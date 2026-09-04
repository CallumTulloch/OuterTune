package com.dd3boh.outertune.extensions

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.Builder
import androidx.core.net.toUri
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MediaItemExtTest {
    @Test
    fun `media3 presentation contains resolved artist and album`() {
        val metadata = metadata(
            artists = listOf(MediaMetadata.Artist("UC_artist", "Official Artist")),
            album = MediaMetadata.Album("MPRE_album", "Official Album"),
        )

        val presentation = metadata.toMedia3Metadata()

        assertEquals("Song", presentation.title.toString())
        assertEquals("Official Artist", presentation.artist.toString())
        assertEquals("Official Artist", presentation.subtitle.toString())
        assertEquals("Official Album", presentation.albumTitle.toString())
    }

    @Test
    fun `metadata for another media id is ignored`() {
        val item = MediaItem.Builder().setMediaId("video").build()

        assertSame(
            item,
            item.withMetadata(
                metadata(
                    id = "other",
                    artists = emptyList(),
                    album = null,
                ),
            ),
        )
    }

    @Test
    fun `complete snapshot clears stale album and artwork from the media session metadata`() {
        val base = Builder()
            .setAlbumTitle("Old album")
            .setArtworkUri("https://example.com/old.jpg".toUri())
            .build()

        val presentation = metadata(
            artists = listOf(MediaMetadata.Artist(null, "Artist")),
            album = null,
        ).toMedia3Metadata(base)

        assertEquals(null, presentation.albumTitle)
        assertEquals(null, presentation.artworkUri)
    }

    private fun metadata(
        id: String = "video",
        artists: List<MediaMetadata.Artist>,
        album: MediaMetadata.Album?,
    ) = MediaMetadata(
        id = id,
        title = "Song",
        artists = artists,
        duration = 180,
        album = album,
        genre = null,
    )
}
