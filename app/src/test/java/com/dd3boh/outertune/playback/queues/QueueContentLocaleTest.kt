package com.dd3boh.outertune.playback.queues

import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.NextResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueContentLocaleTest {
    @Test fun `queue captures locale at first request and keeps it with continuation tokens`() = runBlocking {
        var current = english
        val calls = mutableListOf<Pair<String?, YouTubeLocale>>()
        val queue = YouTubeQueue(WatchEndpoint(videoId = "first"), runtime = YouTubeQueue.Runtime(
            locale = { current },
            next = { endpoint, token, locale ->
                calls += token to locale
                Result.success(NextResult(items = listOf(song(if (token == null) "first" else "second")),
                    continuation = if (token == null) "ja-next" else null, endpoint = endpoint))
            },
        ))
        // Creating a queue does not prematurely freeze the language before playback begins.
        current = japanese
        assertEquals("first", queue.getInitialStatus().items.single().id)
        current = english
        assertEquals("second", queue.nextPage().single().id)
        assertEquals(listOf(null to japanese, "ja-next" to japanese), calls)
        assertFalse(queue.hasNextPage())
    }

    @Test fun `album tracks radio start and continuation use one locale across setting changes`() = runBlocking {
        var current = japanese
        val calls = mutableListOf<Pair<String, YouTubeLocale>>()
        val radio = YouTubeAlbumRadio("album-playlist", runtime = YouTubeAlbumRadio.Runtime(
            locale = { current },
            albumSongs = { _, locale ->
                calls += "album" to locale
                current = english
                Result.success(listOf(song("album-song")))
            },
            next = { endpoint, token, locale ->
                calls += (token ?: "initial") to locale
                Result.success(NextResult(items = if (token == null) listOf(song("album-song"), song("radio-song")) else listOf(song("more")),
                    continuation = if (token == null) "radio-next" else null, endpoint = endpoint))
            },
        ))
        assertEquals(listOf("album-song", "radio-song"), radio.getInitialStatus().items.map { it.id })
        assertEquals(listOf("more"), radio.nextPage().map { it.id })
        assertEquals(listOf("album" to japanese, "initial" to japanese, "radio-next" to japanese), calls)
        assertFalse(radio.hasNextPage())
    }

    companion object {
        private val english = YouTubeLocale("US", "en")
        private val japanese = YouTubeLocale("JP", "ja")
        private fun song(id: String) = SongItem(id, id, emptyList(), thumbnail = "")
    }
}
