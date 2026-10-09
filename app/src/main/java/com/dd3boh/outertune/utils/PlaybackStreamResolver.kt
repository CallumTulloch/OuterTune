package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.AudioQuality
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PlaybackClientFailure(
    val client: YouTubeClient,
    val stage: PlaybackFailureStage,
    val response: PlayerResponse? = null,
    val format: PlayerResponse.StreamingData.Format? = null,
    val httpCode: Int? = null,
    val failure: Throwable? = null,
)

internal sealed interface PlaybackStreamResolution {
    data class Success(
        val client: YouTubeClient,
        val metadataResponse: PlayerResponse,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    ) : PlaybackStreamResolution

    data class Failure(val lastFailure: PlaybackClientFailure?) : PlaybackStreamResolution
}

/** A client is usable only after its selected audio URL passes the same probe as every other client. */
internal suspend fun resolvePlaybackStream(
    clients: List<YouTubeClient>,
    isLoggedIn: Boolean,
    audioQuality: AudioQuality,
    isMetered: Boolean,
    requiredItag: Int?,
    requestPlayer: suspend (YouTubeClient) -> Result<PlayerResponse>,
    resolveUrl: (PlayerResponse.StreamingData.Format) -> Result<String>,
    probeStream: (String) -> Result<Int>,
    decorateUrl: (YouTubeClient, String) -> String = { _, url -> url },
    recordFailure: (PlaybackClientFailure) -> Unit = {},
): PlaybackStreamResolution {
    var primaryResponse: PlayerResponse? = null
    var lastFailure: PlaybackClientFailure? = null

    fun failed(attempt: PlaybackClientFailure) {
        // A login-only client being unavailable must not hide the last attempted client's error.
        if (attempt.stage != PlaybackFailureStage.SKIPPED_LOGIN) lastFailure = attempt
        recordFailure(attempt)
    }

    for ((index, client) in clients.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (client.loginRequired && !isLoggedIn) {
            failed(PlaybackClientFailure(client, PlaybackFailureStage.SKIPPED_LOGIN))
            continue
        }

        val request = requestPlayer(client).preserveCancellation()
        val response = request.getOrNull()
        if (response == null) {
            failed(PlaybackClientFailure(client, PlaybackFailureStage.REQUEST, failure = request.exceptionOrNull()))
            continue
        }
        if (index == 0) primaryResponse = response
        if (response.playabilityStatus.status != "OK") {
            failed(PlaybackClientFailure(client, PlaybackFailureStage.PLAYABILITY, response))
            continue
        }

        val format = selectPlaybackFormat(
            response.streamingData?.adaptiveFormats.orEmpty(), audioQuality, isMetered, requiredItag,
        )
        if (format == null) {
            failed(PlaybackClientFailure(client, PlaybackFailureStage.FORMAT, response))
            continue
        }
        val resolvedUrl = resolveUrl(format).preserveCancellation()
        val url = resolvedUrl.getOrNull()
        if (url == null) {
            failed(PlaybackClientFailure(client, PlaybackFailureStage.URL, response, format,
                failure = resolvedUrl.exceptionOrNull()))
            continue
        }
        val expiresInSeconds = response.streamingData?.expiresInSeconds
        if (expiresInSeconds == null) {
            failed(PlaybackClientFailure(client, PlaybackFailureStage.EXPIRY, response, format))
            continue
        }

        currentCoroutineContext().ensureActive()
        val streamUrl = decorateUrl(client, url)
        val status = probeStream(streamUrl).preserveCancellation()
        currentCoroutineContext().ensureActive()
        if (status.getOrNull()?.let { it in 200..299 } == true) {
            // Keep the primary client's normalization/metadata when it answered. If that request
            // failed, use the response that actually supplied the playable stream.
            return PlaybackStreamResolution.Success(
                client, primaryResponse ?: response, format, streamUrl, expiresInSeconds,
            )
        }
        failed(PlaybackClientFailure(
            client,
            if (status.isSuccess) PlaybackFailureStage.STREAM_HTTP else PlaybackFailureStage.STREAM_NETWORK,
            response, format, status.getOrNull(), status.exceptionOrNull(),
        ))
    }
    return PlaybackStreamResolution.Failure(lastFailure)
}

private fun <T> Result<T>.preserveCancellation(): Result<T> {
    (exceptionOrNull() as? CancellationException)?.let { throw it }
    return this
}
