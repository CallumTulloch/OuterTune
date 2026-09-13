package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerAlbumNavigationTest {
    private fun metadata(isLocal: Boolean = true, albumId: String? = "LBold") = MediaMetadata(
        id = "song",
        title = "Track",
        artists = emptyList(),
        duration = 10,
        genre = null,
        isLocal = isLocal,
        album = albumId?.let { MediaMetadata.Album(it, "Same title", isLocal = isLocal) },
    )

    private fun savedSong(id: String = "song", albumId: String? = "LBcurrent") = Song(
        song = SongEntity(id = id, title = "Track", duration = 10, isLocal = true, localPath = "/Music/track.flac"),
        artists = emptyList(),
        album = albumId?.let {
            AlbumEntity(id = it, title = "Same title", songCount = 1, duration = 10, isLocal = true)
        },
    )

    @Test
    fun localSongUsesCurrentRelationAfterAlbumChanges() {
        assertEquals("LBcurrent", metadata().playerAlbumId(savedSong()))
    }

    @Test
    fun localSongCanOpenAlbumMissingFromQueueMetadata() {
        assertEquals("LBcurrent", metadata(albumId = null).playerAlbumId(savedSong()))
    }

    @Test
    fun localSongDoesNotOpenDeletedOrUnloadedAlbumFromQueue() {
        assertNull(metadata().playerAlbumId(savedSong(albumId = null)))
        assertNull(metadata().playerAlbumId(null))
    }

    @Test
    fun changingTracksDoesNotReusePreviousSongsAlbum() {
        assertNull(metadata().playerAlbumId(savedSong(id = "previous-song")))
    }

    @Test
    fun onlineSongKeepsItsOnlineAlbumWithoutLibraryEntry() {
        assertEquals("MPREonline", metadata(isLocal = false, albumId = "MPREonline").playerAlbumId(null))
        assertNull(metadata(isLocal = false, albumId = null).playerAlbumId(savedSong()))
    }
}
