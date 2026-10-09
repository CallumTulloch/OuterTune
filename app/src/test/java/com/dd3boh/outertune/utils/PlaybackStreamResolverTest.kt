package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.AudioQuality
import com.zionhuang.innertube.models.ResponseContext
import com.zionhuang.innertube.models.Thumbnails
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackStreamResolverTest {
    private val primary = YouTubeClient.ANDROID_VR_NO_AUTH
    private val fallback = YouTubeClient.IOS
    private val clients = listOf(primary, fallback)

    @Test
    fun `primary request failure still resolves a fallback and uses its metadata`() = runBlocking {
        val calls = mutableListOf<YouTubeClient>()
        val attempts = mutableListOf<PlaybackClientFailure>()
        val fallbackResponse = response("fallback")
        val result = resolvePlaybackStream(
            clients, false, AudioQuality.HIGH, false, null,
            requestPlayer = { client ->
                calls += client
                if (client == primary) Result.failure(IOException("Primary request refused"))
                else Result.success(fallbackResponse)
            },
            resolveUrl = ::directUrl,
            probeStream = { Result.success(206) },
            recordFailure = attempts::add,
        ) as PlaybackStreamResolution.Success

        assertEquals(clients, calls)
        assertEquals(fallback, result.client)
        assertSame(fallbackResponse, result.metadataResponse)
        assertEquals(PlaybackFailureStage.REQUEST, attempts.single().stage)
    }

    @Test
    fun `rejected primary audio falls back while retaining primary metadata`() = runBlocking {
        val primaryResponse = response("primary")
        val fallbackResponse = response("fallback")
        val probed = mutableListOf<String>()
        val attempts = mutableListOf<PlaybackClientFailure>()
        val result = resolvePlaybackStream(
            clients, false, AudioQuality.HIGH, false, null,
            requestPlayer = { Result.success(if (it == primary) primaryResponse else fallbackResponse) },
            resolveUrl = ::directUrl,
            probeStream = { url ->
                probed += url
                Result.success(if (url.contains("primary")) 403 else 206)
            },
            recordFailure = attempts::add,
        ) as PlaybackStreamResolution.Success

        assertEquals(listOf("https://fixture.invalid/primary/140", "https://fixture.invalid/fallback/140"), probed)
        assertEquals(fallback, result.client)
        assertSame(primaryResponse, result.metadataResponse)
        assertEquals(PlaybackFailureStage.STREAM_HTTP, attempts.single().stage)
        assertEquals(403, attempts.single().httpCode)
        assertEquals(140, attempts.single().format?.itag)
    }

    @Test
    fun `last client 403 cannot be adopted as a playable stream`() = runBlocking {
        val probed = mutableListOf<String>()
        val result = resolvePlaybackStream(
            clients, false, AudioQuality.HIGH, false, null,
            requestPlayer = { Result.success(response(it.clientName)) },
            resolveUrl = ::directUrl,
            probeStream = { probed += it; Result.success(403) },
        ) as PlaybackStreamResolution.Failure

        assertEquals(2, probed.size)
        assertEquals(fallback, result.lastFailure?.client)
        assertEquals(PlaybackFailureStage.STREAM_HTTP, result.lastFailure?.stage)
        assertEquals(403, result.lastFailure?.httpCode)
    }

    @Test
    fun `last client transport failure cannot be adopted as a playable stream`() = runBlocking {
        val transportFailure = IOException("Stream transport failed")
        val result = resolvePlaybackStream(
            clients, false, AudioQuality.HIGH, false, null,
            requestPlayer = { Result.success(response(it.clientName)) },
            resolveUrl = ::directUrl,
            probeStream = { Result.failure(transportFailure) },
        ) as PlaybackStreamResolution.Failure

        assertEquals(fallback, result.lastFailure?.client)
        assertEquals(PlaybackFailureStage.STREAM_NETWORK, result.lastFailure?.stage)
        assertSame(transportFailure, result.lastFailure?.failure)
        assertNull(result.lastFailure?.httpCode)
    }

    @Test
    fun `cached representation stays pinned even when another client has a preferred format`() = runBlocking {
        val high = format("fallback", 141, 256_000)
        val other = format("primary", 140, 48_000)
        val attempts = mutableListOf<PlaybackClientFailure>()
        val probed = mutableListOf<String>()
        val result = resolvePlaybackStream(
            clients, false, AudioQuality.LOW, true, high.itag,
            requestPlayer = { client ->
                Result.success(if (client == primary) response("primary", listOf(other))
                    else response("fallback", listOf(other, high)))
            },
            resolveUrl = ::directUrl,
            probeStream = { probed += it; Result.success(206) },
            recordFailure = attempts::add,
        ) as PlaybackStreamResolution.Success

        assertEquals(high, result.format)
        assertEquals(listOf(high.url), probed)
        assertEquals(PlaybackFailureStage.FORMAT, attempts.single().stage)
    }

    @Test
    fun `anonymous playback skips login required clients and continues`() = runBlocking {
        val loginClient = YouTubeClient.TVHTML5
        val calls = mutableListOf<YouTubeClient>()
        val attempts = mutableListOf<PlaybackClientFailure>()
        val result = resolvePlaybackStream(
            listOf(primary, loginClient, fallback), false, AudioQuality.HIGH, false, null,
            requestPlayer = { client ->
                calls += client
                check(client != loginClient) { "Anonymous playback requested a login client" }
                Result.success(response(client.clientName,
                    status = if (client == primary) "UNPLAYABLE" else "OK"))
            },
            resolveUrl = ::directUrl,
            probeStream = { Result.success(206) },
            recordFailure = attempts::add,
        ) as PlaybackStreamResolution.Success

        assertEquals(clients, calls)
        assertEquals(fallback, result.client)
        assertEquals(listOf(PlaybackFailureStage.PLAYABILITY, PlaybackFailureStage.SKIPPED_LOGIN),
            attempts.map { it.stage })
    }

    @Test
    fun `skipped login client does not erase a preceding request failure`() = runBlocking {
        val failedRequest = IOException("Request refused")
        val result = resolvePlaybackStream(
            listOf(primary, YouTubeClient.TVHTML5), false, AudioQuality.HIGH, false, null,
            requestPlayer = { Result.failure(failedRequest) },
            resolveUrl = ::directUrl,
            probeStream = { error("No response supplied a stream") },
        ) as PlaybackStreamResolution.Failure

        assertEquals(primary, result.lastFailure?.client)
        assertSame(failedRequest, result.lastFailure?.failure)
    }

    @Test
    fun `web stream token decorates the URL before probing and returning it`() = runBlocking {
        var probed: String? = null
        val result = resolvePlaybackStream(
            listOf(YouTubeClient.WEB_REMIX), true, AudioQuality.HIGH, false, null,
            requestPlayer = { Result.success(response("web")) },
            resolveUrl = ::directUrl,
            probeStream = { probed = it; Result.success(206) },
            decorateUrl = { _, url -> "$url&pot=fixture-token" },
        ) as PlaybackStreamResolution.Success

        assertEquals("https://fixture.invalid/web/140&pot=fixture-token", probed)
        assertEquals(probed, result.streamUrl)
    }

    @Test
    fun `cancellation from request URL or probe never falls through to another client`() {
        for (cancelStage in listOf(PlaybackFailureStage.REQUEST, PlaybackFailureStage.URL, PlaybackFailureStage.STREAM_NETWORK)) {
            val cancelled = CancellationException("Fixture cancellation")
            val calls = mutableListOf<YouTubeClient>()
            val attempts = mutableListOf<PlaybackClientFailure>()
            try {
                runBlocking {
                    resolvePlaybackStream(
                        clients, false, AudioQuality.HIGH, false, null,
                        requestPlayer = { client ->
                            calls += client
                            if (cancelStage == PlaybackFailureStage.REQUEST) Result.failure(cancelled)
                            else Result.success(response("primary"))
                        },
                        resolveUrl = { if (cancelStage == PlaybackFailureStage.URL) Result.failure(cancelled) else directUrl(it) },
                        probeStream = { if (cancelStage == PlaybackFailureStage.STREAM_NETWORK) Result.failure(cancelled) else Result.success(206) },
                        recordFailure = attempts::add,
                    )
                }
                fail("Cancellation at $cancelStage was swallowed")
            } catch (failure: CancellationException) {
                assertSame(cancelled, failure)
            }
            assertEquals(listOf(primary), calls)
            assertTrue(attempts.isEmpty())
        }
    }

    private fun directUrl(format: PlayerResponse.StreamingData.Format) = Result.success(requireNotNull(format.url))

    private fun response(
        source: String,
        formats: List<PlayerResponse.StreamingData.Format> = listOf(format(source, 140, 128_000)),
        status: String = "OK",
    ) = PlayerResponse(
        responseContext = ResponseContext(null, null),
        playabilityStatus = PlayerResponse.PlayabilityStatus(status, null),
        playerConfig = PlayerResponse.PlayerConfig(PlayerResponse.PlayerConfig.AudioConfig(-5.0, null)),
        streamingData = PlayerResponse.StreamingData(null, formats, 3600),
        videoDetails = PlayerResponse.VideoDetails("fixture-video", source, source, "fixture-channel",
            "180", null, "1", Thumbnails(emptyList())),
        playbackTracking = null,
    )

    // Same audio model as PlaybackFormatsTest; no live API, signature JS or credentials are required.
    private fun format(source: String, itag: Int, bitrate: Int) = PlayerResponse.StreamingData.Format(
        itag = itag, bitrate = bitrate, url = "https://fixture.invalid/$source/$itag",
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", width = null, height = null,
        contentLength = 1_000_000L, quality = "tiny", fps = null, qualityLabel = null,
        averageBitrate = null, audioQuality = null, approxDurationMs = null,
        audioSampleRate = 44_100, audioChannels = 2, loudnessDb = null,
        lastModified = null, signatureCipher = null,
    )
}
