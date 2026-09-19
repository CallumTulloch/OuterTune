package com.dd3boh.outertune.playback

import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.models.HybridCacheDataSinkFactory
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Reuse the actual sink across cache-policy changes, with real span files and no network. */
class HybridCacheDataSinkTest {
    private val first = ByteArray(31) { it.toByte() }
    private val last = ByteArray(43) { (255 - it).toByte() }

    @Test
    fun sameSinkCachesThenSkipsThenCachesWithoutWritingToTheClosedDelegate() = withCache { cache ->
        var enabled = true
        val sink = HybridCacheDataSinkFactory(cache) { enabled }.createDataSink()
        sink.close() // Closing before open and repeated close must be harmless.
        write(cache, sink, "first", first)
        sink.close()
        assertCached(cache, "first", first)

        enabled = false
        write(cache, sink, "skipped", last)
        sink.close()
        assertTrue(cache.getCachedSpans("skipped").isEmpty())
        assertCached(cache, "first", first)

        enabled = true
        write(cache, sink, "last", last)
        sink.close()
        assertCached(cache, "last", last)
        assertCached(cache, "first", first)
    }

    @Test
    fun closeAfterFailedOpenAllowsSkippedAndCachedRequestsToReuseTheSink() = withCache { cache ->
        var failOpen = true
        var enabled = true
        val failing = object : Cache by cache {
            override fun startFile(key: String, position: Long, length: Long): File {
                if (failOpen) throw Cache.CacheException("Intentional cache open failure")
                return cache.startFile(key, position, length)
            }
        }
        val sink = HybridCacheDataSinkFactory(failing) { enabled }.createDataSink()
        expectIoFailure { write(cache, sink, "failed-open", first) }
        // write() closes failed opens and releases the caller-owned hole span.
        sink.close()
        assertTrue(cache.getCachedSpans("failed-open").isEmpty())

        enabled = false
        write(cache, sink, "skipped-after-open-failure", last)
        assertTrue(cache.getCachedSpans("skipped-after-open-failure").isEmpty())

        failOpen = false
        enabled = true
        write(cache, sink, "recovered-open", last)
        assertCached(cache, "recovered-open", last)
    }

    @Test
    fun failedCloseDoesNotLeaveAClosedDelegateInTheNextSkippedRequest() = withCache { cache ->
        var failCommit = true
        var enabled = true
        val failing = object : Cache by cache {
            override fun commitFile(file: File, length: Long) {
                if (failCommit) throw Cache.CacheException("Intentional cache commit failure")
                cache.commitFile(file, length)
            }
        }
        val sink = HybridCacheDataSinkFactory(failing) { enabled }.createDataSink()
        expectIoFailure { write(cache, sink, "failed-close", first) }
        sink.close()

        enabled = false
        write(cache, sink, "skipped-after-close-failure", last)
        assertTrue(cache.getCachedSpans("skipped-after-close-failure").isEmpty())

        failCommit = false
        enabled = true
        write(cache, sink, "recovered-close", last)
        assertCached(cache, "recovered-close", last)
    }

    private fun write(cache: Cache, sink: DataSink, key: String, bytes: ByteArray) {
        val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
        try {
            try {
                sink.open(DataSpec.Builder().setUri("https://example.invalid/$key")
                    .setKey(key).setLength(bytes.size.toLong()).build())
                sink.write(bytes, 0, bytes.size)
            } finally {
                sink.close()
            }
        } finally {
            cache.releaseHoleSpan(hole)
        }
    }

    private fun assertCached(cache: Cache, key: String, expected: ByteArray) {
        val span = cache.getCachedSpans(key).single()
        assertEquals(0L, span.position)
        assertEquals(expected.size.toLong(), span.length)
        assertArrayEquals(expected, requireNotNull(span.file).readBytes())
    }

    private fun expectIoFailure(block: () -> Unit) {
        try {
            block()
            fail("The injected cache failure must reach the caller")
        } catch (_: IOException) {
            // Expected: cleanup and subsequent reuse are asserted by the caller.
        }
    }

    @Suppress("DEPRECATION")
    private fun withCache(block: (SimpleCache) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "hybrid-cache-sink-test-${UUID.randomUUID()}")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        try {
            block(cache)
        } finally {
            cache.release()
            directory.deleteRecursively()
        }
    }
}
