package com.dd3boh.outertune.playback

/** Temporary signed URLs. Audio bytes remain in the separate Media3 caches. */
internal class PlaybackUrlCache(
    private val nowMs: () -> Long,
    private val maxEntries: Int = 64,
) {
    private data class Entry(val url: String, val expiresAtMs: Long)
    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    operator fun get(mediaId: String): String? {
        val entry = entries[mediaId] ?: return null
        if (entry.expiresAtMs <= nowMs()) {
            entries.remove(mediaId)
            return null
        }
        return entry.url
    }

    @Synchronized
    fun put(mediaId: String, url: String, expiresInSeconds: Int) {
        // Leave time for opening the URL and starting the request before its server deadline.
        val lifetimeMs = (expiresInSeconds * 1_000L - 30_000L).coerceAtLeast(0L)
        entries.remove(mediaId)
        if (lifetimeMs == 0L) return
        entries[mediaId] = Entry(url, nowMs() + lifetimeMs)
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    @Synchronized
    fun invalidate(mediaId: String): Boolean = entries.remove(mediaId) != null
}

/** One automatic refresh per selected item; repeated server failures remain visible to the user. */
internal class StreamRefreshRetry {
    private var attempted = false

    fun reset() { attempted = false }

    fun shouldRetry(httpStatus: Int?, hadCachedUrl: Boolean, playWhenReady: Boolean): Boolean {
        if (attempted || !hadCachedUrl || !playWhenReady || httpStatus !in setOf(401, 403, 410)) return false
        attempted = true
        return true
    }
}
