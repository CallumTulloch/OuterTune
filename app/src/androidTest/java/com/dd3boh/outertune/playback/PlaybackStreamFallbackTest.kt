package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.PlaybackStreamResolution
import com.dd3boh.outertune.utils.probePlaybackStream
import com.dd3boh.outertune.utils.resolvePlaybackStream
import com.zionhuang.innertube.models.ResponseContext
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.response.PlayerResponse
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Production selection, GET probe, HTTP data source, caches and decoder; only server replies are fixtures. */
class PlaybackStreamFallbackTest {
    @Suppress("DEPRECATION")
    @Test
    fun uncachedSongRejectsForbiddenStreamAndPlaysValidatedFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "stream-fallback-${UUID.randomUUID()}")
        val downloadCache = SimpleCache(File(directory, "download"), NoOpCacheEvictor())
        val playerCache = SimpleCache(File(directory, "player"), NoOpCacheEvictor())
        val mediaId = "fresh403wav1"
        val audio = silentWav()
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val resolutions = CopyOnWriteArrayList<DataSpec>()
        val errors = CopyOnWriteArrayList<PlaybackException>()
        val attemptedClients = CopyOnWriteArrayList<String>()
        // An application interceptor supplies bounded server replies without changing TLS trust
        // or requiring this workstation's YouTube connection to succeed.
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("GET", request.method)
            assertEquals("identity", request.header("Accept-Encoding"))
            requests += request.url.encodedPath to request.header("Range")
            if (request.url.encodedPath == "/forbidden") {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(403).message("Forbidden").body(ByteArray(0).toResponseBody()).build()
            } else {
                assertEquals("/audio.wav", request.url.encodedPath)
                val range = request.header("Range")?.removePrefix("bytes=")?.split('-')
                val start = range?.first()?.toInt() ?: 0
                val end = range?.getOrNull(1)?.toIntOrNull() ?: audio.lastIndex
                val bytes = audio.copyOfRange(start, end + 1)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (range == null) 200 else 206).message("OK")
                    .header("Content-Length", bytes.size.toString())
                    .header("Content-Type", "audio/wav")
                    .apply { if (range != null) header("Content-Range", "bytes $start-$end/${audio.size}") }
                    .body(bytes.toResponseBody()).build()
            }
        }.build()
        var player: ExoPlayer? = null
        try {
            assertTrue(playerCache.getCachedSpans(mediaId).isEmpty())
            assertTrue(downloadCache.getCachedSpans(mediaId).isEmpty())
            val factory = createPlaybackCacheDataSourceFactory(
                downloadCache = downloadCache,
                playerCache = playerCache,
                upstreamFactory = OkHttpDataSource.Factory(client),
                resolver = ResolvingDataSource.Resolver { spec ->
                    resolutions += spec
                    val result = runBlocking {
                        resolvePlaybackStream(
                            clients = listOf(YouTubeClient.ANDROID_VR_NO_AUTH, YouTubeClient.IOS),
                            isLoggedIn = false,
                            audioQuality = AudioQuality.HIGH,
                            isMetered = false,
                            requiredItag = null,
                            requestPlayer = { candidate ->
                                attemptedClients += candidate.clientName
                                Result.success(response(if (candidate == YouTubeClient.ANDROID_VR_NO_AUTH)
                                    "https://stream.example.test/forbidden" else "https://stream.example.test/audio.wav", audio.size))
                            },
                            resolveUrl = { Result.success(requireNotNull(it.url)) },
                            probeStream = { probePlaybackStream(client, it) },
                        )
                    }
                    assertTrue("No valid fallback selected: $result", result is PlaybackStreamResolution.Success)
                    val selected = result as PlaybackStreamResolution.Success
                    assertEquals(YouTubeClient.IOS, selected.client)
                    spec.withUri(Uri.parse(selected.streamUrl))
                },
            )
            val activePlayer = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                    .setAudioAttributes(AudioAttributes.DEFAULT, false).build().also { value ->
                        player = value
                        value.volume = 0f
                        value.addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { errors += error }
                        })
                        value.setMediaItem(MediaItem.Builder().setMediaId(mediaId).setUri(Uri.parse(mediaId))
                            .setCustomCacheKey(mediaId).setMimeType(MimeTypes.AUDIO_WAV).build())
                        value.prepare()
                        value.play()
                    }
            }
            withTimeout(15_000) {
                while (true) {
                    assertTrue("Player failed: $errors", errors.isEmpty())
                    val playing = withContext(Dispatchers.Main) {
                        activePlayer.playbackState == Player.STATE_READY && activePlayer.currentPosition >= 250
                    }
                    if (playing && playerCache.isCached(mediaId, 0, audio.size.toLong())) break
                    delay(25)
                }
            }
            assertEquals(listOf("ANDROID_VR", "IOS"), attemptedClients.toList())
            assertEquals(listOf("/forbidden" to "bytes=0-0", "/audio.wav" to "bytes=0-0"), requests.take(2))
            assertTrue("Validated audio was not opened by Media3", requests.size > 2)
            // Unlike the bounded probe, an initial unbounded Media3 open needs no Range header.
            assertEquals("/audio.wav" to null, requests[2])
            assertEquals(1, requests.count { it.first == "/forbidden" })
            assertTrue(resolutions.isNotEmpty())
            assertTrue(resolutions.all { it.key == mediaId && it.uri == Uri.parse(mediaId) })
            withContext(Dispatchers.Main) {
                val item = activePlayer.currentMediaItem!!
                assertEquals(mediaId, item.mediaId)
                assertEquals(mediaId, item.localConfiguration!!.customCacheKey)
                assertEquals(Uri.parse(mediaId), item.localConfiguration!!.uri)
                assertNull(activePlayer.playerError)
                activePlayer.release()
                player = null
            }
            // Once its bytes are cached, reopening this song requires no player/probe requests.
            val requestCount = requests.size
            val resolutionCount = resolutions.size
            val source = factory.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(mediaId).setKey(mediaId).setLength(audio.size.toLong()).build())
                val buffer = ByteArray(4096)
                var total = 0
                while (true) {
                    val count = source.read(buffer, 0, buffer.size)
                    if (count == -1) break
                    assertTrue("Nonempty cache read made no progress", count > 0)
                    total += count
                }
                assertEquals(audio.size, total)
            } finally { source.close() }
            assertEquals(requestCount, requests.size)
            assertEquals(resolutionCount, resolutions.size)
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            downloadCache.release()
            playerCache.release()
            directory.deleteRecursively()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }

    private fun response(url: String, size: Int) = PlayerResponse(
        responseContext = ResponseContext(null, null),
        playabilityStatus = PlayerResponse.PlayabilityStatus("OK", null),
        playerConfig = null,
        streamingData = PlayerResponse.StreamingData(null, listOf(PlayerResponse.StreamingData.Format(
            itag = 140, url = url, mimeType = "audio/wav", bitrate = 1_536_000,
            width = null, height = null, contentLength = size.toLong(), quality = "tiny",
            fps = null, qualityLabel = null, averageBitrate = null, audioQuality = "AUDIO_QUALITY_MEDIUM",
            approxDurationMs = "12000", audioSampleRate = 48_000, audioChannels = 2,
            loudnessDb = null, lastModified = null, signatureCipher = null,
        )), 600),
        videoDetails = null,
        playbackTracking = null,
    )

    private fun silentWav(): ByteArray {
        val byteRate = 48_000 * 2 * 2
        val dataLength = byteRate * 12
        return ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataLength)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(2); putInt(48_000); putInt(byteRate); putShort(4); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataLength)
        }.array()
    }
}
