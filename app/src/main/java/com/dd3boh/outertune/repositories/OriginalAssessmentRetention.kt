package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameAssessmentCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

private const val INPUT_SET_FIELD = "assessmentInputSet"
private const val INPUT_SET_VERSION = 3

/**
 * Index once per snapshot. Language detection depends on a name and the songs directly linked to
 * its album, not every previously browsed album. Conflicts between observations of a shared
 * target are resolved at publication; they do not change another candidate's language estimate.
 */
internal class OriginalAssessmentInputs internal constructor(
    internal val candidates: Set<ArtTrackOriginalName>,
    private val songsByAlbum: Map<String, List<ArtTrackOriginalName>>,
    private val candidateFingerprints: Map<ArtTrackOriginalName, String>,
) {
    // Kept for diagnostics; validation uses the scoped fingerprint below.
    internal val fingerprint: String by lazy { inputSetFingerprint(candidates) }

    internal fun fingerprintFor(candidate: ArtTrackOriginalName): String? = candidateFingerprints[candidate]

    /** Include the complete album evidence even when only one of its names needs a new result. */
    internal fun withAlbumContext(values: Collection<ArtTrackOriginalName>): List<ArtTrackOriginalName> {
        require(values.all { it in candidates })
        val albums = values.mapNotNull { it.albumId }.toSet()
        return (values + albums.flatMap { songsByAlbum[it].orEmpty() }).distinct()
    }
}

internal fun originalAssessmentInputs(rows: List<MetadataNameEntity>): OriginalAssessmentInputs {
    val candidates = effectiveCandidates(rows)
    val songsByAlbum = candidates.filter { it.target.kind == OriginalNameKind.SONG && !it.albumId.isNullOrBlank() }
        .groupBy { it.albumId!! }
    // Hash each shared group once. Repeating the entire album/artist input in every row would
    // turn this lightweight invalidation check into quadratic work on large libraries.
    val albumFingerprints = songsByAlbum.mapValues { (_, songs) -> inputSetFingerprint(songs) }
    val fingerprints = candidates.associateWith { candidate ->
        fingerprint(listOf(encodedCandidate(candidate),
            candidate.albumId?.let(albumFingerprints::get).orEmpty()))
    }
    return OriginalAssessmentInputs(candidates, songsByAlbum, fingerprints)
}

/** Record this candidate's complete dependency set alongside its unchanged assessment codec. */
internal fun encodeOriginalAssessment(
    candidate: ArtTrackOriginalName,
    assessment: OriginalNameAssessment,
    evaluationRows: List<MetadataNameEntity>,
): String = encodeOriginalAssessment(candidate, assessment, originalAssessmentInputs(evaluationRows))

internal fun encodeOriginalAssessment(
    candidate: ArtTrackOriginalName,
    assessment: OriginalNameAssessment,
    inputs: OriginalAssessmentInputs,
): String {
    require(assessment.target == candidate.target && assessment.originalName == candidate.name &&
        assessment.sourceVideoId == candidate.sourceVideoId)
    require(currentMethod(assessment.method))
    require(candidate in inputs.candidates)
    val encoded = Json.parseToJsonElement(ArtTrackOriginalNameCodec.encode(candidate, assessment)) as JsonObject
    return buildJsonObject {
        encoded.forEach { (key, value) -> put(key, value) }
        put(INPUT_SET_FIELD, buildJsonObject {
            put("version", INPUT_SET_VERSION)
            put("model", OriginalAlbumLanguageResolver.METHOD_VERSION)
            put("fingerprint", inputs.fingerprintFor(candidate)!!)
        })
    }.toString()
}

/** Also rejects legacy assessments until they have been evaluated with a recorded input set. */
internal fun hasCurrentOriginalAssessmentInputs(
    row: MetadataNameEntity,
    evaluationRows: List<MetadataNameEntity>,
): Boolean = hasCurrentOriginalAssessmentInputs(row, originalAssessmentInputs(evaluationRows))

internal fun hasCurrentOriginalAssessmentInputs(
    row: MetadataNameEntity,
    inputs: OriginalAssessmentInputs,
): Boolean {
    val candidate = originalCandidate(row) ?: return false
    val fingerprint = inputs.fingerprintFor(candidate) ?: return false
    return currentAssessmentFor(row, fingerprint) != null
}

