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
}
