package com.dd3boh.outertune.models

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.SongMetadataEndpointHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineNavigationTest {
    @Test
    fun `typed menu hints provide destinations without naming the artist candidate`() {
        val targets = targetSong().navigationTargets()

        assertEquals("MPREb_NUdafp1DlA5", targets.albumBrowseId)
        assertEquals(
            listOf(
                ArtistNavigationTarget(
                    browseId = "UChWKQRswWTLRXp98zmgHtdQ",
                    name = null,
                )
            ),
            targets.artists,
        )
        assertEquals("翟锦彦、8082Audio", targetSong().artists.single().name)
    }

    @Test
    fun `visible linked destinations keep their names and win duplicate hint names`() {
        val song = targetSong().copy(
            artists = listOf(Artist(name = "8082Audio", id = "UChWKQRswWTLRXp98zmgHtdQ")),
        )

        assertEquals(
            listOf(ArtistNavigationTarget("UChWKQRswWTLRXp98zmgHtdQ", "8082Audio")),
            song.navigationTargets().artists,
        )
    }

    @Test
    fun `typed album hint wins when visible and persisted album values conflict`() {
        val song = targetSong().copy(
            album = Album(name = "Stale visible album", id = "MPRE_visible"),
        )
        val persisted = metadata().copy(
            album = MediaMetadata.Album("MPRE_persisted", "Stale persisted album"),
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                albumBrowseId = "MPRE_persisted_hint",
            ),
        )

        assertEquals("MPREb_NUdafp1DlA5", song.navigationTargets(persisted).albumBrowseId)
    }

    @Test
    fun `resolved metadata is a fallback but unresolved ids never become routes`() {
        val song = targetSong().copy(metadataEndpointHints = SongMetadataEndpointHints())
        val persisted = metadata().copy(
            album = MediaMetadata.Album("MPRE_resolved", "Resolved album"),
            artists = listOf(
                MediaMetadata.Artist("LAinternal", "Unlinked credit"),
                MediaMetadata.Artist("UCresolved", "Resolved artist"),
            ),
        )

        val targets = song.navigationTargets(persisted)

        assertEquals("MPRE_resolved", targets.albumBrowseId)
        assertEquals(
            listOf(ArtistNavigationTarget("UCresolved", "Resolved artist")),
            targets.artists,
        )
    }

    @Test
    fun `local persisted metadata is never exposed as an online destination`() {
        val song = targetSong().copy(metadataEndpointHints = SongMetadataEndpointHints())

        val targets = song.navigationTargets(
            metadata().copy(
                isLocal = true,
                album = MediaMetadata.Album("LBalbum", "Local album", isLocal = true),
                artists = listOf(MediaMetadata.Artist("LAartist", "Local artist", isLocal = true)),
            )
        )

        assertNull(targets.albumBrowseId)
        assertEquals(emptyList<ArtistNavigationTarget>(), targets.artists)
    }

    @Test
    fun `database backed song keeps menu candidates independent from display credits`() {
        val metadata = metadata().copy(
            artists = listOf(MediaMetadata.Artist("LAinternal", "翟锦彦")),
            album = MediaMetadata.Album("MPRE_visible", "Visible album"),
            metadataEndpointHints = MediaMetadata.MetadataEndpointHints(
                albumBrowseId = "MPRE_typed",
                artistBrowseIds = listOf("LAinvalid", "UChWKQRswWTLRXp98zmgHtdQ"),
            ),
        )

        val targets = metadata.navigationTargets()

        assertEquals("MPRE_typed", targets.albumBrowseId)
        assertEquals(
            listOf(ArtistNavigationTarget("UChWKQRswWTLRXp98zmgHtdQ", null)),
            targets.artists,
        )
    }

    private fun targetSong() = SongItem(
        id = "TSZhKssbW2g",
        title = "It Will Fit Me Just As Well",
        artists = listOf(Artist(name = "翟锦彦、8082Audio", id = null)),
        album = null,
        duration = 238,
        thumbnail = "https://example.test/cover.jpg",
        metadataEndpointHints = SongMetadataEndpointHints(
            album = BrowseEndpoint("MPREb_NUdafp1DlA5"),
            artistCandidates = listOf(BrowseEndpoint("UChWKQRswWTLRXp98zmgHtdQ")),
        ),
    )

    private fun metadata() = MediaMetadata(
        id = "TSZhKssbW2g",
        title = "It Will Fit Me Just As Well",
        artists = emptyList(),
        duration = 238,
        genre = null,
    )
}
