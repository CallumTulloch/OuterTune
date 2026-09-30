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
import java.security.MessageDigest

/**
 * Prepare independently completed display decisions from a coherent candidate snapshot.
 * Pending targets are omitted, preserving their last publication. The caller compares each
 * target's full dependency set before saving it with its assessments in one transaction.
 * Existing targets are included so removal of their final source commits a null verdict.
 */
internal fun prepareOriginalPublications(
    rows: List<MetadataNameEntity>,
    previous: List<MetadataOriginalPublicationEntity>,
    evaluatedAt: Long,
): List<MetadataOriginalPublicationEntity> {
    return OriginalPublicationPreparer().prepare(rows, previous, evaluatedAt)
}

/** Owned by the serial publication worker. Reuse only an identical complete dependency set and
 * its known previous/result publication. A concurrent external commit must be checked afresh.
 */
internal class OriginalPublicationPreparer {
    // Publication proof can dwarf the candidate rows. Retain a digest, never another generation
    // of the database's proof strings; return the caller's current committed row on a cache hit.
    private data class PublicationState(val name: String?, val evaluatedAt: Long, val proof: String)
    private val proofDigest = MessageDigest.getInstance("SHA-256")
    private val proofBuffer = ByteArray(8 * 1024)
    private val proofCharacters = CharArray(proofBuffer.size / 2)

    private fun state(row: MetadataOriginalPublicationEntity): PublicationState {
        // Hash exact UTF-16 code units in bounded chunks. Converting every proof to a second
        // full byte array costs tens of MiB per library observation even when nothing changed.
        proofDigest.reset()
        var offset = 0
        while (offset < row.evidenceJson.length) {
            val count = minOf(proofCharacters.size, row.evidenceJson.length - offset)
            row.evidenceJson.toCharArray(proofCharacters, 0, offset, offset + count)
            for (index in 0 until count) {
                val code = proofCharacters[index].code
                proofBuffer[index * 2] = (code ushr 8).toByte()
                proofBuffer[index * 2 + 1] = code.toByte()
            }
            proofDigest.update(proofBuffer, 0, count * 2)
            offset += count
        }
        val bytes = proofDigest.digest()
        val digits = "0123456789abcdef"
        val proof = buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 15])
            }
        }
        return PublicationState(row.englishName, row.evaluatedAt, proof)
    }

    private data class Cached(
        val input: OriginalPublicationInput,
        val rows: List<MetadataNameEntity>,
        val previous: PublicationState?,
        val result: PublicationState?,
    )
    private var cached = emptyMap<OriginalNameTarget, Cached>()

    internal data class Change(val previous: MetadataOriginalPublicationEntity?, val publication: MetadataOriginalPublicationEntity)
    internal data class Batch(val changes: List<Change>, val hasMore: Boolean)

    /** Only changed proofs escape this call; an unchanged database row is collectible immediately.
     * Bound migrations too, so a large first publication cannot assemble every proof at once.
     */
    fun prepareChanges(rows: List<MetadataNameEntity>, previous: Sequence<MetadataOriginalPublicationEntity>,
        evaluatedAt: Long, maxChanges: Int = 64): Batch {
        require(maxChanges > 0)
        return prepareInternal(rows, previous, evaluatedAt, maxChanges, null)
    }

    fun prepare(
        rows: List<MetadataNameEntity>,
        previous: List<MetadataOriginalPublicationEntity>,
        evaluatedAt: Long,
    ): List<MetadataOriginalPublicationEntity> {
        val result = mutableListOf<MetadataOriginalPublicationEntity>()
        prepareInternal(rows, previous.asSequence(), evaluatedAt, Int.MAX_VALUE, result)
        return result.sortedWith(compareBy({ it.kind }, { it.targetId }))
    }

    private fun prepareInternal(rows: List<MetadataNameEntity>, previous: Sequence<MetadataOriginalPublicationEntity>,
        evaluatedAt: Long, maxChanges: Int, result: MutableList<MetadataOriginalPublicationEntity>?): Batch {
        require(evaluatedAt > 0)
        val inputs = OriginalPublicationInputs(rows)
        val assessments by lazy { originalAssessmentsByTarget(rows, inputs) }
        // A source snapshot can support its song, album, artists and provider references. Decode
        // each source once here instead of scanning every original again for every display target.
        val originalsBySource = inputs.bySource
        val grouped = rows.groupBy { OriginalNameTarget(OriginalNameKind.valueOf(it.kind), it.targetId) }
        val next = mutableMapOf<OriginalNameTarget, Cached>()
        val changes = mutableListOf<Change>()
        val visited = mutableSetOf<OriginalNameTarget>()
        fun prepareTarget(target: OriginalNameTarget, prior: MetadataOriginalPublicationEntity?) {
            visited += target
            val input = inputs.forTarget(target)
            val candidates = grouped[target].orEmpty()
            val priorState = prior?.let(::state)
            val old = cached[target]
            if (old != null && old.input == input && old.rows == candidates &&
                priorState == (old.result ?: old.previous)) {
                // Rebind the dependency rows too, so old Room snapshots can be collected.
                next[target] = Cached(input, candidates, priorState, old.result)
                if (old.result != null && prior != null) result?.add(prior)
                return
            }
            if (!inputs.ready(target)) {
                next[target] = Cached(input, candidates, priorState, null)
                return
            }
            val evidence = assessments[target].orEmpty()
            // An empty requested language cannot match a stored alias. The normal selector therefore
            // returns a name only when an observed English alias passes the original-name policy.
            // Manual overrides are applied at display time and must never become original evidence.
            val englishName = selectMetadataDisplayName(target, candidates.filter { it.source != "manual" },
                language = "", preferOriginal = true, assessments = evidence)
            val evidenceJson = publicationEvidence(target, englishName, originalsBySource, candidates, evidence)
            val publication = if (prior != null && prior.englishName == englishName && prior.evidenceJson == evidenceJson) prior
            else MetadataOriginalPublicationEntity(target.kind.name, target.id, englishName, evidenceJson, evaluatedAt)
            next[target] = Cached(input, candidates, priorState, state(publication))
            result?.add(publication)
            if (publication != prior) changes += Change(prior, publication)
        }
        for (prior in previous) {
            if (changes.size == maxChanges) { cached = next; return Batch(changes, true) }
            prepareTarget(OriginalNameTarget(OriginalNameKind.valueOf(prior.kind), prior.targetId), prior)
        }
        for (target in grouped.keys.sortedWith(compareBy({ it.kind.name }, { it.id }))) {
            if (target in visited) continue
            if (changes.size == maxChanges) { cached = next; return Batch(changes, true) }
            prepareTarget(target, null)
        }
        cached = next
        return Batch(changes, false)
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
