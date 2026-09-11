package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real extractor, decoder and player; only the remote audio bytes are replaced by a local fixture. */
class PlaybackMetadataContinuityTest {
    @Suppress("DEPRECATION")
    @Test
    fun metadataLanguageReplacementKeepsPlayingAcrossCachedPrefixAndLargeSeek() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "playback-metadata-test-${UUID.randomUUID()}")
        val downloadCache = SimpleCache(File(directory, "download"), NoOpCacheEvictor())
        val playerCache = SimpleCache(File(directory, "player"), NoOpCacheEvictor())
        val mediaId = "abcdefghijk"
        val originalUri = Uri.parse(mediaId)
        val signedUri = Uri.parse("https://stream.example.test/continuity.wav?signature=fresh")
        val audio = silentWav()
        val resolutions = CopyOnWriteArrayList<DataSpec>()
        val errors = CopyOnWriteArrayList<PlaybackException>()
        var player: ExoPlayer? = null
        try {
            val prefixLength = 4096L
            val hole = downloadCache.startReadWrite(mediaId, 0, prefixLength)
            try {
                val file = downloadCache.startFile(mediaId, 0, prefixLength)
                file.writeBytes(audio.copyOfRange(0, prefixLength.toInt()))
                downloadCache.commitFile(file, prefixLength)
            } finally {
                downloadCache.releaseHoleSpan(hole)
            }
            val cacheFactory = createPlaybackCacheDataSourceFactory(
                downloadCache = downloadCache,
                playerCache = playerCache,
                upstreamFactory = DataSource.Factory {
                    ByteArrayDataSource(ByteArrayDataSource.UriResolver { uri ->
                        assertEquals(signedUri, uri)
                        audio
                    })
                },
                resolver = ResolvingDataSource.Resolver { dataSpec ->
                    resolutions += dataSpec
                    dataSpec.withUri(signedUri)
                },
            )
            val activePlayer = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
                    .setAudioAttributes(AudioAttributes.DEFAULT, false)
                    .build().also { value ->
                        player = value
                        value.volume = 0f
                        value.addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { errors += error }
                        })
                        value.setMediaItem(MediaItem.Builder().setMediaId(mediaId).setUri(originalUri)
                            .setCustomCacheKey(mediaId).setMimeType(MimeTypes.AUDIO_WAV)
                            .setMediaMetadata(MediaMetadata.Builder().setTitle("日本語の曲名").build()).build())
                        value.prepare()
                        value.play()
                    }
            }
            suspend fun awaitPosition(minimumMs: Long) = withTimeout(15_000) {
                while (true) {
                    assertTrue("Unexpected player errors: $errors", errors.isEmpty())
                    val ready = withContext(Dispatchers.Main) {
                        activePlayer.playbackState == Player.STATE_READY && activePlayer.currentPosition >= minimumMs
                    }
                    if (ready) break
                    delay(25)
                }
            }

            awaitPosition(250)
            assertTrue("The player never reached the hole after its downloaded prefix", resolutions.isNotEmpty())
            assertEquals(prefixLength, resolutions.first().position)
            for (title in listOf("Original English Title", "日本語の曲名")) {
                val before = withContext(Dispatchers.Main) {
                    val position = activePlayer.currentPosition
                    val item = activePlayer.currentMediaItem!!
                    // Same operation as MusicService's display projection subscription.
                    activePlayer.replaceMediaItem(activePlayer.currentMediaItemIndex,
                        item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(title).build()).build())
                    assertTrue(activePlayer.playWhenReady)
                    position
                }
                awaitPosition(before + 250)
                withContext(Dispatchers.Main) {
                    val item = activePlayer.currentMediaItem!!
                    assertEquals(title, item.mediaMetadata.title.toString())
                    assertEquals(mediaId, item.mediaId)
                    assertEquals(originalUri, item.localConfiguration!!.uri)
                    assertEquals(mediaId, item.localConfiguration!!.customCacheKey)
                    assertNull(activePlayer.playerError)
                }
            }

            // Stereo PCM at 48kHz occupies 192,000 bytes/sec; 8 seconds is beyond 512KiB.
            withContext(Dispatchers.Main) { activePlayer.seekTo(8_000) }
            awaitPosition(8_250)
            withContext(Dispatchers.Main) {
                assertTrue(activePlayer.isPlaying)
                assertEquals(mediaId, activePlayer.currentMediaItem!!.mediaId)
                assertNull(activePlayer.playerError)
            }
            assertTrue(errors.isEmpty())
            assertTrue(resolutions.all { it.key == mediaId })
        } finally {
            withContext(Dispatchers.Main) { player?.release() }
            downloadCache.release()
            playerCache.release()
            directory.deleteRecursively()
        }
    }

    private fun silentWav(): ByteArray {
        val sampleRate = 48_000
        val channelCount = 2
        val bytesPerSample = 2
        val byteRate = sampleRate * channelCount * bytesPerSample
        val dataLength = byteRate * 12
        return ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataLength)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channelCount.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channelCount * bytesPerSample).toShort())
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLength)
        }.array()
    }
}
