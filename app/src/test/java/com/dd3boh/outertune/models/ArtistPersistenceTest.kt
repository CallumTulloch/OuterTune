package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.initialArtistBrowseId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistPersistenceTest {
    @Test
    fun `canonical online ids initialize browse ids`() {
        assertEquals("UCcanonical", initialArtistBrowseId("UCcanonical", isLocal = false))
        assertEquals(
            "UCcanonical",
            ArtistEntity(id = "UCcanonical", name = "Artist").browseId,
        )
        assertEquals(
            "FEmusic_library_privately_owned_artist_123",
            initialArtistBrowseId(
                "FEmusic_library_privately_owned_artist_123",
                isLocal = false,
            ),
        )
    }

    @Test
    fun `generated and local ids do not initialize browse ids`() {
        assertNull(initialArtistBrowseId("LAplaceholder", isLocal = false))
        assertNull(initialArtistBrowseId("UClooksRemoteButIsLocal", isLocal = true))
        assertNull(initialArtistBrowseId("unknown", isLocal = false))
    }

    @Test
    fun `invalid persisted browse id is not exposed to navigation`() {
        val artist = ArtistEntity(
            id = "LAinternal",
            name = "Unresolved artist",
            isLocal = false,
            browseId = "LAincorrectlyPersisted",
        )

        assertNull(artist.navigationId)
    }

    @Test
    fun `database restore exposes browse id for an online artist`() {
        val metadata = persistedSong(
            ArtistEntity(
                id = "LAinternal",
                name = "Canonical artist",
                isLocal = false,
                browseId = "UCcanonical",
            )
        ).toMediaMetadata()

        assertEquals("UCcanonical", metadata.artists.single().id)
    }

    @Test
    fun `database restore hides generated id for unresolved online artist`() {
        val metadata = persistedSong(
            ArtistEntity(
                id = "LAplaceholder",
                name = "Unresolved artist",
                isLocal = false,
                browseId = null,
            )
        ).toMediaMetadata()

        assertNull(metadata.artists.single().id)
    }

    @Test
    fun `database restore keeps internal id for local artist navigation`() {
        val metadata = persistedSong(
            ArtistEntity(
                id = "LAlocal",
                name = "Local artist",
                isLocal = true,
            )
        ).toMediaMetadata()

        assertEquals("LAlocal", metadata.artists.single().id)
    }

    @Test
    fun `typed metadata endpoints survive song persistence round trip`() {
        val hints = MediaMetadata.MetadataEndpointHints(
            albumBrowseId = "MPRE_album",
            artistBrowseIds = listOf("UC_first", "UC_second", "UC_first"),
            creditsBrowseId = "MPTC_song",
        )
        val stored = MediaMetadata(
            id = "song",
            title = "Song",
            artists = emptyList(),
            duration = 180,
            genre = null,
            metadataEndpointHints = hints,
            artistCreditsResolved = true,
        ).toSongEntity()
        val restored = Song(song = stored, artists = emptyList()).toMediaMetadata()

        assertEquals("MPRE_album", restored.metadataEndpointHints.albumBrowseId)
        assertEquals(
            listOf("UC_first", "UC_second"),
            restored.metadataEndpointHints.artistBrowseIds,
        )
        assertEquals("MPTC_song", restored.metadataEndpointHints.creditsBrowseId)
        assertEquals(true, restored.artistCreditsResolved)
    }

    @Test
    fun `online album identity seeds a durable typed hint when the renderer has none`() {
        val stored = MediaMetadata(
            id = "song",
            title = "Song",
            artists = emptyList(),
            duration = 180,
            album = MediaMetadata.Album("MPRE_album", "Album"),
            genre = null,
        ).toSongEntity()

        assertEquals("MPRE_album", stored.metadataAlbumBrowseId)
    }

    private fun persistedSong(vararg artists: ArtistEntity) = Song(
        song = SongEntity(
            id = "song",
            title = "Song",
            localPath = null,
        ),
        artists = artists.toList(),
    )
}
