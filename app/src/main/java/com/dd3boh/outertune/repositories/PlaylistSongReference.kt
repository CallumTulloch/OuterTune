package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameAssessmentCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.SongItem
import java.security.MessageDigest
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal const val PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX = "playlist-song-reference:"
private const val PLAYLIST_REFERENCE_FIELD = "playlistSongReference"
private const val PLAYLIST_REFERENCE_VERSION = 3
private val playlistVideoId = Regex("[A-Za-z0-9_-]{11}")
private val playlistAlbumId = Regex("(?:MPRE|FEmusic_library_privately_owned_release)[A-Za-z0-9_-]+")
private val playlistToken = Regex("[A-Za-z0-9_-]{1,512}")

internal fun playlistSongReferenceSource(sourceVideoId: String, playlistId: String): String =
    "$PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX$sourceVideoId:$playlistId"

/** An explicit shared playlist entry lends a title; it never makes playback IDs interchangeable. */
internal data class AlbumPlaylistSongReference(
    val playlistId: String,
    val playlistSetVideoId: String,
    val sourceVideoId: String,
    val targetVideoId: String,
    val originalName: String,
    val sourceAlbumId: String,
    val targetAlbumId: String,
    val sourceOriginalFingerprint: String,
    // An independently observed Music name can corroborate the explicit playlist identity even
    // when Main's actual original contains an edition suffix. It is not itself a new original.
    val sourceMusicName: String = originalName,
) {
    fun toMetadataName(observedAt: Long): MetadataNameEntity {
        require(observedAt > 0)
        return MetadataNameEntity("SONG", targetVideoId, "und", originalName,
            playlistSongReferenceSource(sourceVideoId, playlistId), sourcePriority = 10,
            observedAt = observedAt, originEvidenceJson = PlaylistSongReferenceCodec.encode(this))
    }
}

/**
 * [edge] must come from the provider's matching playlistSetVideoId, never a title/index join.
 * The current album's playlist, observed English target name and direct source original constrain
 * that explicit relation. If Music uses a shorter title, independent source/target Music names
 * must agree; the actual Main original remains unchanged. This does not establish an artist,
 * an album name or recording equality.
 * [expectedAlbumId] comes from the album whose canonical playlist was verified. The same source
 * may also occur on another edition without changing its existing language-assessment context.
 */
internal fun playlistSongReference(
    edge: PlaylistSongReference,
    source: ArtTrackOriginalName,
    target: SongItem,
    albumPlaylistId: String,
    expectedAlbumId: String? = source.albumId,
    sourceMusic: SongItem? = null,
): AlbumPlaylistSongReference? {
    if (!validPlaylistOriginal(source) || !playlistToken.matches(edge.playlistId) ||
        !playlistToken.matches(edge.playlistSetVideoId) || edge.playlistId != albumPlaylistId ||
        edge.sourceVideoId != source.sourceVideoId || edge.targetVideoId != target.id ||
        edge.sourceVideoId == edge.targetVideoId || !playlistVideoId.matches(target.id) ||
        expectedAlbumId?.let(playlistAlbumId::matches) != true || target.album?.id != expectedAlbumId ||
        !validPlaylistName(target.title)) return null
    if (target.endpoint?.videoId?.let { it != target.id } == true) return null
    val musicName = if (playlistComparable(target.title) == playlistComparable(source.name)) source.name else {
        // Names never create this edge. The complete canonical playlist has already joined the
        // two video IDs by its stable entry ID; matching independent Music observations only
        // corroborate that edge. Keep Main's original verbatim, including any version suffix.
        if (sourceMusic == null || sourceMusic.id != source.sourceVideoId ||
            sourceMusic.album?.id != expectedAlbumId || !validPlaylistName(sourceMusic.title) ||
            sourceMusic.endpoint?.videoId?.let { it != sourceMusic.id } == true ||
            playlistComparable(sourceMusic.title) != playlistComparable(target.title)) return null
        sourceMusic.title
    }
    return AlbumPlaylistSongReference(edge.playlistId, edge.playlistSetVideoId, source.sourceVideoId,
        target.id, source.name, requireNotNull(source.albumId), requireNotNull(expectedAlbumId),
        playlistOriginalFingerprint(source), musicName)
}

/** All identities are bound to the row and a fingerprint covering the complete reference. */
internal object PlaylistSongReferenceCodec {
    fun encode(reference: AlbumPlaylistSongReference): String {
        require(validPlaylistReference(reference))
        return buildJsonObject {
            put(PLAYLIST_REFERENCE_FIELD, buildJsonObject {
                put("version", PLAYLIST_REFERENCE_VERSION)
                put("playlistId", reference.playlistId)
                put("playlistSetVideoId", reference.playlistSetVideoId)
                put("sourceVideoId", reference.sourceVideoId)
                put("targetVideoId", reference.targetVideoId)
                put("originalName", reference.originalName)
                put("sourceAlbumId", reference.sourceAlbumId)
                put("targetAlbumId", reference.targetAlbumId)
                put("sourceOriginalFingerprint", reference.sourceOriginalFingerprint)
                put("sourceMusicName", reference.sourceMusicName)
                put("referenceFingerprint", playlistReferenceFingerprint(reference))
            })
        }.toString()
    }

