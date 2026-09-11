package com.dd3boh.outertune.models.metadata

import java.text.Normalizer

enum class OriginalNameKind { SONG, ALBUM, ARTIST }

data class OriginalNameTarget(val kind: OriginalNameKind, val id: String)

enum class OriginalNameLanguage { ENGLISH, OTHER, UNKNOWN }

enum class OriginalNameVerification { CONFIRMED, UNVERIFIED, REJECTED }

enum class OriginalNameSourceKind {
    RIGHTS_HOLDER,
    DISTRIBUTOR_ORIGINAL_METADATA,
    YOUTUBE_MAIN,
    METADATA_CATALOG,
}

/**
 * Evidence about one name, not the request locale, nationality or language of the recording.
 * CONFIRMED requires a review of both the formal name and its connection to this exact target.
 * A source's English interface, English alias or Latin script is not such a review.
 */
data class OriginalNameEvidence(
    val target: OriginalNameTarget,
    val originalName: String,
    val source: OriginalNameSourceKind,
    val sourceUrl: String,
    val language: OriginalNameLanguage = OriginalNameLanguage.UNKNOWN,
    val verification: OriginalNameVerification = OriginalNameVerification.UNVERIFIED,
    val reviewedOn: String? = null,
    val reviewNote: String = "",
)

/**
 * An empty result means there is no evidence for this target. Network implementations must
 * propagate failures and cancellation so callers can distinguish missing evidence from retryable
 * failures. Sources must not discover identity by matching a translated name or result position.
 */
fun interface OriginalNameEvidenceSource {
    suspend fun findEvidence(target: OriginalNameTarget): List<OriginalNameEvidence>
}

enum class OriginalNameSelectionReason {
    CONFIGURED_LANGUAGE,
    VERIFIED_ENGLISH_ORIGINAL,
    AUTOMATIC_ENGLISH_ORIGINAL,
    ORIGINAL_UNCONFIRMED,
    CONFLICTING_EVIDENCE,
    ENGLISH_NAME_UNAVAILABLE,
    ENGLISH_NAME_MISMATCH,
    AVAILABLE_NAME_FALLBACK,
}

data class OriginalNameSelection(
    val name: String,
    val reason: OriginalNameSelectionReason,
    val evidence: OriginalNameEvidence? = null,
)

object OriginalNamePolicy {
    /**
     * Selection never changes the stored names or artist relationships. The caller supplies names
     * already associated with the same target ID and keeps all of them available to search.
     */
    fun select(
        target: OriginalNameTarget,
        configuredName: String?,
        englishName: String?,
        fallbackName: String,
        preferEnglishOriginal: Boolean,
        evidence: List<OriginalNameEvidence>,
        assessments: List<OriginalNameAssessment> = emptyList(),
    ): OriginalNameSelection {
        val configured = configuredName?.takeIf(String::isNotBlank)
        val english = englishName?.takeIf(String::isNotBlank)

        fun useConfigured(reason: OriginalNameSelectionReason): OriginalNameSelection =
            if (configured != null) {
                OriginalNameSelection(configured, reason)
            } else {
                OriginalNameSelection(
                    fallbackName.takeIf(String::isNotBlank) ?: english.orEmpty(),
                    OriginalNameSelectionReason.AVAILABLE_NAME_FALLBACK,
                )
            }

        if (!preferEnglishOriginal) {
            return useConfigured(OriginalNameSelectionReason.CONFIGURED_LANGUAGE)
        }
        if (english == null) {
            return useConfigured(OriginalNameSelectionReason.ENGLISH_NAME_UNAVAILABLE)
        }

        val confirmed = evidence.filter { it.isConfirmedPrimaryEvidenceFor(target) }
        val automatic = assessments.filter { it.target == target }
        val known = confirmed.filter { it.language != OriginalNameLanguage.UNKNOWN }
        if (known.isEmpty() && automatic.isNotEmpty()) {
            val languages = automatic.map { it.language }.filter { it != OriginalNameLanguage.UNKNOWN }.distinct()
            val originals = automatic.map { comparableName(it.originalName) }.distinct()
            // An uncertain language is not permission to discard a conflicting original spelling.
            if (languages.size > 1 || originals.size != 1) return useConfigured(OriginalNameSelectionReason.CONFLICTING_EVIDENCE)
            if (languages.isEmpty()) return useConfigured(OriginalNameSelectionReason.ORIGINAL_UNCONFIRMED)
            if (languages.single() != OriginalNameLanguage.ENGLISH) return useConfigured(OriginalNameSelectionReason.CONFIGURED_LANGUAGE)
            if (originals.single() != comparableName(english)) return useConfigured(OriginalNameSelectionReason.ENGLISH_NAME_MISMATCH)
            return OriginalNameSelection(english, OriginalNameSelectionReason.AUTOMATIC_ENGLISH_ORIGINAL)
        }
        if (known.isEmpty()) {
            return useConfigured(OriginalNameSelectionReason.ORIGINAL_UNCONFIRMED)
        }

        // Conflicting reviewed records require another review, not whichever response arrived last.
        val languages = known.map(OriginalNameEvidence::language).distinct()
        val names = known.map { comparableName(it.originalName) }.distinct()
        if (languages.size != 1 || names.size != 1) {
            return useConfigured(OriginalNameSelectionReason.CONFLICTING_EVIDENCE)
        }
        if (languages.single() != OriginalNameLanguage.ENGLISH) {
            return useConfigured(OriginalNameSelectionReason.CONFIGURED_LANGUAGE)
        }
        if (names.single() != comparableName(english)) {
            return useConfigured(OriginalNameSelectionReason.ENGLISH_NAME_MISMATCH)
        }

        val supporting = known.minWithOrNull(
            compareBy(OriginalNameEvidence::sourceUrl, { it.reviewedOn.orEmpty() }),
        )
        return OriginalNameSelection(
            english,
            OriginalNameSelectionReason.VERIFIED_ENGLISH_ORIGINAL,
            supporting,
        )
    }

    private fun OriginalNameEvidence.isConfirmedPrimaryEvidenceFor(
        target: OriginalNameTarget,
    ): Boolean =
        target.id.isNotBlank() && this.target == target &&
            verification == OriginalNameVerification.CONFIRMED &&
            originalName.isNotBlank() && sourceUrl.isNotBlank() &&
            source in setOf(
                OriginalNameSourceKind.RIGHTS_HOLDER,
                OriginalNameSourceKind.DISTRIBUTOR_ORIGINAL_METADATA,
            )

    // Preserve deliberate casing, punctuation and script. NFC only joins equivalent Unicode forms.
    private fun comparableName(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
}

/** Main/oEmbed supplies a title candidate, not a verified language or a credited artist name. */
fun youtubeMainTitleCandidate(
    videoId: String,
    title: String,
    sourceUrl: String,
): OriginalNameEvidence = OriginalNameEvidence(
    target = OriginalNameTarget(OriginalNameKind.SONG, videoId),
    originalName = title,
    source = OriginalNameSourceKind.YOUTUBE_MAIN,
    sourceUrl = sourceUrl,
)
