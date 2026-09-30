package com.dd3boh.outertune.models.metadata

import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A versioned automatic language assessment of a name supplied by an already verified Art Track.
 * This records the assessment's inputs and method; it does not turn a model estimate into a review.
 */
data class OriginalNameAssessment(
    val target: OriginalNameTarget,
    val originalName: String,
    val sourceVideoId: String,
    val sourceUrl: String,
    val language: OriginalNameLanguage,
    val confidence: Float,
    val method: String,
    val inputFingerprint: String,
    val evaluatedAt: Long,
    val resolverVersion: Int = OriginalNameAssessmentCodec.RESOLVER_VERSION,
    val formatVersion: Int = 1,
)

/** Explicit JSON avoids relying on a serialization compiler plugin in the app module. */
object OriginalNameAssessmentCodec {
    const val FORMAT_VERSION = 1
    const val RESOLVER_VERSION = 2
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")

    /** Invalid generated assessments must be fixed by their producer instead of being persisted. */
    fun encode(assessment: OriginalNameAssessment): String {
        requireValid(assessment)
        return buildJsonObject {
            put("formatVersion", assessment.formatVersion)
            put("resolverVersion", assessment.resolverVersion)
            put("target", buildJsonObject {
                put("kind", assessment.target.kind.name)
                put("id", assessment.target.id)
            })
            put("originalName", assessment.originalName)
            put("sourceVideoId", assessment.sourceVideoId)
            put("sourceUrl", assessment.sourceUrl)
            put("language", assessment.language.name)
            put("confidence", assessment.confidence)
            put("method", assessment.method)
            put("inputFingerprint", assessment.inputFingerprint)
            put("evaluatedAt", assessment.evaluatedAt)
        }.toString()
    }

    /**
     * Unknown/legacy formats remain unconfirmed. Bind the payload to its DB row before using it;
     * a valid assessment for another name, entity kind or ID cannot affect this row's display.
     */
    fun decode(
        value: String?,
        expectedTarget: OriginalNameTarget,
        expectedName: String,
    ): OriginalNameAssessment? = originalEvidenceCache.decode(value, expectedTarget, expectedName).assessment

    internal fun decodeRoot(
        root: JsonObject?,
        expectedTarget: OriginalNameTarget,
        expectedName: String,
    ): OriginalNameAssessment? {
        if (root == null || expectedTarget.id.isBlank() || expectedName.isBlank()) return null
        return try {
            val formatVersion = root.requiredNumber("formatVersion").intOrNull ?: return null
            val resolverVersion = root.requiredNumber("resolverVersion").intOrNull ?: return null
            if (formatVersion != FORMAT_VERSION || resolverVersion != RESOLVER_VERSION) return null
            val target = root["target"] as? JsonObject ?: return null
            val confidence = root.requiredNumber("confidence").doubleOrNull ?: return null
            // Check the decoded number before Float conversion: 1.00000001 must not round to 1.
            if (!confidence.isFinite() || confidence !in 0.0..1.0) return null
            val assessment = OriginalNameAssessment(
                target = OriginalNameTarget(
                    OriginalNameKind.valueOf(target.requiredText("kind")),
                    target.requiredText("id"),
                ),
                originalName = root.requiredText("originalName"),
                sourceVideoId = root.requiredText("sourceVideoId"),
                sourceUrl = root.requiredText("sourceUrl"),
                language = OriginalNameLanguage.valueOf(root.requiredText("language")),
                confidence = confidence.toFloat(),
                method = root.requiredText("method"),
                inputFingerprint = root.requiredText("inputFingerprint"),
                evaluatedAt = root.requiredNumber("evaluatedAt").longOrNull ?: return null,
                resolverVersion = resolverVersion,
                formatVersion = formatVersion,
            )
            requireValid(assessment)
            if (assessment.target != expectedTarget || comparableName(assessment.originalName) != comparableName(expectedName)) null
            else assessment
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun requireValid(assessment: OriginalNameAssessment) {
        require(assessment.formatVersion == FORMAT_VERSION && assessment.resolverVersion == RESOLVER_VERSION) {
            "Unsupported original name assessment version"
        }
        require(assessment.target.id.isNotBlank() && assessment.target.id.none(Char::isWhitespace)) { "Missing assessment target ID" }
        require(assessment.originalName.isNotBlank()) { "Missing assessed original name" }
        require(videoIdPattern.matches(assessment.sourceVideoId)) { "Invalid assessment source video ID" }
        require(assessment.sourceUrl == "https://www.youtube.com/watch?v=${assessment.sourceVideoId}") {
            "Assessment source URL does not identify its Main video"
        }
        require(assessment.target.kind != OriginalNameKind.SONG || assessment.target.id == assessment.sourceVideoId) {
            "A song assessment must refer to the same video ID"
        }
        require(assessment.confidence.isFinite() && assessment.confidence in 0f..1f) { "Invalid assessment confidence" }
        require(assessment.method.isNotBlank() && assessment.inputFingerprint.isNotBlank()) { "Missing assessment method or inputs" }
        require(assessment.evaluatedAt > 0L) { "Invalid assessment evaluation time" }
    }

    private fun JsonObject.requiredText(key: String): String {
        val value = get(key) as? JsonPrimitive
        require(value != null && value.isString && value.content.isNotBlank()) { "Assessment requires text for $key" }
        return value.content
    }

    private fun JsonObject.requiredNumber(key: String): JsonPrimitive {
        val value = get(key) as? JsonPrimitive
        require(value != null && !value.isString) { "Assessment requires a number for $key" }
        return value
    }

    private fun comparableName(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
}
