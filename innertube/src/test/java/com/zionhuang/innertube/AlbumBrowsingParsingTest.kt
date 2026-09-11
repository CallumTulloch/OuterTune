@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
package com.zionhuang.innertube

import com.zionhuang.innertube.models.*
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.pages.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AlbumBrowsingParsingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private inline fun <reified T> read(name: String): T = json.decodeFromString(
        javaClass.getResource("/album-browsing/$name.json")!!.readText())

    @Test fun `album search and top result survive missing playback playlist id in both languages`() {
        for (language in listOf("ja", "en")) {
            val row = SearchPage.toYTItem(read("search-row-$language"), language) as AlbumItem
            val card = SearchSummaryPage.fromMusicCardShelfRenderer(read("search-card-$language"), language) as AlbumItem
            for (album in listOf(row, card)) {
                assertEquals("MPREb_r2SiObOBunG", album.id)
                assertEquals("THE GREATEST UNKNOWN", album.title)
                assertNull(album.playlistId)
                assertTrue(album.shareLink.endsWith("/browse/MPREb_r2SiObOBunG"))
                assertTrue(album.artists.orEmpty().any { it.name == "King Gnu" })
            }
        }
    }

    @Test fun `original album shelf retains all tracks including restricted titles`() {
        val response = read<BrowseResponse>("greatest-album-ja")
        val album = AlbumPage.getAlbum("MPREb_r2SiObOBunG", response, "ja")
        val songs = AlbumPage.getSongs(response, album, "ja")
        assertEquals("THE GREATEST UNKNOWN", album.title)
        assertEquals(2023, album.year)
        assertEquals("King Gnu", album.artists!!.single().name)
        assertEquals("OLAK5uy_kaiYU1pnGQeY_UvpyVEbsgYQVZyBgjvX8", album.playlistId)
        assertEquals(21, songs.size)
        assertEquals(13, songs.count { !it.isPlayable })
        assertEquals(21, songs.distinctBy { it.id }.size)
        assertTrue(songs.all { it.title.isNotBlank() && it.album?.id == album.id && it.thumbnail.isNotBlank() })
        assertTrue(songs.all { it.artists.isEmpty() }) // Do not invent track performers from an album header.
        assertNull(AlbumPage.continuation(response))
    }

    @Test fun `header only playlist response is not mistaken for an empty album`() {
        val response = read<BrowseResponse>("greatest-album-songs-ja")
        assertNull(AlbumPage.trackContents(response))
    }

    @Test fun `artist discography preserves year without inventing an artist`() {
        for (name in listOf("artist-album-card", "artist-single-card")) {
            val renderer = read<MusicTwoRowItemRenderer>(name)
            val item = ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer, "ja") as AlbumItem
            assertNotNull(item.year)
            assertTrue(item.artists.isNullOrEmpty())
            assertTrue(item.artistCredit?.rawText.isNullOrEmpty())
        }
    }
}
