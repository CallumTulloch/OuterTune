package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.utils.displayTitle
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.runBlocking
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

    @Test fun `a supported original selects the observed album alias despite a higher priority video suffix`() {
        for ((title, suffix) in listOf("In Bloom" to "Official Music Video", "Breed" to "Audio")) {
            val names = listOf(name("ja", "設定言語の曲名"), name("en", "$title ($suffix)"),
                name("en", title, "album", priority = 50))
            for (orderedNames in listOf(names, names.reversed())) {
                assertEquals(title, selectMetadataDisplayName(target, orderedNames, "ja", true,
                    listOf(originalAssessment(title))))
                assertEquals("設定言語の曲名", selectMetadataDisplayName(target, orderedNames, "ja", false,
                    listOf(originalAssessment(title))))
                assertEquals("$title ($suffix)", selectMetadataDisplayName(target, orderedNames, "en", false,
                    listOf(originalAssessment(title))))
            }
        }
    }

    @Test fun `an album alias without an assessment does not change detail priority or infer an original`() {
        val names = listOf(name("ja", "イン・ブルーム"), name("en", "In Bloom (Official Music Video)"),
            name("en", "In Bloom", "album", priority = 50))
        for (enabled in listOf(false, true)) {
            assertEquals("イン・ブルーム", selectMetadataDisplayName(target, names, "ja", enabled))
            assertEquals("In Bloom (Official Music Video)", selectMetadataDisplayName(target, names, "en", enabled))
        }
    }

    @Test fun `matching one observed alias cannot cherry pick a conflicting original name or language`() {
        val names = listOf(name("ja", "設定言語の曲名"), name("en", "In Bloom (Official Music Video)"),
            name("en", "In Bloom", "album", priority = 50))
        val english = originalAssessment("In Bloom")
        val conflicts = listOf(english.copy(originalName = "Another Original"),
            english.copy(originalName = "Another Original", language = OriginalNameLanguage.UNKNOWN),
            english.copy(language = OriginalNameLanguage.OTHER))
        for (other in conflicts) {
            for (assessments in listOf(listOf(english, other), listOf(other, english))) {
                assertEquals("設定言語の曲名", selectMetadataDisplayName(target, names, "ja", true, assessments))
            }
        }
    }

    @Test fun `manual naming overrides a supported matching album original`() {
        val names = listOf(name("ja", "手動の曲名", "manual", priority = 0),
            name("en", "Breed (Audio)"), name("en", "Breed", "album", priority = 50))
        for (language in listOf("ja", "en")) for (enabled in listOf(false, true)) {
            assertEquals("手動の曲名", selectMetadataDisplayName(target, names, language, enabled,
                listOf(originalAssessment("Breed"))))
        }
    }

    @Test fun `alias matching permits NFC and trim only and never synthesizes an unobserved original`() {
        val configured = name("ja", "設定言語の曲名")
        val detail = name("en", "Café (Audio)")
        val observed = " Cafe\u0301 "
        val matching = name("en", observed, "album", priority = 50)
        val assessment = originalAssessment("Café")
        assertEquals(observed, selectMetadataDisplayName(target, listOf(configured, detail, matching), "ja", true,
            listOf(assessment)))
        for (names in listOf(listOf(configured, detail),
            listOf(configured, detail, matching.copy(name = "café")),
            listOf(configured, detail, matching.copy(targetId = "another-song")))) {
            assertEquals(configured.name, selectMetadataDisplayName(target, names, "ja", true, listOf(assessment)))
        }
    }

    @Test fun `a matching alias with unknown or non English original remains in the configured language`() {
        val names = listOf(name("ja", "設定言語の曲名"), name("en", "Breed (Audio)"),
            name("en", "Breed", "album", priority = 50))
        for (language in listOf(OriginalNameLanguage.UNKNOWN, OriginalNameLanguage.OTHER)) {
            assertEquals("設定言語の曲名", selectMetadataDisplayName(target, names, "ja", true,
                listOf(originalAssessment("Breed").copy(language = language))))
        }
    }

    @Test fun `verified playlist relation displays the observed Eleanor original without stripping its edition`() = runBlocking {
        val fixture = eleanorOriginalFixture()
        val directAssessment = OriginalAlbumLanguageResolver().assess(listOf(fixture.original), 1).single()
        assertEquals(OriginalNameLanguage.ENGLISH, directAssessment.language)
        val direct = fixture.direct.copy(originEvidenceJson = encodeOriginalAssessment(
            fixture.original, directAssessment, listOf(fixture.direct)))
        val rows = fixture.names + direct
        val assessments = playlistAssociatedOriginalAssessments(rows)
        assertEquals(1, assessments.size)
        assertEquals(fixture.original.name, assessments.single().originalName)
        assertEquals("エリナー・リグビー", selectMetadataDisplayName(fixture.target, rows, "ja", false, assessments))
        assertEquals("Eleanor Rigby (Remastered 2015)",
            selectMetadataDisplayName(fixture.target, rows, "ja", true, assessments))
        // The same directly observed source original is usable on its own video ID too.
        assertEquals(fixture.original.name,
            selectMetadataDisplayName(fixture.original.target, listOf(direct), "ja", true, listOf(directAssessment)))
        assertNull(selectMetadataDisplayName(fixture.original.target, listOf(direct), "ja", false, listOf(directAssessment)))
        val publication = prepareOriginalPublications(rows, emptyList(), 1)!!.single { it.targetId == fixture.target.id }
        assertEquals(fixture.original.name, publication.englishName)
        assertTrue(publication.evidenceJson.contains("Eleanor Rigby (Remastered 2015)"))
        val manual = fixture.names.first().copy(name = "手動の曲名", source = "manual", sourcePriority = 0)
        for (enabled in listOf(false, true)) {
            assertEquals(manual.name, selectMetadataDisplayName(fixture.target, rows + manual, "ja", enabled, assessments))
        }
    }

    @Test fun `a Main original fallback requires observed proof and a completed English decision`() = runBlocking {
        val fixture = eleanorOriginalFixture()
        val english = OriginalAlbumLanguageResolver().assess(listOf(fixture.original), 1).single()
        for (language in listOf(OriginalNameLanguage.UNKNOWN, OriginalNameLanguage.OTHER)) {
            val direct = fixture.direct.copy(originEvidenceJson = encodeOriginalAssessment(
                fixture.original, english.copy(language = language), listOf(fixture.direct)))
            val rows = fixture.names + direct
            val assessments = playlistAssociatedOriginalAssessments(rows)
            assertEquals(language, assessments.single().language)
            assertEquals("エリナー・リグビー", selectMetadataDisplayName(fixture.target, rows, "ja", true, assessments))
        }
        val direct = fixture.direct.copy(originEvidenceJson = encodeOriginalAssessment(
            fixture.original, english, listOf(fixture.direct)))
        val current = playlistAssociatedOriginalAssessments(fixture.names + direct)
        val withoutObservedOriginal = fixture.names.filter { it.language != "und" }
        val reference = fixture.names.single { it.language == "und" }
        val forged = reference.copy(source = "arbitrary-observation", originEvidenceJson = "{}")
        val mismatched = reference.copy(targetId = "wrong000000")
        for (rows in listOf(withoutObservedOriginal, withoutObservedOriginal + forged,
            withoutObservedOriginal + mismatched, withoutObservedOriginal + reference.copy(originEvidenceJson = "{}"))) {
            assertEquals("エリナー・リグビー", selectMetadataDisplayName(fixture.target, rows, "ja", true, current))
        }
        assertEquals("エリナー・リグビー",
            selectMetadataDisplayName(fixture.target, fixture.names, "ja", true, emptyList()))
    }

    private data class EleanorOriginalFixture(
        val target: OriginalNameTarget,
        val original: ArtTrackOriginalName,
        val direct: MetadataNameEntity,
        val names: List<MetadataNameEntity>,
    )

    private fun eleanorOriginalFixture(): EleanorOriginalFixture {
        val sourceId = "6gluNoLVKiQ"
        val targetId = "HuS5NuXRb5Y"
        val albumId = "MPREb_mnYrD806fc4"
        val playlistId = "OLAK5uy_mc399CKoHidFSZHLCydnI43dS3O9hEojA"
        val original = ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, sourceId),
            "Eleanor Rigby (Remastered 2015)", sourceId, albumId)
        val sourceMusic = SongItem(sourceId, "Eleanor Rigby", emptyList(), Album("1", albumId), thumbnail = "")
        val targetMusic = sourceMusic.copy(id = targetId)
        val reference = requireNotNull(playlistSongReference(
            PlaylistSongReference(playlistId, "3CF5D4F99A0F04E8", sourceId, targetId), original,
            targetMusic, playlistId, sourceMusic = sourceMusic))
        val direct = MetadataNameEntity("SONG", sourceId, "und", original.name,
            ORIGINAL_NAME_SOURCE_PREFIX + sourceId, 10, 1, ArtTrackOriginalNameCodec.encode(original))
        val configured = MetadataNameEntity("SONG", targetId, "ja", "エリナー・リグビー", "detail", 100, 1)
        val english = configured.copy(language = "en", name = "Eleanor Rigby")
        return EleanorOriginalFixture(OriginalNameTarget(OriginalNameKind.SONG, targetId), original,
            direct, listOf(configured, english, reference.toMetadataName(1)))
    }

    private fun originalAssessment(title: String) = OriginalNameAssessment(target, title, "abcdefghijk",
        "https://www.youtube.com/watch?v=abcdefghijk", OriginalNameLanguage.ENGLISH, 0.99f,
        OriginalAlbumLanguageResolver.METHOD_VERSION + "/individual-english", "current-inputs", 1)
}
