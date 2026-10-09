package com.dd3boh.outertune.utils

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

/** Tests the playback GET without downloading the audio; HTTP rejections remain status values. */
internal fun probePlaybackStream(httpClient: OkHttpClient, url: String): Result<Int> = try {
    val request = Request.Builder()
        .url(url)
        .get()
        .header("Range", "bytes=0-0")
        .header("Accept-Encoding", "identity")
        .build()
    httpClient.newCall(request).execute().use { response -> Result.success(response.code) }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    Result.failure(failure)
}
