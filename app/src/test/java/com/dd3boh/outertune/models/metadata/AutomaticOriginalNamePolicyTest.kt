package com.dd3boh.outertune.models.metadata

import org.junit.Assert.*
import org.junit.Test

class AutomaticOriginalNamePolicyTest {
    private val video = "abcdefghijk"
    private val song = OriginalNameTarget(OriginalNameKind.SONG, video)
    private val english = "Original English Title"

    @Test fun `an automatically assessed English original is selected without a nationality rule`() {
        for (target in listOf(song, OriginalNameTarget(OriginalNameKind.ARTIST, "UC-japanese-artist"),
            OriginalNameTarget(OriginalNameKind.ALBUM, "MPRE-japanese-album"))) {
            val result = select(listOf(assessment(target)), target = target)
            assertEquals(english, result.name)
            assertEquals(OriginalNameSelectionReason.AUTOMATIC_ENGLISH_ORIGINAL, result.reason)
            assertNull(result.evidence) // An automatic result is not presented as a reviewed source.
        }
    }

    @Test fun `disabled preference preserves configured name even with an automatic English result`() {
        assertEquals("設定言語の表記", select(listOf(assessment()), enabled = false).name)
    }

    @Test fun `non-English and unknown automatic outcomes preserve configured name`() {
        for (language in listOf(OriginalNameLanguage.OTHER, OriginalNameLanguage.UNKNOWN)) {
            assertEquals("設定言語の表記", select(listOf(assessment().copy(language = language))).name)
        }
    }

    @Test fun `automatic results cannot cross IDs or kinds or synthesize a missing English name`() {
        val wrongTargets = listOf(song.copy(id = "12345678901"), OriginalNameTarget(OriginalNameKind.ARTIST, song.id))
        for (target in wrongTargets) assertEquals("設定言語の表記", select(listOf(assessment(target))).name)
        assertEquals(OriginalNameSelectionReason.ENGLISH_NAME_UNAVAILABLE, select(listOf(assessment()), candidate = null).reason)
        assertEquals(OriginalNameSelectionReason.ENGLISH_NAME_MISMATCH, select(listOf(assessment()), candidate = "Another title").reason)
    }

    @Test fun `conflicting automatic names or languages keep configured name independent of arrival order`() {
        val first = assessment()
        for (second in listOf(first.copy(originalName = "Another original"), first.copy(language = OriginalNameLanguage.OTHER),
            first.copy(originalName = "Another original", language = OriginalNameLanguage.UNKNOWN))) {
            val forwards = select(listOf(first, second))
            assertEquals(OriginalNameSelectionReason.CONFLICTING_EVIDENCE, forwards.reason)
            assertEquals(forwards, select(listOf(second, first)))
        }
    }

    @Test fun `unknown language for the same spelling does not contradict English evidence`() {
        val first = assessment()
        val result = select(listOf(first, first.copy(language = OriginalNameLanguage.UNKNOWN)))
        assertEquals(OriginalNameSelectionReason.AUTOMATIC_ENGLISH_ORIGINAL, result.reason)
    }

    private fun assessment(target: OriginalNameTarget = song) = OriginalNameAssessment(target, english, video,
        "https://www.youtube.com/watch?v=$video", OriginalNameLanguage.ENGLISH, 0.99f,
        "album-language-test", "same-album-context", 1_789_000_000_000L)

    private fun select(assessments: List<OriginalNameAssessment>, target: OriginalNameTarget = song,
        enabled: Boolean = true, candidate: String? = english) = OriginalNamePolicy.select(target,
        configuredName = "設定言語の表記", englishName = candidate, fallbackName = "Fallback",
        preferEnglishOriginal = enabled, evidence = emptyList(), assessments = assessments)
}
