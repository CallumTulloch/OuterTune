package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameAssessmentCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.MainSongReference
import com.zionhuang.innertube.models.SongItem
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal const val PROVIDER_SONG_REFERENCE_SOURCE_PREFIX = "main-song-reference:"
private const val REFERENCE_FIELD = "providerSongReference"
private const val REFERENCE_VERSION = 1
private val referenceVideoId = Regex("[A-Za-z0-9_-]{11}")
private val referenceAlbumId = Regex("(?:MPRE|FEmusic_library_privately_owned_release)[A-Za-z0-9_-]+")

/** A provider-supplied edge, not another original title or another classifier input. */
internal data class ProviderSongReference(
    val sourceVideoId: String,
    val targetVideoId: String,
    val originalName: String,
    val sourceAlbumId: String,
    val sourceOriginalFingerprint: String,
) {
    fun toMetadataName(observedAt: Long): MetadataNameEntity {
        require(observedAt > 0)
        return MetadataNameEntity("SONG", targetVideoId, "und", originalName,
            PROVIDER_SONG_REFERENCE_SOURCE_PREFIX + sourceVideoId, sourcePriority = 10,
            observedAt = observedAt, originEvidenceJson = ProviderSongReferenceCodec.encode(this))
    }
}

/**
 * The caller supplies observed English Music metadata (fresh or cached) and a validated Main
 * music-card edge. The edge supplies the provider's song-title reference; album ID and exact name
 * only constrain it. Neither a cached album membership nor a matching title establishes a link,
 * equivalent recordings, or interchangeable playback IDs.
 */
internal fun providerSongReference(
    edge: MainSongReference,
    source: ArtTrackOriginalName,
    target: SongItem,
): ProviderSongReference? {
    if (!validDirectSource(source) || edge.sourceVideoId != source.sourceVideoId ||
        edge.targetVideoId != target.id || edge.sourceVideoId == edge.targetVideoId ||
        !referenceVideoId.matches(target.id) || target.album?.id != source.albumId ||
        comparableReferenceName(target.title) != comparableReferenceName(source.name)) return null
    val endpointId = target.endpoint?.videoId
    if (endpointId != null && endpointId != target.id) return null
    val albumId = requireNotNull(source.albumId)
    return ProviderSongReference(source.sourceVideoId, target.id, source.name, albumId,
        sourceFingerprint(source.sourceVideoId, source.name, albumId))
}

/** Every payload field is bound to its row; null evidence is an explicit withdrawn reference. */
internal object ProviderSongReferenceCodec {
    fun encode(reference: ProviderSongReference): String {
        require(validReference(reference))
        return buildJsonObject {
            put(REFERENCE_FIELD, buildJsonObject {
                put("version", REFERENCE_VERSION)
                put("sourceVideoId", reference.sourceVideoId)
                put("targetVideoId", reference.targetVideoId)
                put("originalName", reference.originalName)
                put("sourceAlbumId", reference.sourceAlbumId)
                put("sourceOriginalFingerprint", reference.sourceOriginalFingerprint)
            })
        }.toString()
    }

    fun decode(row: MetadataNameEntity): ProviderSongReference? = runCatching {
        if (row.kind != "SONG" || row.language != "und" ||
            !row.source.startsWith(PROVIDER_SONG_REFERENCE_SOURCE_PREFIX)) return null
        val root = Json.parseToJsonElement(row.originEvidenceJson ?: return null) as? JsonObject ?: return null
        val payload = root[REFERENCE_FIELD] as? JsonObject ?: return null
        val version = payload["version"] as? JsonPrimitive ?: return null
        if (version.isString || version.intOrNull != REFERENCE_VERSION) return null
        fun text(key: String) = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val reference = ProviderSongReference(
            text("sourceVideoId") ?: return null, text("targetVideoId") ?: return null,
            text("originalName") ?: return null, text("sourceAlbumId") ?: return null,
            text("sourceOriginalFingerprint") ?: return null,
        )
        reference.takeIf { validReference(it) && it.targetVideoId == row.targetId &&
            it.originalName == row.name && row.source == PROVIDER_SONG_REFERENCE_SOURCE_PREFIX + it.sourceVideoId }
    }.getOrNull()
}

/**
 * Reuse only a current direct source assessment, adapting its target in memory for display policy.
 * Never persist the adapted assessment: its source remains another video, which the direct codec
 * deliberately rejects. References cannot feed the classifier or serve as another edge's source.
 */
