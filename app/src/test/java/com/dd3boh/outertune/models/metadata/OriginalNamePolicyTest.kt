package com.dd3boh.outertune.models.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalNamePolicyTest {
    private val song = OriginalNameTarget(OriginalNameKind.SONG, "test-song")

    private fun confirmed(
        target: OriginalNameTarget = song,
        name: String = "Original Title",
        language: OriginalNameLanguage = OriginalNameLanguage.ENGLISH,
    ) = OriginalNameEvidence(
        target = target,
        originalName = name,
        source = OriginalNameSourceKind.RIGHTS_HOLDER,
        sourceUrl = "https://example.com/official-release",
        language = language,
        verification = OriginalNameVerification.CONFIRMED,
        reviewedOn = "2026-09-07",
    )

    private fun select(
        evidence: List<OriginalNameEvidence>,
        target: OriginalNameTarget = song,
        english: String? = "Original Title",
        configured: String? = "設定言語の曲名",
        enabled: Boolean = true,
    ) = OriginalNamePolicy.select(
        target = target,
        configuredName = configured,
        englishName = english,
        fallbackName = "Previously supplied name",
        preferEnglishOriginal = enabled,
        evidence = evidence,
    )

    @Test
    fun `switch off keeps configured language even with verified English evidence`() {
        val result = select(listOf(confirmed()), enabled = false)
        assertEquals("設定言語の曲名", result.name)
        assertEquals(OriginalNameSelectionReason.CONFIGURED_LANGUAGE, result.reason)
    }

    @Test
    fun `verified formal English name is used for a Japanese artist without nationality inference`() {
        val artist = OriginalNameTarget(OriginalNameKind.ARTIST, "test-japanese-artist")
        val result = select(
            listOf(confirmed(artist, "Official English Name")),
            target = artist,
            english = "Official English Name",
            configured = "日本語での別名",
        )
        assertEquals("Official English Name", result.name)
        assertEquals(OriginalNameSelectionReason.VERIFIED_ENGLISH_ORIGINAL, result.reason)
    }

    @Test
    fun `Latin spelling and Main equality do not prove an English original`() {
        val candidate = youtubeMainTitleCandidate(
            song.id, "Original Title", "https://www.youtube.com/watch?v=${song.id}",
        )
        assertEquals(OriginalNameLanguage.UNKNOWN, candidate.language)
        assertEquals(OriginalNameVerification.UNVERIFIED, candidate.verification)
        assertEquals("設定言語の曲名", select(listOf(candidate)).name)
        assertEquals("設定言語の曲名", select(emptyList()).name)
    }

    @Test
    fun `even mislabeled Main or catalog evidence cannot act as primary verification`() {
        for (source in listOf(OriginalNameSourceKind.YOUTUBE_MAIN, OriginalNameSourceKind.METADATA_CATALOG)) {
            val result = select(listOf(confirmed().copy(source = source)))
            assertEquals(OriginalNameSelectionReason.ORIGINAL_UNCONFIRMED, result.reason)
        }
    }

    @Test
    fun `non English Latin name keeps configured language`() {
        val result = select(
            listOf(confirmed(name = "La vie en rose", language = OriginalNameLanguage.OTHER)),
            english = "La vie en rose",
            configured = "ばら色の人生",
        )
        assertEquals("ばら色の人生", result.name)
        assertEquals(OriginalNameSelectionReason.CONFIGURED_LANGUAGE, result.reason)
    }

    @Test
    fun `Japanese original is not replaced by an English transliteration`() {
        val result = select(
            listOf(confirmed(name = "日本語の原名", language = OriginalNameLanguage.OTHER)),
            english = "Nihongo no Genmei",
        )
        assertEquals("設定言語の曲名", result.name)
    }

    @Test
    fun `evidence does not cross IDs or entity kinds`() {
        for (other in listOf(
            OriginalNameTarget(OriginalNameKind.SONG, "another-song"),
            OriginalNameTarget(OriginalNameKind.ARTIST, song.id),
            OriginalNameTarget(OriginalNameKind.ALBUM, song.id),
        )) {
            assertEquals("設定言語の曲名", select(listOf(confirmed(other))).name)
        }
    }

    @Test
    fun `missing English acquisition is not synthesized from reviewed evidence`() {
        val result = select(listOf(confirmed()), english = null)
        assertEquals("設定言語の曲名", result.name)
        assertEquals(OriginalNameSelectionReason.ENGLISH_NAME_UNAVAILABLE, result.reason)
    }

    @Test
    fun `different English candidate keeps configured name`() {
        val result = select(listOf(confirmed()), english = "Another Version (Live)")
        assertEquals("設定言語の曲名", result.name)
        assertEquals(OriginalNameSelectionReason.ENGLISH_NAME_MISMATCH, result.reason)
    }

    @Test
    fun `conflicting reviews cannot depend on arrival order`() {
        val first = confirmed()
        val alternatives = listOf(
            confirmed(name = "Different original title"),
            confirmed(language = OriginalNameLanguage.OTHER),
        )
        for (alternative in alternatives) {
            val forward = select(listOf(first, alternative))
            val backward = select(listOf(alternative, first))
            assertEquals(forward, backward)
            assertEquals(OriginalNameSelectionReason.CONFLICTING_EVIDENCE, forward.reason)
        }
    }

    @Test
    fun `unverified or rejected claim does not overwrite a confirmed review`() {
        for (status in listOf(OriginalNameVerification.UNVERIFIED, OriginalNameVerification.REJECTED)) {
            val result = select(listOf(confirmed(), confirmed(name = "Different").copy(verification = status)))
            assertEquals(OriginalNameSelectionReason.VERIFIED_ENGLISH_ORIGINAL, result.reason)
        }
    }

    @Test
    fun `equivalent Unicode is accepted but deliberate casing is preserved`() {
        val composed = "Caf\u00e9 Original"
        val decomposed = "Caf\u0065\u0301 Original"
        assertEquals(
            OriginalNameSelectionReason.VERIFIED_ENGLISH_ORIGINAL,
            select(listOf(confirmed(name = composed)), english = decomposed).reason,
        )
        assertEquals(
            OriginalNameSelectionReason.ENGLISH_NAME_MISMATCH,
            select(listOf(confirmed(name = "ORIGINAL TITLE"))).reason,
        )
    }

    @Test
    fun `blank source or target cannot verify a name`() {
        assertEquals(
            OriginalNameSelectionReason.ORIGINAL_UNCONFIRMED,
            select(listOf(confirmed().copy(sourceUrl = " "))).reason,
        )
        val emptyTarget = song.copy(id = "")
        assertEquals(
            OriginalNameSelectionReason.ORIGINAL_UNCONFIRMED,
            select(listOf(confirmed(emptyTarget)), target = emptyTarget).reason,
        )
    }

    @Test
    fun `missing configured name preserves a supplied fallback without claiming verification`() {
        val result = select(emptyList(), configured = null)
        assertEquals("Previously supplied name", result.name)
        assertEquals(OriginalNameSelectionReason.AVAILABLE_NAME_FALLBACK, result.reason)
    }

    @Test
    fun `reviewed source is replaceable and does not extend a recording review to its MV`() = runBlocking {
        val replacement = OriginalNameEvidenceSource { target -> listOf(confirmed()).filter { it.target == target } }
        assertEquals(listOf(confirmed()), replacement.findEvidence(song))
        assertTrue(replacement.findEvidence(song.copy(id = "ljUtuoFt-8c")).isEmpty())

        val audio = OriginalNameTarget(OriginalNameKind.SONG, "ljUtuoFt-8c")
        val reviewed = OriginalNameEvidenceSource { target ->
            listOf(confirmed().copy(target = audio, originalName = "Smells Like Teen Spirit")).filter { it.target == target }
        }
        val audioResult = select(
            reviewed.findEvidence(audio),
            target = audio,
            english = "Smells Like Teen Spirit",
        )
        assertEquals(OriginalNameSelectionReason.VERIFIED_ENGLISH_ORIGINAL, audioResult.reason)
        assertTrue(reviewed.findEvidence(audio.copy(id = "hTWKbfoikeg")).isEmpty())
    }
}
