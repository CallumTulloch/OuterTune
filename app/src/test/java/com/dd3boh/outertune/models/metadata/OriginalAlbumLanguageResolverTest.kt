package com.dd3boh.outertune.models.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class OriginalAlbumLanguageResolverTest {
    private val albumId = "test-album"
    private val timestamp = 1_789_000_000_000L
    private val englishTitles = listOf(
        "Smells Like Teen Spirit", "In Bloom", "Come As You Are", "Breed", "Lithium", "Polly",
        "Territorial Pissings", "Drain You", "Lounge Act", "Stay Away", "On A Plain",
        "Something In The Way", "Endless, Nameless",
    )

    private fun song(index: Int, name: String, album: String? = albumId): ArtTrackOriginalName {
        val id = "video${index.toString().padStart(6, '0')}"
        return ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id), name, id, album)
    }

    private val contextDetector = OriginalTextLanguageDetector { text ->
        listOf(OriginalTextLanguageScore("en", if ('\n' in text) 0.99f else 0.17f))
    }

    @Test
    fun `bundled model resolves all thirteen originals with their own album context`() = runBlocking {
        val songs = englishTitles.mapIndexed { index, name -> song(index, name) }
        val artist = ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.ARTIST, "test-artist"),
            "Nirvana", songs.first().sourceVideoId, albumId)
        val album = ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.ALBUM, albumId),
            "Nevermind", songs.first().sourceVideoId, albumId)
        val results = OriginalAlbumLanguageResolver().assess(songs + artist + album, timestamp)

        assertEquals(15, results.size)
        assertTrue(results.all { it.language == OriginalNameLanguage.ENGLISH })
        assertTrue(results.single { it.originalName == "Lounge Act" }.method.contains("album-context"))
        assertEquals(englishTitles, results.take(13).map { it.originalName })
        results.forEach {
            assertEquals(it, OriginalNameAssessmentCodec.decode(
                OriginalNameAssessmentCodec.encode(it), it.target, it.originalName,
            ))
        }
    }

    @Test
    fun `Japanese originals stay Japanese while a formal English title can be English`() = runBlocking {
        val names = listOf("夜に駆ける", "群青", "怪物", "Something In The Way", "Lemon")
        val results = OriginalAlbumLanguageResolver().assess(
            names.mapIndexed { index, name -> song(index, name) }, timestamp,
        )

        assertTrue(results.take(3).all { it.language == OriginalNameLanguage.OTHER })
        assertEquals(OriginalNameLanguage.ENGLISH, results[3].language)
        assertEquals("Lemon", results[4].originalName)
        assertEquals(names, results.map { it.originalName })
    }

    @Test
    fun `strong multiword French evidence is preserved within an English album`() = runBlocking {
        val french = song(20, "Alors on danse (Radio Edit)")
        val results = OriginalAlbumLanguageResolver().assess(
            englishTitles.mapIndexed { index, name -> song(index, name) } + french, timestamp,
        )

        val result = results.single { it.target == french.target }
        assertEquals(OriginalNameLanguage.OTHER, result.language)
        assertTrue(result.method.contains("individual-fr"))
        assertEquals(french.name, result.originalName)
    }

    @Test
    fun `Latin script and a model prior do not individually establish English`() = runBlocking {
        val names = listOf("Nirvana", "Nevermind", "YOASOBI", "Papaoutai", "Stromae", "Yoru ni Kakeru", "Kenshi Yonezu")
        val results = OriginalAlbumLanguageResolver().assess(
            names.mapIndexed { index, name -> song(index, name, null) }, timestamp,
        )

        assertTrue(results.none { it.language == OriginalNameLanguage.ENGLISH })
        assertEquals(OriginalNameLanguage.UNKNOWN, results.single { it.originalName == "Papaoutai" }.language)
    }

    @Test
    fun `album evidence needs three unique song identities and cannot count repeated routes`() = runBlocking {
        val resolver = OriginalAlbumLanguageResolver(contextDetector)
        val first = song(0, "Short")
        val second = song(1, "Other")

        assertEquals(OriginalNameLanguage.UNKNOWN,
            resolver.assess(listOf(first, first, second), timestamp).first().language)
        assertEquals(OriginalNameLanguage.ENGLISH,
            resolver.assess(listOf(first, second, song(2, "Third")), timestamp).first().language)
    }

    @Test
    fun `names from another album or with no direct album link do not inherit context`() = runBlocking {
        val source = (0..2).map { song(it, "Short $it") }
        val outside = song(3, "Outside", "other-album")
        val unlinked = ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.ARTIST, "unlinked-artist"),
            "Artist", source.first().sourceVideoId)
        val results = OriginalAlbumLanguageResolver(contextDetector).assess(source + outside + unlinked, timestamp)

        assertTrue(results.take(3).all { it.language == OriginalNameLanguage.ENGLISH })
        assertTrue(results.takeLast(2).all { it.language == OriginalNameLanguage.UNKNOWN })
    }

    @Test
    fun `conflicting originals for the same song invalidate its album context`() = runBlocking {
        val source = (0..2).map { song(it, "Short $it") }
        val conflicting = source.first().copy(name = "Different original")
        val results = OriginalAlbumLanguageResolver(contextDetector).assess(source + conflicting, timestamp)

        assertTrue(results.all { it.language == OriginalNameLanguage.UNKNOWN })
    }

    @Test
    fun `a weak or casing dependent album estimate does not fill ambiguous names`() = runBlocking {
        val detector = OriginalTextLanguageDetector { text ->
            listOf(OriginalTextLanguageScore("en", when {
                '\n' !in text -> 0.17f
                text == text.lowercase(Locale.ROOT) -> 0.94f
                else -> 0.99f
            }))
        }
        val results = OriginalAlbumLanguageResolver(detector).assess(
            (0..2).map { song(it, "Short $it") }, timestamp,
        )
        assertTrue(results.all { it.language == OriginalNameLanguage.UNKNOWN })
    }

    @Test
    fun `context fingerprint is order independent but changes with the supporting originals`() = runBlocking {
        val source = (0..2).map { song(it, "Short $it") }
        val resolver = OriginalAlbumLanguageResolver(contextDetector)
        val original = resolver.assess(source, timestamp).first()
        val reordered = resolver.assess(source.reversed(), timestamp).last()
        val changed = resolver.assess(source.dropLast(1) + source.last().copy(name = "Changed"), timestamp).first()

        assertEquals(original.inputFingerprint, reordered.inputFingerprint)
        assertNotEquals(original.inputFingerprint, changed.inputFingerprint)
        assertEquals(source.first().sourceVideoId, original.sourceVideoId)
        assertTrue(original.method.startsWith(OriginalAlbumLanguageResolver.METHOD_VERSION))
    }

    @Test
    fun `non Latin originals never inherit an English context`() = runBlocking {
        val source = (0..2).map { song(it, "Short $it") }
        val others = listOf(song(3, "米津玄師"), song(4, "밤편지"), song(5, "夜に駆ける"))
        val results = OriginalAlbumLanguageResolver(contextDetector).assess(source + others, timestamp)

        assertTrue(results.takeLast(3).all { it.language == OriginalNameLanguage.OTHER })
    }

    @Test
    fun `a song cannot claim an original from a different video`() = runBlocking {
        val invalid = song(0, "Original").copy(sourceVideoId = song(1, "Other").sourceVideoId)
        assertTrue(OriginalAlbumLanguageResolver(contextDetector).assess(listOf(invalid), timestamp).isEmpty())
    }

    @Test
    fun `numbers symbols and punctuation have no language even in an English context`() = runBlocking {
        val source = (0..2).map { song(it, "Short $it") }
        val neutral = listOf(song(3, "123"), song(4, "!!!"), song(5, "🎵"))
        val resolver = OriginalAlbumLanguageResolver(contextDetector)
        val results = resolver.assess(source + neutral, timestamp)

        assertTrue(results.takeLast(3).all { it.language == OriginalNameLanguage.UNKNOWN })
        val undersized = resolver.assess(source.take(1) + neutral, timestamp)
        assertTrue(undersized.all { it.language == OriginalNameLanguage.UNKNOWN })
    }

    @Test
    fun `bundled classifier copies mutable rank entries before the next evaluation`() = runBlocking {
        val first = LangidOriginalTextLanguageDetector.identify("Something In The Way")
        val snapshot = first.map { it.copy() }
        val next = LangidOriginalTextLanguageDetector.identify("Alors on danse (Radio Edit)")

        assertEquals(97, first.size)
        assertEquals(snapshot, first)
        assertEquals("en", first.first().language)
        assertEquals("fr", next.first().language)
        assertFalse(first === next)
    }
}
