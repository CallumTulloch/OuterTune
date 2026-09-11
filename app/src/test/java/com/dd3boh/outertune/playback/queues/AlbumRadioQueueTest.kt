package com.dd3boh.outertune.playback.queues

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.NextResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AlbumRadioQueueTest {
    @Test fun `short radio response preserves the complete album and can continue playback queue`() = runBlocking {
        val radio = YouTubeAlbumRadio("album", runtime = YouTubeAlbumRadio.Runtime(
            locale = { locale },
            albumSongs = { _, _ -> Result.success(listOf(song("one"), song("two"), song("three"))) },
            next = { endpoint, token, _ -> Result.success(NextResult(
                title = "Album radio",
                items = listOf(song(if (token == null) "one" else "next-radio-song")),
                currentIndex = 0,
                continuation = if (token == null) "next" else null,
                endpoint = endpoint,
            )) },
        ))

        val initial = radio.getInitialStatus()
        assertEquals(listOf("one", "two", "three"), initial.items.map { it.id })
        assertEquals("Album radio", initial.title)
        assertEquals(0, initial.mediaItemIndex)
        assertTrue(radio.hasNextPage())
        assertEquals(listOf("next-radio-song"), radio.nextPage().map { it.id })
        assertFalse(radio.hasNextPage())
    }

    @Test fun `empty radio response preserves available album tracks and handles entirely empty results`() = runBlocking {
        for (albumSongs in listOf(listOf(song("one"), song("two")), emptyList())) {
            val radio = YouTubeAlbumRadio("album", runtime = YouTubeAlbumRadio.Runtime(
                locale = { locale },
                albumSongs = { _, _ -> Result.success(albumSongs) },
                next = { endpoint, _, _ -> Result.success(NextResult(
                    items = emptyList(), continuation = null, endpoint = endpoint,
                )) },
            ))

            val initial = radio.getInitialStatus()
            assertEquals(albumSongs.map { it.id }, initial.items.map { it.id })
            assertEquals(0, initial.mediaItemIndex)
            assertFalse(radio.hasNextPage())
        }
    }

    companion object {
        private val locale = YouTubeLocale("JP", "ja")
        private fun song(id: String) = SongItem(id, id, emptyList(), thumbnail = "")
    }
}
