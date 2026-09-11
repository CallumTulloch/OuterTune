package com.dd3boh.outertune.playback.queues

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.NextResult
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeAlbumRadio internal constructor(
    override val playlistId: String,
    override val startShuffled: Boolean = false,
    private val runtime: Runtime,
) : Queue {
    constructor(playlistId: String, startShuffled: Boolean = false) : this(playlistId, startShuffled, Runtime())

    internal class Runtime(
        val locale: () -> YouTubeLocale = { YouTube.locale },
        val albumSongs: suspend (String, YouTubeLocale) -> Result<List<SongItem>> = { id, locale ->
            YouTube.albumSongs(id, requestLocale = locale)
        },
        val next: suspend (WatchEndpoint, String?, YouTubeLocale) -> Result<NextResult> = { endpoint, token, locale ->
            YouTube.next(endpoint, token, requestLocale = locale)
        },
    )

    override val preloadItem: MediaMetadata? = null
    private val endpoint = WatchEndpoint(
        playlistId = playlistId,
        params = "wAEB"
    )
    private var continuation: String? = null
    private var requestLocale: YouTubeLocale? = null
    private fun locale() = requestLocale ?: runtime.locale().also { requestLocale = it }

    override suspend fun getInitialStatus(): Queue.Status = withContext(IO) {
        val locale = locale()
        val albumSongs = runtime.albumSongs(playlistId, locale).getOrThrow()
        val nextResult = runtime.next(endpoint, continuation, locale).getOrThrow()
        continuation = nextResult.continuation
        Queue.Status(
            title = nextResult.title,
            // A radio response may contain only part of the album, or no rows at all.
            items = (albumSongs + nextResult.items.drop(albumSongs.size)).map { it.toMediaMetadata() },
            mediaItemIndex = nextResult.currentIndex ?: 0
        )
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaMetadata> {
        val locale = locale()
        val nextResult = withContext(IO) {
            runtime.next(endpoint, continuation, locale).getOrThrow()
        }
        continuation = nextResult.continuation
        return nextResult.items.map { it.toMediaMetadata() }
    }
}
