package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.utils.displayName
import com.dd3boh.outertune.utils.displayTitle
import com.dd3boh.outertune.utils.matchesMetadataQuery
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class MetadataNameCandidatesTest {
    private val songTarget = OriginalNameTarget(OriginalNameKind.SONG, "track")
    private val artistTarget = OriginalNameTarget(OriginalNameKind.ARTIST, "UCartist")

    @After fun resetDisplay() = MetadataNames.publish(emptyMap())

    @Test fun `both locales retain names of the exact same identities`() {
        val ja = SongItem("track", "日本語曲名", listOf(Artist("ニルヴァーナ", "UCartist")),
            album = Album("日本語アルバム", "album"), thumbnail = "")
        val en = ja.copy(title = "English title", artists = listOf(Artist("Nirvana", "UCartist")),
            album = Album("English album", "album"))
        val names = metadataNameCandidates(listOf(ja), "ja", "queue", 1) +
            metadataNameCandidates(listOf(en), "en", "queue", 2)
        assertEquals(6, names.size)
        assertEquals(setOf("ja", "en"), names.map { it.language }.toSet())
        assertEquals(setOf("UCartist"), names.filter { it.kind == "ARTIST" }.map { it.targetId }.toSet())
        assertTrue(names.all { it.originEvidenceJson == null })
    }

    @Test fun `literal and unlinked credits never manufacture artist identities`() {
        val song = SongItem("track", "Song", listOf(Artist("One & Two", null)), thumbnail = "",
            artistCredit = ArtistCredit("One & Two", emptyList(), ArtistCreditStatus.RAW))
        assertEquals(listOf("SONG"), metadataNameCandidates(listOf(song), "en", "queue").map { it.kind })
    }

    @Test fun `same language route variants coexist and artist header takes precedence`() {
        val song = SongItem("track", "Song", listOf(Artist("ニルヴァーナ", "UCartist")), thumbnail = "")
        val header = ArtistItem("UCartist", "Nirvana", null, shuffleEndpoint = null, radioEndpoint = null)
        val fromSong = metadataNameCandidates(listOf(song), "ja", "queue", 1).single { it.kind == "ARTIST" }
        val fromHeader = metadataNameCandidates(listOf(header), "ja", "artist", 2).single()
        assertEquals(fromSong.targetId, fromHeader.targetId)
        assertNotEquals(fromSong.name, fromHeader.name)
        assertTrue(fromHeader.sourcePriority > fromSong.sourcePriority)
    }

    @Test fun `a single response retains the strongest occurrence regardless of order`() {
        val song = SongItem("track", "Song", listOf(Artist("Nirvana", "UCartist")), thumbnail = "")
        val header = ArtistItem("UCartist", "Nirvana", null, shuffleEndpoint = null, radioEndpoint = null)
        for (items in listOf(listOf(song, header), listOf(header, song))) {
            assertEquals(100, metadataNameCandidates(items, "en", "searchSummary").single { it.kind == "ARTIST" }.sourcePriority)
        }
    }

    @Test fun `display changes do not overwrite stored titles credited names or playback identity`() {
        val metadata = metadata()
        MetadataNames.publish(mapOf(songTarget to "English title", artistTarget to "Nirvana"))
        assertEquals("English title", metadata.displayTitle)
        assertEquals("Nirvana", metadata.artists.single().displayName)
        assertEquals("日本語曲名", metadata.title)
        assertEquals("ニルヴァーナ", metadata.artists.single().name)
        assertEquals("track", metadata.id)
        MetadataNames.publish(mapOf(songTarget to "日本語曲名", artistTarget to "ニルヴァーナ"))
        assertEquals("日本語曲名", metadata.displayTitle)
        assertEquals("ニルヴァーナ", metadata.artists.single().displayName)
    }

    @Test fun `queue search includes both languages regardless of displayed name`() {
        MetadataNames.publish(mapOf(songTarget to "English title", artistTarget to "Nirvana"),
            mapOf(songTarget to listOf("日本語曲名", "English title"), artistTarget to listOf("Nirvana", "ニルヴァーナ")))
        for (query in listOf("日本語", "english", "nirvan", "ニルヴァーナ")) {
            assertTrue(query, metadata().matchesMetadataQuery(query))
        }
        assertFalse(metadata().matchesMetadataQuery("unrelated"))
        assertFalse(metadata().copy(id = "other", artists = emptyList(), title = "other").matchesMetadataQuery("nirvan"))
    }

    @Test fun `local file metadata does not adopt online names even with matching ids`() {
        MetadataNames.publish(mapOf(songTarget to "English title", artistTarget to "Nirvana"),
            mapOf(songTarget to listOf("English title")))
        val local = metadata().copy(isLocal = true)
        assertEquals("日本語曲名", local.displayTitle)
        assertFalse(local.matchesMetadataQuery("English"))
    }

    private fun metadata() = MediaMetadata("track", "日本語曲名",
        listOf(MediaMetadata.Artist("localArtistRef", "ニルヴァーナ", onlineId = "UCartist")),
        duration = 300, genre = null)
}
