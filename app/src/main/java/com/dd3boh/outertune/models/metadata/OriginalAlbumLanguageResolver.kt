package com.dd3boh.outertune.models.metadata

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Assess distributor originals, using a directly linked album to disambiguate short names.
 * This is an automatic language estimate, not a human review or a nationality/audio-language rule.
 * Callers establish every name's identity and album link before passing candidates here.
 */
class OriginalAlbumLanguageResolver(
    private val detector: OriginalTextLanguageDetector = LangidOriginalTextLanguageDetector,
) {
    suspend fun assess(
        candidates: List<ArtTrackOriginalName>,
        evaluatedAt: Long,
    ): List<OriginalNameAssessment> {
        require(evaluatedAt > 0L)
        val valid = candidates.filter {
            it.target.id.isNotBlank() && it.target.id.none(Char::isWhitespace) && it.name.isNotBlank() &&
                videoIdPattern.matches(it.sourceVideoId) &&
                (it.target.kind != OriginalNameKind.SONG || it.target.id == it.sourceVideoId)
        }.distinct()
        // Within one assessment, repeated performer names need only one model evaluation.
        val observations = mutableMapOf<String, List<OriginalTextLanguageScore>>()
        suspend fun identify(text: String): List<OriginalTextLanguageScore> =
            observations[text] ?: detector.identify(text)
                .filter { it.language.isNotBlank() && it.confidence.isFinite() && it.confidence in 0f..1f }
                .also { observations[text] = it }
        suspend fun evidence(text: String): TextEvidence = TextEvidence(
            identify(text), identify(text.lowercase(Locale.ROOT)),
        )

        val contexts = mutableMapOf<String, AlbumContext>()
        valid.filter { it.target.kind == OriginalNameKind.SONG && !it.albumId.isNullOrBlank() && hasLetters(it.name) }
            .groupBy { it.albumId!! }.forEach { (albumId, songs) ->
                val bySong = songs.groupBy { it.target.id }
                // Repeated routes must not inflate the sample or hide conflicting originals.
                if (bySong.size < MIN_CONTEXT_TRACKS ||
                    bySong.values.any { values -> values.map { comparable(it.name) }.distinct().size != 1 }) return@forEach
                val tracks = bySong.values.map { values -> values.minBy { it.name } }.sortedBy { it.target.id }
                // A majority-English aggregate cannot establish the language of ambiguous titles
                // or artist names on an album that also contains clearly non-English originals.
                if (tracks.any { hasNonLatinLetters(it.name) }) return@forEach
                for (track in tracks) {
                    if (hasMultipleWords(track.name) && evidence(track.name).strongForeignLanguage() != null) return@forEach
                }
                val input = tracks.joinToString("\n") { it.name }
                val contextEvidence = evidence(input)
                val confidence = minOf(contextEvidence.original.english(), contextEvidence.lowercase.english())
                contexts[albumId] = AlbumContext(
                    confidence = confidence,
                    input = tracks.joinToString("\n") { encoded(it.target.id) + encoded(it.name) },
                    trackCount = tracks.size,
                )
            }

        return valid.map { candidate ->
            val context = candidate.albumId?.let(contexts::get)
            val result = if (!hasLetters(candidate.name)) {
                Decision(OriginalNameLanguage.UNKNOWN, 0f, "no-language-letters")
            } else if (hasNonLatinLetters(candidate.name)) {
                Decision(OriginalNameLanguage.OTHER, 1f, "non-latin-script")
            } else {
                val text = evidence(candidate.name)
                val foreign = text.strongForeignLanguage()
                val english = minOf(text.original.english(), text.lowercase.english())
                when {
                    // Both case variants must agree: isolated high scores misclassify short titles.
                    hasMultipleWords(candidate.name) && foreign != null ->
                        Decision(OriginalNameLanguage.OTHER, foreign.confidence, "individual-${foreign.language}")
                    english >= INDIVIDUAL_CONFIDENCE ->
                        Decision(OriginalNameLanguage.ENGLISH, english, "individual-english")
                    context != null && context.confidence >= CONTEXT_CONFIDENCE ->
                        Decision(OriginalNameLanguage.ENGLISH, context.confidence,
                            "album-context:${candidate.albumId}:tracks=${context.trackCount}")
                    else -> Decision(OriginalNameLanguage.UNKNOWN, 0f, "undetermined")
                }
            }
            val method = "$METHOD_VERSION/${result.method}"
            OriginalNameAssessment(
                target = candidate.target,
                originalName = candidate.name,
                sourceVideoId = candidate.sourceVideoId,
                sourceUrl = "https://www.youtube.com/watch?v=${candidate.sourceVideoId}",
                language = result.language,
                confidence = result.confidence,
                method = method,
                inputFingerprint = fingerprint(
                    listOf(method, candidate.target.kind.name, candidate.target.id, candidate.name,
                        candidate.sourceVideoId, candidate.albumId.orEmpty(), context?.input.orEmpty()),
                ),
                evaluatedAt = evaluatedAt,
            )
        }
    }

    private data class AlbumContext(val confidence: Float, val input: String, val trackCount: Int)
    private data class Decision(val language: OriginalNameLanguage, val confidence: Float, val method: String)
    private data class TextEvidence(
        val original: List<OriginalTextLanguageScore>,
        val lowercase: List<OriginalTextLanguageScore>,
    ) {
        fun strongForeignLanguage(): OriginalTextLanguageScore? {
            val first = original.maxByOrNull(OriginalTextLanguageScore::confidence) ?: return null
            val second = lowercase.maxByOrNull(OriginalTextLanguageScore::confidence) ?: return null
            return first.takeIf {
                it.language !in setOf("en", "und") && it.language == second.language &&
                    it.confidence >= INDIVIDUAL_CONFIDENCE && second.confidence >= INDIVIDUAL_CONFIDENCE
            }?.copy(confidence = minOf(first.confidence, second.confidence))
        }
    }

    companion object {
        const val METHOD_VERSION = "langid-java-1.0.0/album-language-v2"
        private const val INDIVIDUAL_CONFIDENCE = 0.90f
        private const val CONTEXT_CONFIDENCE = 0.95f
        private const val MIN_CONTEXT_TRACKS = 3
        private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
        private val wordPattern = Regex("\\p{L}[\\p{L}\\p{M}'’]*")

        private fun List<OriginalTextLanguageScore>.english(): Float =
            filter { it.language == "en" && it.confidence.isFinite() && it.confidence in 0f..1f }
                .maxOfOrNull(OriginalTextLanguageScore::confidence) ?: 0f

        private fun hasNonLatinLetters(text: String): Boolean = text.codePoints().anyMatch {
            Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
        }

        private fun hasLetters(text: String): Boolean = text.codePoints().anyMatch(Character::isLetter)
        private fun hasMultipleWords(text: String): Boolean = wordPattern.findAll(text).take(2).count() >= 2

        private fun comparable(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
        private fun encoded(text: String): String = "${text.length}:$text"
        private fun fingerprint(parts: List<String>): String {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(parts.joinToString("") { encoded(it) }.toByteArray(StandardCharsets.UTF_8))
            val digits = "0123456789abcdef"
            return buildString(hash.size * 2) {
                hash.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(digits[value ushr 4])
                    append(digits[value and 15])
                }
            }
        }
    }
}
