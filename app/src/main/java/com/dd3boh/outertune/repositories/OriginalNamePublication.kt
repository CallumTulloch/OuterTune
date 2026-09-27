package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataOriginalPublicationEntity
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Prepare one completed publication batch from a coherent, fully assessed candidate snapshot.
 * Null means assessment is still pending, not a completed UNKNOWN decision. The caller compares
 * the captured input generation and saves this result with its assessments in one transaction.
 * Existing targets are included so removal of their final source commits a null verdict.
 */
internal fun prepareOriginalPublications(
    rows: List<MetadataNameEntity>,
    previous: List<MetadataOriginalPublicationEntity>,
    evaluatedAt: Long,
): List<MetadataOriginalPublicationEntity>? {
    require(evaluatedAt > 0)
    val originals = latestOriginalRows(rows)
    val inputs = originalAssessmentInputs(originals)
    if (originals.any { !hasCurrentOriginalAssessmentInputs(it, inputs) }) return null
    val assessments = originalAssessmentsByTarget(rows)
    // A source snapshot can support its song, album, artists and provider references. Decode
    // each source once here instead of scanning every original again for every display target.
    val originalsBySource = originals.mapNotNull { row ->
        originalCandidate(row)?.sourceVideoId?.let { it to row }
    }.groupBy({ it.first }, { it.second })
    val grouped = rows.groupBy { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
    val previousByTarget = previous.associateBy { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
    return (grouped.keys + previousByTarget.keys).sortedWith(compareBy({ it.kind.name }, { it.id })).map { target ->
        val candidates = grouped[target].orEmpty()
        val evidence = assessments[target].orEmpty()
        // An empty requested language cannot match a stored alias. The normal selector therefore
        // returns a name only when an observed English alias passes the original-name policy.
        // Manual overrides are applied at display time and must never become original evidence.
        val englishName = selectMetadataDisplayName(target, candidates.filter { it.source != "manual" },
            language = "", preferOriginal = true, assessments = evidence)
        val evidenceJson = publicationEvidence(target, englishName, originalsBySource, candidates, evidence)
        val prior = previousByTarget[target]
        if (prior != null && prior.englishName == englishName && prior.evidenceJson == evidenceJson) prior
        else MetadataOriginalPublicationEntity(target.kind.name, target.id, englishName, evidenceJson, evaluatedAt)
    }
}

/** Re-select settings immediately; pending candidates cannot alter the committed English string. */
internal fun selectPublishedMetadataDisplayName(
    target: OriginalNameTarget,
    candidates: List<MetadataNameEntity>,
    language: String,
    preferOriginal: Boolean,
    publication: MetadataOriginalPublicationEntity?,
): String? = selectCommittedMetadataDisplayName(target, candidates, language, preferOriginal,
    publication?.takeIf { it.kind == target.kind.name && it.targetId == target.id }?.englishName)

/** The caller has already joined the committed name to this exact target. */
internal fun selectCommittedMetadataDisplayName(
    target: OriginalNameTarget,
    candidates: List<MetadataNameEntity>,
    language: String,
    preferOriginal: Boolean,
    englishName: String?,
): String? {
    val configured = selectMetadataDisplayName(target, candidates, language, preferOriginal = false)
    val manual = candidates.any {
        it.kind == target.kind.name && it.targetId == target.id && it.source == "manual" && it.name.isNotBlank()
    }
    if (manual || !preferOriginal) return configured
    return englishName?.takeIf(String::isNotBlank) ?: configured
}

/** Preserve the concrete source rows and reference observations used for a completed decision. */
private fun publicationEvidence(
    target: OriginalNameTarget,
    englishName: String?,
    originalsBySource: Map<String, List<MetadataNameEntity>>,
    candidates: List<MetadataNameEntity>,
    assessments: List<OriginalNameAssessment>,
): String {
    val sourceIds = assessments.map { it.sourceVideoId }.toSet()
    val sourceRows = sourceIds.flatMap { originalsBySource[it].orEmpty() }
    // Keep only the newest relation observation, including explicit withdrawal payloads.
    val relations = candidates.filter { it.language == "und" && !it.source.startsWith(ORIGINAL_NAME_SOURCE_PREFIX) }
        .groupBy { it.source }.values.flatMap { observations ->
            val newest = observations.maxOf { it.observedAt }
            observations.filter { it.observedAt == newest }
        }
    fun evidenceRows(values: List<MetadataNameEntity>) = JsonArray(values.sortedWith(
        compareBy(MetadataNameEntity::kind, MetadataNameEntity::targetId, MetadataNameEntity::source, MetadataNameEntity::name)
    ).map { row -> buildJsonObject {
        put("kind", row.kind)
        put("targetId", row.targetId)
        put("name", row.name)
        put("source", row.source)
        put("observedAt", row.observedAt)
        put("evidence", row.originEvidenceJson?.let(::JsonPrimitive) ?: JsonNull)
    } })
    return buildJsonObject {
        put("version", 1)
        put("kind", target.kind.name)
        put("targetId", target.id)
        put("englishName", englishName?.let(::JsonPrimitive) ?: JsonNull)
        put("assessments", JsonArray(assessments.sortedWith(
            compareBy(OriginalNameAssessment::sourceVideoId, OriginalNameAssessment::originalName, { it.language.name })
        ).map { assessment -> buildJsonObject {
            put("sourceVideoId", assessment.sourceVideoId)
            put("originalName", assessment.originalName)
            put("language", assessment.language.name)
            put("confidence", assessment.confidence)
            put("method", assessment.method)
            put("inputFingerprint", assessment.inputFingerprint)
            put("evaluatedAt", assessment.evaluatedAt)
        } }))
        put("sourceSnapshots", evidenceRows(sourceRows))
        put("relations", evidenceRows(relations))
    }.toString()
}
