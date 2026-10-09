/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils

import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.PlaybackException
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.YTPlayerUtils.MAIN_CLIENT
import com.dd3boh.outertune.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.dd3boh.outertune.utils.YTPlayerUtils.validateStatus
import com.dd3boh.outertune.utils.potoken.PoTokenGenerator
import com.dd3boh.outertune.utils.potoken.PoTokenResult
import com.zionhuang.innertube.NewPipeUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.YouTubeAuthentication
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID
import com.zionhuang.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.zionhuang.innertube.models.YouTubeClient.Companion.IOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.TVHTML5
import com.zionhuang.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.zionhuang.innertube.models.YouTubeClient.Companion.VISIONOS
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.response.PlayerResponse
import okhttp3.OkHttpClient
import kotlinx.coroutines.CancellationException

object YTPlayerUtils {

    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    /** Keep this client's metadata when its request succeeds, even if another client supplies audio. */
    private val MAIN_CLIENT: YouTubeClient = ANDROID_VR_NO_AUTH

    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        VISIONOS,
        WEB_REMIX,
        ANDROID,
        TVHTML5,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        IOS,
    )


    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val authRevision: Long,
    )

    /**
     * Custom player response intended to use for playback.
     * Metadata comes from [MAIN_CLIENT] when it answers. If its request fails, the client
     * supplying the verified stream also supplies metadata. Every stream URL is verified.
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        requiredItag: Int? = null,
    ): Result<PlaybackData> = try {
        Result.success(withStablePlaybackSession({ YouTube.authentication }) { authentication ->
            resolvePlayback(videoId, playlistId, audioQuality, connectivityManager, requiredItag, authentication)
        })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private suspend fun resolvePlayback(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        requiredItag: Int?,
        authentication: YouTubeAuthentication,
    ): PlaybackData {
        Log.d(TAG, "Playback info requested: $videoId")

        // Signature lookup is optional: some clients can supply audio without a timestamp.
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)

        val isLoggedIn = !authentication.cookie.isNullOrBlank()
        val sessionId =
            if (isLoggedIn) {
                // signed in sessions use dataSyncId as identifier
                authentication.dataSyncId
            } else {
                // signed out sessions use visitorData as identifier
                authentication.visitorData
            }

        val diagnostics = PlaybackDiagnostics(
            cookiePresent = isLoggedIn,
            visitorPresent = !authentication.visitorData.isNullOrBlank(),
            sessionPresent = !sessionId.isNullOrBlank(),
            authRevision = authentication.revision,
            requiredItag = requiredItag,
            authorizationAvailable = authentication.authorizationAvailable,
        ).apply { signatureTimestampAvailable = signatureTimestamp != null }

        Log.d(TAG, "[$videoId] signatureTimestamp: $signatureTimestamp, isLoggedIn: $isLoggedIn")

        val (webPlayerPot, webStreamingPot) = getWebClientPoTokenOrNull(videoId, sessionId, authentication.revision)?.let {
            Pair(it.playerRequestPoToken, it.streamingDataPoToken)
        } ?: Pair(null, null).also {
            Log.w(TAG, "[$videoId] No po token")
        }
        diagnostics.poTokenAvailable = webPlayerPot != null && webStreamingPot != null

        val resolution = resolvePlaybackStream(
            clients = listOf(MAIN_CLIENT) + STREAM_FALLBACK_CLIENTS,
            isLoggedIn = isLoggedIn,
            audioQuality = audioQuality,
            isMetered = connectivityManager.isActiveNetworkMetered,
            requiredItag = requiredItag,
            requestPlayer = { client ->
                Log.d(TAG, "[$videoId] Trying client: ${client.clientName}")
                YouTube.player(videoId, playlistId, client, signatureTimestamp, webPlayerPot,
                    requestAuthentication = authentication)
            },
            resolveUrl = { format ->
                NewPipeUtils.getStreamUrl(format, videoId).onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    reportException(failure)
                }
            },
            probeStream = ::validateStatus,
            decorateUrl = { client, url ->
                if (client.useWebPoTokens && webStreamingPot != null) "$url&pot=$webStreamingPot" else url
            },
            recordFailure = { attempt ->
                diagnostics.record(
                    attempt.client.clientName,
                    attempt.client.loginSupported && authentication.cookie != null,
                    attempt.stage,
                    status = attempt.response?.playabilityStatus?.status,
                    httpCode = attempt.httpCode,
                    selectedItag = attempt.format?.itag,
                    failure = attempt.failure,
                )
            },
        )
        if (resolution is PlaybackStreamResolution.Failure) {
            val attempt = resolution.lastFailure
            val diagnostic = diagnostics.asException()
            if (attempt?.stage == PlaybackFailureStage.PLAYABILITY) {
                throw PlaybackException(attempt.response?.playabilityStatus?.reason,
                    diagnostic, PlaybackException.ERROR_CODE_REMOTE_ERROR)
            }
            val message = when (attempt?.stage) {
                PlaybackFailureStage.FORMAT -> "Could not find format"
                PlaybackFailureStage.URL -> "Could not find stream url"
                PlaybackFailureStage.EXPIRY -> "Missing stream expire time"
                PlaybackFailureStage.STREAM_HTTP -> "Stream rejected (HTTP ${attempt.httpCode})"
                PlaybackFailureStage.STREAM_NETWORK -> "Could not verify stream url"
                else -> "Bad stream player response"
            }
            // Keep transport exceptions recognizable by MusicService's network error handling.
            attempt?.failure?.let { failure ->
                failure.addSuppressed(diagnostic)
                throw failure
            }
            throw Exception(message, diagnostic)
        }
        resolution as PlaybackStreamResolution.Success
        Log.i(TAG, "[$videoId] [${resolution.client.clientName}] found working stream")
        return PlaybackData(
            resolution.metadataResponse.playerConfig?.audioConfig,
            resolution.metadataResponse.videoDetails,
            resolution.metadataResponse.playbackTracking,
            resolution.format,
            resolution.streamUrl,
            resolution.streamExpiresInSeconds,
            authentication.revision,
        )
    }

    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> =
        YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history

    /**
     * Preserve HTTP status separately from transport failures for the error details.
     */
    private fun validateStatus(url: String): Result<Int> = probePlaybackStream(httpClient, url)

    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onFailure {
                if (it is CancellationException) throw it
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Wrapper around the [PoTokenGenerator.getWebClientPoToken] function which reports exceptions
     */
    private suspend fun getWebClientPoTokenOrNull(videoId: String, sessionId: String?, authRevision: Long): PoTokenResult? {
        if (sessionId == null) {
            Log.d(TAG, "[$videoId] Session identifier is null")
            return null
        }
        try {
            return poTokenGenerator.getWebClientPoToken(videoId, sessionId, authRevision)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            reportException(e)
        }
        return null
    }
}