    fun decode(row: MetadataNameEntity): AlbumPlaylistSongReference? = runCatching {
        if (row.kind != "SONG" || row.language != "und" ||
            !row.source.startsWith(PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX)) return null
        val root = Json.parseToJsonElement(row.originEvidenceJson ?: return null) as? JsonObject ?: return null
        val payload = root[PLAYLIST_REFERENCE_FIELD] as? JsonObject ?: return null
        val version = payload["version"] as? JsonPrimitive ?: return null
        if (version.isString || version.intOrNull !in setOf(2, PLAYLIST_REFERENCE_VERSION)) return null
        fun text(key: String) = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val reference = AlbumPlaylistSongReference(
            text("playlistId") ?: return null, text("playlistSetVideoId") ?: return null,
            text("sourceVideoId") ?: return null, text("targetVideoId") ?: return null,
            text("originalName") ?: return null, text("sourceAlbumId") ?: return null,
            text("targetAlbumId") ?: return null,
            text("sourceOriginalFingerprint") ?: return null,
            if (version.intOrNull == 2) text("originalName") ?: return null
            else text("sourceMusicName") ?: return null,
        )
        reference.takeIf { validPlaylistReference(it) && it.targetVideoId == row.targetId &&
            it.originalName == row.name && row.source == playlistSongReferenceSource(it.sourceVideoId, it.playlistId) &&
            text("referenceFingerprint") == playlistReferenceFingerprint(it, version.intOrNull == 2) }
    }.getOrNull()
}

/** Adapt direct, current assessments in memory only. References never become classifier inputs. */
internal fun playlistAssociatedOriginalAssessments(rows: List<MetadataNameEntity>): List<OriginalNameAssessment> {
    // Select the latest observation before decoding, so a newer explicit {} withdrawal wins.
    val references = rows.filter { it.kind == "SONG" && it.language == "und" &&
        it.source.startsWith(PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX) }
        .groupBy { it.targetId to it.source }.values.flatMap { observations ->
            val latest = observations.maxOf { it.observedAt }
            observations.filter { it.observedAt == latest }.mapNotNull(PlaylistSongReferenceCodec::decode)
        }.distinct()
    if (references.isEmpty()) return emptyList()
    val sourceIds = references.map { it.sourceVideoId }.toSet()
    val directRows = latestOriginalRows(rows)
    val inputs = originalAssessmentInputs(directRows)
    val unambiguousSources = directRows.filter { it.kind == "SONG" && it.targetId in sourceIds }
        .groupBy { it.targetId }.filterValues { sourceRows ->
            val candidates = sourceRows.mapNotNull(::originalCandidate)
            candidates.all(::validPlaylistOriginal) && candidates.map(::playlistOriginalFingerprint).distinct().size == 1
        }.keys
    val sources = directRows.mapNotNull { row ->
        if (row.kind != "SONG" || row.targetId !in unambiguousSources) return@mapNotNull null
        val candidate = originalCandidate(row)?.takeIf(::validPlaylistOriginal) ?: return@mapNotNull null
        if (!hasCurrentOriginalAssessmentInputs(row, inputs)) return@mapNotNull null
        val assessment = OriginalNameAssessmentCodec.decode(row.originEvidenceJson, candidate.target, candidate.name)
            ?: return@mapNotNull null
        if (assessment.sourceVideoId != candidate.sourceVideoId ||
            !assessment.method.startsWith(OriginalAlbumLanguageResolver.METHOD_VERSION + "/") ||
            assessment.method.length <= OriginalAlbumLanguageResolver.METHOD_VERSION.length + 1) return@mapNotNull null
        Triple(candidate, playlistOriginalFingerprint(candidate), assessment)
    }.groupBy { it.first.sourceVideoId }
    return references.flatMap { reference ->
        sources[reference.sourceVideoId].orEmpty().mapNotNull { (candidate, fingerprint, assessment) ->
            if (candidate.albumId != reference.sourceAlbumId || fingerprint != reference.sourceOriginalFingerprint ||
                playlistComparable(candidate.name) != playlistComparable(reference.originalName)) null
            else assessment.copy(target = OriginalNameTarget(OriginalNameKind.SONG, reference.targetVideoId))
        }
    }.distinct()
}

private fun validPlaylistOriginal(source: ArtTrackOriginalName): Boolean =
    source.target.kind == OriginalNameKind.SONG && source.target.id == source.sourceVideoId &&
        playlistVideoId.matches(source.sourceVideoId) && validPlaylistName(source.name) &&
        source.albumId?.let(playlistAlbumId::matches) == true

private fun validPlaylistReference(reference: AlbumPlaylistSongReference): Boolean =
    playlistToken.matches(reference.playlistId) && playlistToken.matches(reference.playlistSetVideoId) &&
        playlistVideoId.matches(reference.sourceVideoId) && playlistVideoId.matches(reference.targetVideoId) &&
        reference.sourceVideoId != reference.targetVideoId && validPlaylistName(reference.originalName) &&
        validPlaylistName(reference.sourceMusicName) &&
        playlistAlbumId.matches(reference.sourceAlbumId) && playlistAlbumId.matches(reference.targetAlbumId) &&
        reference.sourceOriginalFingerprint == playlistDigest(listOf(reference.sourceVideoId,
            playlistComparable(reference.originalName), reference.sourceAlbumId))

private fun validPlaylistName(name: String): Boolean = name.isNotBlank() && name.none { it == '\n' || it == '\r' }
private fun playlistComparable(name: String): String = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
private fun playlistOriginalFingerprint(source: ArtTrackOriginalName): String =
    playlistDigest(listOf(source.sourceVideoId, playlistComparable(source.name), source.albumId.orEmpty()))
private fun playlistReferenceFingerprint(reference: AlbumPlaylistSongReference, legacy: Boolean = false): String = playlistDigest(listOf(
    reference.playlistId, reference.playlistSetVideoId, reference.sourceVideoId, reference.targetVideoId,
    reference.originalName, reference.sourceAlbumId, reference.targetAlbumId, reference.sourceOriginalFingerprint,
) + if (legacy) emptyList() else listOf(reference.sourceMusicName))

private fun playlistDigest(fields: List<String>): String = MessageDigest.getInstance("SHA-256")
    .digest(fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
