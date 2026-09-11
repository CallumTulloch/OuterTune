package com.dd3boh.outertune.lyrics

import android.content.Context
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.WatchEndpoint

object YouTubeLyricsProvider : LyricsProvider {
    override val name = "YouTube Music"
    override fun isEnabled(context: Context) = true
    override suspend fun getLyrics(id: String, title: String, artist: String, duration: Int): Result<String> = runCatching {
        val requestLocale = YouTube.locale
        val nextResult = YouTube.next(WatchEndpoint(videoId = id), requestLocale = requestLocale).getOrThrow()
        YouTube.lyrics(
            endpoint = nextResult.lyricsEndpoint ?: throw IllegalStateException("Lyrics endpoint not found"),
            requestLocale = requestLocale,
        ).getOrThrow() ?: throw IllegalStateException("Lyrics unavailable")
    }
}