/**
 * Retain assessments only for a complete, unchanged source snapshot and unchanged model inputs.
 * Call inside the transaction that reads [previousNames] and commits [incomingSnapshot]. The caller
 * still owns source withdrawal: rows omitted by the incoming snapshot must cease to be current.
 */
internal fun retainOriginalAssessments(
    previousNames: List<MetadataNameEntity>,
    sourceVideoId: String,
    incomingSnapshot: List<MetadataNameEntity>,
): List<MetadataNameEntity> {
    if (incomingSnapshot.isEmpty()) return incomingSnapshot
    val incoming = incomingSnapshot.map { row ->
        val candidate = originalCandidate(row) ?: return incomingSnapshot
        if (candidate.sourceVideoId != sourceVideoId) return incomingSnapshot
        candidate
    }
    // Partial/mixed snapshots cannot establish the same source observation.
    if (incomingSnapshot.map { it.observedAt }.distinct().size != 1) return incomingSnapshot
    val previousCurrent = latestOriginalRows(previousNames)
    val sourceRows = previousCurrent.filter { originalCandidate(it)?.sourceVideoId == sourceVideoId }
    if (sourceRows.isEmpty() || incomingSnapshot.first().observedAt < sourceRows.maxOf { it.observedAt }) {
        return incomingSnapshot
    }
    if (sourceRows.mapNotNull(::originalCandidate).toSet() != incoming.toSet()) return incomingSnapshot

    // Include other videos: short titles and artist names can depend on their album's other songs.
    // Replacing one source also removes any obsolete aliases from the classification input.
    val prospective = previousCurrent.filter { originalCandidate(it)?.sourceVideoId != sourceVideoId } + incomingSnapshot
    val inputs = originalAssessmentInputs(prospective)
    val previousByCandidate = sourceRows.associateBy(::originalCandidate)
    return incomingSnapshot.map { row ->
        val previous = previousByCandidate[originalCandidate(row)] ?: return@map row
        if (!hasCurrentOriginalAssessmentInputs(previous, inputs)) row
        else row.copy(originEvidenceJson = previous.originEvidenceJson)
    }
}

private fun currentAssessmentFor(row: MetadataNameEntity, fingerprint: String): OriginalNameAssessment? {
    val candidate = originalCandidate(row) ?: return null
    val assessment = OriginalNameAssessmentCodec.decode(row.originEvidenceJson, candidate.target, candidate.name)
        ?: return null
    if (assessment.originalName != candidate.name || assessment.sourceVideoId != candidate.sourceVideoId ||
        !currentMethod(assessment.method)) return null
    val root = runCatching { Json.parseToJsonElement(row.originEvidenceJson!!) as? JsonObject }.getOrNull()
        ?: return null
    val inputs = root[INPUT_SET_FIELD] as? JsonObject ?: return null
    val version = inputs["version"] as? JsonPrimitive ?: return null
    if (version.isString || version.intOrNull != INPUT_SET_VERSION) return null
    fun text(key: String) = (inputs[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    if (text("model") != OriginalAlbumLanguageResolver.METHOD_VERSION || text("fingerprint") != fingerprint) return null
    return assessment
}

private fun currentMethod(method: String): Boolean =
    method.startsWith(OriginalAlbumLanguageResolver.METHOD_VERSION + "/") &&
        method.length > OriginalAlbumLanguageResolver.METHOD_VERSION.length + 1

private fun effectiveCandidates(rows: List<MetadataNameEntity>): Set<ArtTrackOriginalName> =
    latestOriginalRows(rows).mapNotNull(::originalCandidate).toSet()

/** Observation time guards concurrent commits elsewhere; it is not an input to language detection. */
private fun inputSetFingerprint(values: Collection<ArtTrackOriginalName>): String =
    fingerprint(values.map(::encodedCandidate).sorted())

private fun encodedCandidate(candidate: ArtTrackOriginalName): String =
    listOf(candidate.target.kind.name, candidate.target.id, candidate.name, candidate.sourceVideoId,
        candidate.albumId.orEmpty()).joinToString("") { encoded(it) }

private fun encoded(value: String) = "${value.length}:$value"

private fun fingerprint(parts: List<String>): String {
    val input = (listOf(INPUT_SET_VERSION.toString(), OriginalNameAssessmentCodec.RESOLVER_VERSION.toString(),
        OriginalAlbumLanguageResolver.METHOD_VERSION) + parts).joinToString("") { encoded(it) }
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    val digits = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 15])
        }
    }
}
