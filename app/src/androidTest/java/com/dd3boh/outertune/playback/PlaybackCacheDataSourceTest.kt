package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the production cache chain with real span files and a deterministic byte source. */
class PlaybackCacheDataSourceTest {
    private val mediaId = "abcdefghijk"
    private val signedUri = Uri.parse("https://stream.example.test/audio?signature=fresh")
    private val bytes = ByteArray(1024 * 1024 + 63) { ((it * 31 + it / 251) % 256).toByte() }

    @Test
    fun partialDownloadPrefixResolvesTheFollowingCacheMissAndReadsPast512KiB() = withCaches { download, player ->
        assertPrefixReadThrough(download, player, download)
    }

    @Test
    fun partialPlayerPrefixResolvesTheFollowingCacheMissAndReadsPast512KiB() = withCaches { download, player ->
        assertPrefixReadThrough(download, player, player)
    }

    private fun assertPrefixReadThrough(download: Cache, player: Cache, prefixCache: Cache) {
        val prefixLength = 4096
        seed(prefixCache, 0, prefixLength)
        val resolutions = mutableListOf<DataSpec>()
        val networkOpens = mutableListOf<DataSpec>()
        val factory = factory(download, player, resolutions, networkOpens)

        assertArrayEquals(bytes, read(factory, request()))
        assertEquals(1, resolutions.size)
        assertEquals(prefixLength.toLong(), resolutions.single().position)
        assertEquals(C.LENGTH_UNSET.toLong(), resolutions.single().length)
        assertEquals(mediaId, resolutions.single().key)
        assertEquals(signedUri, networkOpens.single().uri)
        assertEquals(prefixLength.toLong(), networkOpens.single().position)
        assertEquals(0L, networkOpens.single().uriPositionOffset)
        assertTrue(player.isCached(mediaId, prefixLength.toLong(), (bytes.size - prefixLength).toLong()))
        if (prefixCache === download) {
            assertFalse(download.isCached(mediaId, prefixLength.toLong(), 1))
        }

        // Reopening the same media ID uses the audio bytes, even though the first source reported
        // a signed URL. The signed URL must never become the cache key or require online access.
        resolutions.clear()
        networkOpens.clear()
        assertArrayEquals(bytes, read(factory, request()))
        assertTrue(resolutions.isEmpty())
        assertTrue(networkOpens.isEmpty())
    }

    @Test
    fun seekingAcrossBothCachesPreservesTheBoundedHolePositionLengthAndRequestHeaders() = withCaches { download, player ->
        seed(download, 0, 1024)
        seed(player, 1024, 1024)
        seed(download, 4096, 4096)
        val resolutions = mutableListOf<DataSpec>()
        val networkOpens = mutableListOf<DataSpec>()
        val request = request().buildUpon()
            .setPosition(512)
            .setLength(6144)
            .setHttpRequestHeaders(mapOf("X-Playback-Test" to "retained"))
            .build()

        val actual = read(factory(download, player, resolutions, networkOpens), request)

        assertArrayEquals(bytes.copyOfRange(512, 6656), actual)
        assertEquals(1, resolutions.size)
        val opened = networkOpens.single()
        assertEquals(2048L, opened.position)
        assertEquals(2048L, opened.length)
        assertEquals(0L, opened.uriPositionOffset)
        assertEquals(mediaId, opened.key)
        assertEquals(request.httpRequestHeaders, opened.httpRequestHeaders)
    }

    @Test
    fun completeAudioAcrossDownloadAndPlayerCachesWorksWithoutAnyUrlResolution() = withCaches { download, player ->
        seed(download, 0, 4096)
        seed(player, 4096, bytes.size - 4096)
        val factory = createPlaybackCacheDataSourceFactory(
            downloadCache = download,
            playerCache = player,
            upstreamFactory = DataSource.Factory {
                object : DataSource by ByteArrayDataSource(bytes) {
                    override fun open(dataSpec: DataSpec): Long = error("Fully cached audio opened a network source")
                }
            },
            resolver = ResolvingDataSource.Resolver { error("Fully cached audio requested a signed URL") },
        )

        assertArrayEquals(bytes, read(factory, request().buildUpon().setLength(bytes.size.toLong()).build()))
    }

    private fun factory(
        download: Cache,
        player: Cache,
        resolutions: MutableList<DataSpec>,
        networkOpens: MutableList<DataSpec>,
    ): DataSource.Factory = createPlaybackCacheDataSourceFactory(
        downloadCache = download,
        playerCache = player,
        upstreamFactory = DataSource.Factory {
            val source = ByteArrayDataSource(bytes)
            object : DataSource by source {
                override fun open(dataSpec: DataSpec): Long {
                    networkOpens += dataSpec
                    assertEquals("An unresolved media ID reached upstream", signedUri, dataSpec.uri)
                    return source.open(dataSpec)
                }
            }
        },
        resolver = ResolvingDataSource.Resolver { dataSpec ->
            resolutions += dataSpec
            dataSpec.withUri(signedUri)
        },
    )

    private fun request(): DataSpec = DataSpec.Builder().setUri(mediaId).setKey(mediaId).build()

    private fun read(factory: DataSource.Factory, request: DataSpec): ByteArray {
        val source = factory.createDataSource()
        try {
            source.open(request)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                assertTrue("A nonempty read made no progress", count > 0)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            source.close()
        }
    }

    private fun seed(cache: Cache, position: Int, length: Int) {
        val hole = cache.startReadWrite(mediaId, position.toLong(), length.toLong())
        try {
            val file = cache.startFile(mediaId, position.toLong(), length.toLong())
            file.writeBytes(bytes.copyOfRange(position, position + length))
            cache.commitFile(file, length.toLong())
        } finally {
            cache.releaseHoleSpan(hole)
        }
    }

    @Suppress("DEPRECATION")
    private fun withCaches(block: (SimpleCache, SimpleCache) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "playback-cache-test-${UUID.randomUUID()}")
        val download = SimpleCache(File(directory, "download"), NoOpCacheEvictor())
        val player = SimpleCache(File(directory, "player"), NoOpCacheEvictor())
        try {
            block(download, player)
        } finally {
            download.release()
            player.release()
            directory.deleteRecursively()
        }
    }
}
