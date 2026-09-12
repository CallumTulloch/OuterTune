package com.zionhuang.innertube

/** Account synchronization is disabled; browsing and playback keep their existing authentication. */
object YouTubeSyncPolicy {
    const val ENABLED: Boolean = false

    internal fun requireEnabled() {
        if (!ENABLED) throw YouTubeSyncDisabledException()
    }
}

/** Fixed diagnostic text: never include account information, request arguments, or a response. */
class YouTubeSyncDisabledException : IllegalStateException("YouTube synchronization is disabled")
