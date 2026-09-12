package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.utils.displayTitle
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MetadataDisplaySelectionTest {
    private val target = OriginalNameTarget(OriginalNameKind.SONG, "track")
    private fun name(language: String, text: String, source: String = "detail", priority: Int = 100) =
        MetadataNameEntity("SONG", "track", language, text, source, priority, observedAt = 1)

    @After fun resetDisplay() = MetadataNames.publish(emptyMap())

    @Test fun `missing configured cache never replaces the source Japanese title with unconfirmed English`() {
        val raw = MediaMetadata("track", "日本語の曲名", emptyList(), duration = 100, genre = null)
        for (enabled in listOf(false, true)) {
            val selected = selectMetadataDisplayName(target, listOf(name("en", "Nihongo no Kyokumei")), "ja", enabled)
            assertNull(selected)
            MetadataNames.publish(selected?.let { mapOf(target to it) }.orEmpty())
            assertEquals("日本語の曲名", raw.displayTitle)
            assertEquals("track", raw.id)
        }
    }

    @Test fun `configured language wins over higher priority English and prior language caches`() {
        val names = listOf(name("en", "English"), name("ja", "日本語", priority = 20), name("fr", "Français"))
        assertEquals("日本語", selectMetadataDisplayName(target, names, "ja", false))
        assertEquals("Français", selectMetadataDisplayName(target, names, "fr", false))
        assertEquals("English", selectMetadataDisplayName(target, names, "en", false))
        assertNull(selectMetadataDisplayName(target, names, "de", false))
    }

    @Test fun `supported English original can display before configured acquisition only while enabled`() {
        val candidate = name("en", "Original Title")
        val assessment = OriginalNameAssessment(target, candidate.name, "abcdefghijk",
            "https://www.youtube.com/watch?v=abcdefghijk", OriginalNameLanguage.ENGLISH, 0.99f,
            "album-language-test", "context", 1)
        assertEquals(candidate.name, selectMetadataDisplayName(target, listOf(candidate), "ja", true, listOf(assessment)))
        assertNull(selectMetadataDisplayName(target, listOf(candidate), "ja", false, listOf(assessment)))
    }

    @Test fun `original-only cache is not a configured language and unrelated targets cannot supply a name`() {
        assertNull(selectMetadataDisplayName(target, listOf(name("und", "Unassessed original")), "ja", false))
        assertNull(selectMetadataDisplayName(target, listOf(name("ja", "別の曲").copy(targetId = "other")), "ja", false))
    }

    @Test fun `manual naming remains independent of language preference`() {
        val names = listOf(name("en", "English"), name("ja", "手動", "manual", priority = 0))
        for (enabled in listOf(false, true)) assertEquals("手動", selectMetadataDisplayName(target, names, "fr", enabled))
    }

    @Test fun `legacy priority hundred artist and album cards cannot overtake an earlier detail name`() {
        val cardSources = listOf("library", "libraryContinuation", "libraryRecentActivity", "home",
            "homeContinuation", "searchSuggestions", "searchSummary", "search", "searchContinuation",
            "artist", "artistItems", "artistItemsContinuation", "album", "browse", "related")
        for (kind in listOf(OriginalNameKind.ARTIST, OriginalNameKind.ALBUM)) {
            val namedTarget = OriginalNameTarget(kind, "identity")
            val japanese = MetadataNameEntity(kind.name, namedTarget.id, "ja", "日本語の詳細名", "detail", 100, 1)
            val english = japanese.copy(language = "en", name = "English detail")
            for (source in cardSources) {
                // These rows already exist in installations made before the priority fix.
                val laterCard = japanese.copy(name = "English list spelling", source = source, observedAt = 86_400_001)
                for (enabled in listOf(false, true)) {
                    assertEquals("$kind/$source/enabled=$enabled", japanese.name,
                        selectMetadataDisplayName(namedTarget, listOf(japanese, english, laterCard), "ja", enabled))
                }
            }
        }
    }

    @Test fun `an English list response cannot replace the selected Japanese detail`() {
        val namedTarget = OriginalNameTarget(OriginalNameKind.ARTIST, "UC-artist")
        val japanese = MetadataNameEntity("ARTIST", namedTarget.id, "ja", "椎名林檎", "detail", 100, 1)
        val english = japanese.copy(language = "en", name = "Sheena Ringo")
        val englishCard = english.copy(name = "Ringo Sheena", source = "library", observedAt = 86_400_001)
        for (enabled in listOf(false, true)) {
            assertEquals(japanese.name,
                selectMetadataDisplayName(namedTarget, listOf(japanese, english, englishCard), "ja", enabled))
        }
        assertEquals(english.name,
            selectMetadataDisplayName(namedTarget, listOf(japanese, english, englishCard), "en", false))
    }

    @Test fun `a newer authoritative detail can still replace an earlier detail spelling`() {
        val namedTarget = OriginalNameTarget(OriginalNameKind.ARTIST, "UC-artist")
        val previous = MetadataNameEntity("ARTIST", namedTarget.id, "ja", "以前の詳細名", "detail", 100, 1)
        val current = previous.copy(name = "現在の詳細名", observedAt = 2)
        assertEquals(current.name, selectMetadataDisplayName(namedTarget, listOf(previous, current), "ja", false))
    }
}
