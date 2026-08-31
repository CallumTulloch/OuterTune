package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadUtilTest {
    private val original = MediaMetadata(
        id = "4tlUwgtgdZA",
        title = "丸ノ内サディスティック",
        artists = listOf(MediaMetadata.Artist(id = null, name = "椎名林檎")),
        duration = 214,
        thumbnailUrl = "search-thumbnail",
        album = null,
        genre = null,
    )
    private val resolved = original.copy(
        title = "different queue title",
        artists = listOf(MediaMetadata.Artist(id = "UCbrWU0y_rLsEOYgaTX5Y74A", name = "椎名林檎")),
        thumbnailUrl = "queue-thumbnail",
        album = MediaMetadata.Album(
            id = "MPREb_Oo0wRyxtDXp",
            title = "無罪モラトリアム",
        ),
    )

    @Test
    fun `queue metadata fills missing album without replacing search presentation`() {
        val merged = mergeResolvedMetadata(original, resolved)

        assertEquals(resolved.album, merged.album)
        assertEquals("UCbrWU0y_rLsEOYgaTX5Y74A", merged.artists.single().id)
        assertEquals(original.title, merged.title)
        assertEquals(original.thumbnailUrl, merged.thumbnailUrl)
    }

    @Test
    fun `missing queue album does not invent an album`() {
        val merged = mergeResolvedMetadata(original, resolved.copy(album = null))

        assertNull(merged.album)
    }
}