internal fun associatedOriginalAssessments(
    rows: List<MetadataNameEntity>,
    publicationInputs: OriginalPublicationInputs = OriginalPublicationInputs(rows),
): List<OriginalNameAssessment> {
    // Choose the latest observation before decoding: an invalid/null newer payload must not revive
    // an older edge. Ties retain conflicting valid names for the existing conservative policy.
    val references = rows.filter { it.kind == "SONG" && it.language == "und" &&
        it.source.startsWith(PROVIDER_SONG_REFERENCE_SOURCE_PREFIX) }
        .groupBy { it.targetId to it.source }.values.flatMap { observations ->
            val latest = observations.maxOf { it.observedAt }
            observations.filter { it.observedAt == latest }.mapNotNull(ProviderSongReferenceCodec::decode)
        }.distinct()
    if (references.isEmpty()) return emptyList()
    val requiredSources = references.map { it.sourceVideoId }.toSet()
    val directRows = publicationInputs.originals
    val inputs = publicationInputs.assessmentInputs
    val unambiguousSources = directRows.filter { it.kind == "SONG" && it.targetId in requiredSources }
        .groupBy { it.targetId }.filterValues { sourceRows ->
            val candidates = sourceRows.mapNotNull(::originalCandidate)
            candidates.all(::validDirectSource) && candidates.map {
                sourceFingerprint(it.sourceVideoId, it.name, it.albumId!!)
            }.distinct().size == 1
        }.keys
    val sources = directRows.mapNotNull { row ->
        if (row.kind != "SONG" || row.targetId !in unambiguousSources) return@mapNotNull null
        val candidate = originalCandidate(row)?.takeIf(::validDirectSource) ?: return@mapNotNull null
        if (!hasCurrentOriginalAssessmentInputs(row, inputs)) return@mapNotNull null
        val assessment = OriginalNameAssessmentCodec.decode(row.originEvidenceJson, candidate.target, candidate.name)
            ?: return@mapNotNull null
        if (assessment.sourceVideoId != candidate.sourceVideoId ||
            !assessment.method.startsWith(OriginalAlbumLanguageResolver.METHOD_VERSION + "/") ||
            assessment.method.length <= OriginalAlbumLanguageResolver.METHOD_VERSION.length + 1) return@mapNotNull null
        Triple(candidate, sourceFingerprint(candidate.sourceVideoId, candidate.name, candidate.albumId!!), assessment)
    }.groupBy { it.first.sourceVideoId }

    return references.flatMap { reference ->
        sources[reference.sourceVideoId].orEmpty().mapNotNull { (candidate, fingerprint, assessment) ->
            if (candidate.albumId != reference.sourceAlbumId ||
                comparableReferenceName(candidate.name) != comparableReferenceName(reference.originalName) ||
                fingerprint != reference.sourceOriginalFingerprint) null
            else assessment.copy(target = OriginalNameTarget(OriginalNameKind.SONG, reference.targetVideoId))
        }
    }.distinct()
}

private fun validDirectSource(source: ArtTrackOriginalName): Boolean =
    source.target.kind == OriginalNameKind.SONG && source.target.id == source.sourceVideoId &&
        referenceVideoId.matches(source.sourceVideoId) && validReferenceName(source.name) &&
        source.albumId?.let(referenceAlbumId::matches) == true

private fun validReference(reference: ProviderSongReference): Boolean =
    referenceVideoId.matches(reference.sourceVideoId) && referenceVideoId.matches(reference.targetVideoId) &&
        reference.sourceVideoId != reference.targetVideoId && validReferenceName(reference.originalName) &&
        referenceAlbumId.matches(reference.sourceAlbumId) &&
        reference.sourceOriginalFingerprint == sourceFingerprint(reference.sourceVideoId,
            reference.originalName, reference.sourceAlbumId)

private fun validReferenceName(name: String): Boolean = name.isNotBlank() && name.none { it == '\n' || it == '\r' }
private fun comparableReferenceName(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)

/** Length-prefix fields so punctuation cannot collide; observing the same original again is stable. */
private fun sourceFingerprint(sourceVideoId: String, name: String, albumId: String): String {
    val input = listOf(sourceVideoId, comparableReferenceName(name), albumId).joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
