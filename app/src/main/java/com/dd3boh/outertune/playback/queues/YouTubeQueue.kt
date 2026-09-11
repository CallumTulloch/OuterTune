package com.dd3boh.outertune.playback.queues

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.NextResult
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class YouTubeQueue internal constructor(
    private var endpoint: WatchEndpoint,
    override val preloadItem: MediaMetadata? = null,
    override val playlistId: String? = endpoint.playlistId,
    override val startShuffled: Boolean = false,
    private var continuation: String? = null,
    private val runtime: Runtime,
) : Queue {
    constructor(
        endpoint: WatchEndpoint,
        preloadItem: MediaMetadata? = null,
        playlistId: String? = endpoint.playlistId,
        startShuffled: Boolean = false,
        continuation: String? = null,
    ) : this(endpoint, preloadItem, playlistId, startShuffled, continuation, Runtime())

    internal class Runtime(
        val locale: () -> YouTubeLocale = { YouTube.locale },
        val next: suspend (WatchEndpoint, String?, YouTubeLocale) -> Result<NextResult> = { endpoint, token, locale ->
            YouTube.next(endpoint, token, requestLocale = locale)
        },
    )

    // Pagination belongs to the request that created it, even when display settings change.
    private var requestLocale: YouTubeLocale? = null
    private fun locale() = requestLocale ?: runtime.locale().also { requestLocale = it }

    override suspend fun getInitialStatus(): Queue.Status {
        val locale = locale()
        val nextResult = withContext(IO) {
            runtime.next(endpoint, continuation, locale).getOrThrow()
        }
        endpoint = nextResult.endpoint
        continuation = nextResult.continuation
        return Queue.Status(
            title = nextResult.title,
            items = nextResult.items.map { it.toMediaMetadata() },
            mediaItemIndex = nextResult.currentIndex ?: 0
        )
    }

    override fun hasNextPage(): Boolean = continuation != null

    override suspend fun nextPage(): List<MediaMetadata> {
        val locale = locale()
        val nextResult = withContext(IO) {
            runtime.next(endpoint, continuation, locale).getOrNull()
        }
        if (nextResult != null) {
            endpoint = nextResult.endpoint
        }
        continuation = nextResult?.continuation
        return nextResult?.items?.map { it.toMediaMetadata() } ?: emptyList()
    }

//    fun getContinuationEndpoint(): String? {
//        return if (endpoint.videoId != null && continuation != null) {
//            "${endpoint.videoId}\n$continuation"
//        } else {
//            null
//        }
//    }

    companion object {
        fun radio(song: MediaMetadata) = YouTubeQueue(WatchEndpoint(song.id), song)
    }
}
