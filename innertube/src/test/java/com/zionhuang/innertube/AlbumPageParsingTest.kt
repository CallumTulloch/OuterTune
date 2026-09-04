package com.zionhuang.innertube

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.pages.AlbumPage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalSerializationApi::class)
class AlbumPageParsingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun response(): BrowseResponse = json.decodeFromString(
        requireNotNull(javaClass.getResource("/album-header-menu-metadata.json")).readText()
    )

    @Test
    fun `album menu artist candidates stay separate from a linkless display artist`() {
        val response = response()

        val displayArtists = AlbumPage.getArtists(response)
        val navigationCandidates = AlbumPage.getArtistNavigationCandidates(response)

        assertEquals(listOf(Artist(name = "翟锦彦、8082Audio", id = null)), displayArtists)
        assertNull(displayArtists.single().id)
        assertEquals(
            listOf("UChWKQRswWTLRXp98zmgHtdQ"),
            navigationCandidates.map { it.browseId }
        )
    }

    @Test
    fun `album song keeps typed metadata endpoints from its menu`() {
        val album = AlbumItem(
            browseId = "MPREb_NUdafp1DlA5",
            playlistId = "OLAK5uy_test",
            title = "Test Album",
            artists = null,
            thumbnail = "https://example.com/cover.jpg",
        )

        val songs = AlbumPage.getSongs(response(), album)

        assertEquals(
            listOf("TSZhKssbW2g", "xDWhuDRnevk"),
            songs.map { it.id }
        )
        assertEquals(
            listOf(
                "我也去当个天命人玩玩（《黑神话：悟空》最终预告）（It Will Fit Me Just As Well） (特别版)",
                "我也去当个天命人玩玩（《黑神话：悟空》最终预告）（It Will Fit Me Just As Well） (特别版伴奏)",
            ),
            songs.map { it.title }
        )
        songs.forEach { song ->
            assertEquals("MPREb_NUdafp1DlA5", song.metadataEndpointHints.album?.browseId)
            assertEquals(
                listOf("UChWKQRswWTLRXp98zmgHtdQ"),
                song.metadataEndpointHints.artistCandidates.map { it.browseId }
            )
            assertEquals(
                "MPTC${song.id}",
                song.metadataEndpointHints.credits?.browseId
            )
        }
    }
}
