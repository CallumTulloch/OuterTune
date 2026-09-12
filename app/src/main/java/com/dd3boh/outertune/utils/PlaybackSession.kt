package com.dd3boh.outertune.utils

import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Retry once with the new session if authentication changes during stream resolution. */
internal suspend fun <S, T> withStablePlaybackSession(
    session: () -> S,
    request: suspend (S) -> T,
): T {
    repeat(2) {
        val started = session()
        val result = try {
            Result.success(request(started))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
        if (started == session()) return result.getOrThrow()
    }
    throw IOException("Authentication changed during playback; retry playback")
}
