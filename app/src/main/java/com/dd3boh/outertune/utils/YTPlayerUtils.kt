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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

object YTPlayerUtils {

    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    /**
     * The main client is used for metadata and initial streams.
     * Do not use other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * [com.zionhuang.innertube.models.YouTubeClient.ANDROID_VR_NO_AUTH] Is temporally used as it is out only working client
     * [com.zionhuang.innertube.models.YouTubeClient.WEB_REMIX] should be preferred here because currently it is the only client which provides:
     * - the correct metadata (like loudnessDb)
     * - premium formats
     */
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
        IOS, // recent api changes produce error 403 after 30 seconds
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
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
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

        /**
         * This is required for some clients to get working streams however
         * it should not be forced for the [MAIN_CLIENT] because the response of the [MAIN_CLIENT]
         * is required even if the streams won't work from this client.
         * This is why it is allowed to be null.
         */
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

        val mainPlayerResponse =
            YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp, webPlayerPot,
                requestAuthentication = authentication)
                .onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    diagnostics.record(MAIN_CLIENT.clientName, false, PlaybackFailureStage.REQUEST, failure = failure)
                    failure.addSuppressed(diagnostics.asException())
                }
                .getOrThrow()

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null

        var streamPlayerResponse: PlayerResponse? = null
        for (clientIndex in (-1 until STREAM_FALLBACK_CLIENTS.size)) {
            currentCoroutineContext().ensureActive()
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                Log.d(TAG, "Trying client: ${MAIN_CLIENT.clientName}")
                // try with streams from main client first
                client = MAIN_CLIENT
                streamPlayerResponse = mainPlayerResponse
            } else {
                Log.d(TAG, "Trying fallback client: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]

                if (client.loginRequired && !isLoggedIn) {
                    // skip client if it requires login but user is not logged in
                    diagnostics.record(client.clientName, false, PlaybackFailureStage.SKIPPED_LOGIN)
                    continue
                }

                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, signatureTimestamp, webPlayerPot,
                        requestAuthentication = authentication)
                        .onFailure { failure ->
                            if (failure is CancellationException) throw failure
                            diagnostics.record(
                                client.clientName,
                                client.loginSupported && authentication.cookie != null,
                                PlaybackFailureStage.REQUEST,
                                failure = failure,
                            )
                        }
                        .getOrNull()
                if (streamPlayerResponse == null) continue
            }

            fun recordFailure(
                stage: PlaybackFailureStage,
                httpCode: Int? = null,
                failure: Throwable? = null,
            ) = diagnostics.record(
                client.clientName,
                client.loginSupported && authentication.cookie != null,
                stage,
                status = streamPlayerResponse?.playabilityStatus?.status,
                httpCode = httpCode,
                selectedItag = format?.itag,
                failure = failure,
            )

            currentCoroutineContext().ensureActive()
            Log.d(TAG, "[$videoId] stream client: ${client.clientName}, " +
                    "playabilityStatus: ${streamPlayerResponse?.playabilityStatus?.let {
                        it.status + (it.reason?.let { " - $it" } ?: "")
                    }}")

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                format =
                    findFormat(
                        streamPlayerResponse,
                        audioQuality,
                        connectivityManager,
                        requiredItag,
                    )
                if (format == null) {
                    recordFailure(PlaybackFailureStage.FORMAT)
                    continue
                }
                streamUrl = findUrlOrNull(format, videoId)
                if (streamUrl == null) {
                    recordFailure(PlaybackFailureStage.URL)
                    continue
                }
                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    recordFailure(PlaybackFailureStage.EXPIRY)
                    continue
                }

                if (client.useWebPoTokens && webStreamingPot != null) {
                    streamUrl += "&pot=$webStreamingPot";
                }

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1) {
                    /** skip [validateStatus] for last client */
                    break
                }
                val streamStatus = validateStatus(streamUrl)
                if (streamStatus.getOrNull()?.let { it in 200..299 } == true) {
                    // working stream found
                    Log.i(TAG, "[$videoId] [${client.clientName}] found working stream")
                    break
                } else {
                    recordFailure(
                        if (streamStatus.isSuccess) PlaybackFailureStage.STREAM_HTTP else PlaybackFailureStage.STREAM_NETWORK,
                        httpCode = streamStatus.getOrNull(),
                        failure = streamStatus.exceptionOrNull(),
                    )
                    Log.w(TAG, "[$videoId] [${client.clientName}] got bad http status code")
                }
            } else {
                recordFailure(PlaybackFailureStage.PLAYABILITY)
            }
        }

        if (streamPlayerResponse == null) {
            throw Exception("Bad stream player response", diagnostics.asException())
        }
        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            throw PlaybackException(
                streamPlayerResponse.playabilityStatus.reason,
                diagnostics.asException(),
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }
        if (streamExpiresInSeconds == null) {
            throw Exception("Missing stream expire time", diagnostics.asException())
        }
        if (format == null) {
            throw Exception("Could not find format", diagnostics.asException())
        }
        if (streamUrl == null) {
            throw Exception("Could not find stream url", diagnostics.asException())
        }

        return PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
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

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        requiredItag: Int?,
    ): PlayerResponse.StreamingData.Format? =
        selectPlaybackFormat(
            playerResponse.streamingData?.adaptiveFormats.orEmpty(),
            audioQuality,
            connectivityManager.isActiveNetworkMetered,
            requiredItag,
        )

    /**
     * Preserve HTTP status separately from transport failures for the error details.
     */
    private fun validateStatus(url: String): Result<Int> {
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)
            return Result.success(httpClient.newCall(requestBuilder.build()).execute().use { it.code })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onFailure {
                reportException(it)
            }
            .getOrNull()
    }

    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String
    ): String? {
        return NewPipeUtils.getStreamUrl(format, videoId)
            .onFailure {
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
