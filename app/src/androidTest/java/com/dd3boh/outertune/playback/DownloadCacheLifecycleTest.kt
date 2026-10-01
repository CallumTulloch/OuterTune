package com.dd3boh.outertune.playback

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.database.DefaultDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

/**
 * Real Media3 download tasks, persisted index, HTTP Range handling, cache spans and PCM playback.
 * Only the HTTP transport is replaced. All databases/caches belong to this test; app preferences,
 * DownloadUtil's singleton, account state and the user's downloads are never touched.
 */
class DownloadCacheLifecycleTest {
    @Test(timeout = 90_000)
    fun interruptedHttpBodyPersistsFailureAndManualRetryAfterManagerReopenCompletesTheSameBytes() = runBlocking {
        val fixture = Fixture(failFirstBody = true)
        try {
            fixture.startManager()
            fixture.add()
            val failed = fixture.awaitState(Download.STATE_FAILED)
            assertEquals(Download.FAILURE_REASON_UNKNOWN, failed.failureReason)
            assertTrue(fixture.failures.isNotEmpty())
            val prefix = fixture.downloadCache.getCachedBytes(fixture.id, 0, fixture.audio.size.toLong())
            assertTrue("A failed transfer must retain an incomplete prefix", prefix > 0 && prefix < fixture.audio.size)
            assertFalse(fixture.downloadCache.isCached(fixture.id, 0, fixture.audio.size.toLong()))
            assertEquals(1, fixture.http.requests.size)

            fixture.releaseManager()
            val persisted = DefaultDownloadIndex(fixture.provider).getDownload(fixture.id)!!
            assertEquals(Download.STATE_FAILED, persisted.state)
            assertEquals(fixture.id, persisted.request.customCacheKey)
            assertEquals(Uri.parse(fixture.id), persisted.request.uri)
            fixture.startManager()
            assertEquals(Download.STATE_FAILED, fixture.manager!!.downloadIndex.getDownload(fixture.id)!!.state)
            fixture.add()
            fixture.assertComplete()

            assertTrue("Retry must request the missing suffix, preserving the prefix",
                fixture.http.requests.drop(1).any { it.position > 0 })
            assertTrue(fixture.states.containsAll(listOf(Download.STATE_DOWNLOADING, Download.STATE_FAILED, Download.STATE_COMPLETED)))
            fixture.assertFullOfflineRead()
        } finally { fixture.close() }
    }

    @Test(timeout = 90_000)
    fun stoppingAnActiveTransferCancelsItsReaderThenResumesAndRemovalClearsOnlyTheDownloadCache() = runBlocking {
        val fixture = Fixture(blockAfterPrefix = true)
        try {
            fixture.startManager()
            fixture.add()
            await("HTTP reader reached its partial-transfer gate") { fixture.http.prefixReached.count == 0L }
            withContext(Dispatchers.Main) { fixture.manager!!.setStopReason(fixture.id, 7) }
            val stopped = fixture.awaitState(Download.STATE_STOPPED)
            assertEquals(7, stopped.stopReason)
            await("Cancelled reader released and committed its partial span") {
                fixture.http.interruptedReaders.get() > 0 &&
                    fixture.downloadCache.getCachedBytes(fixture.id, 0, fixture.audio.size.toLong()) > 0
            }
            assertFalse(fixture.downloadCache.isCached(fixture.id, 0, fixture.audio.size.toLong()))
            assertTrue("User stop is cancellation, not a failed download", fixture.failures.isEmpty())

            fixture.http.openGates()
            withContext(Dispatchers.Main) { fixture.manager!!.setStopReason(fixture.id, Download.STOP_REASON_NONE) }
            fixture.assertComplete()
            fixture.assertFullOfflineRead()
            assertTrue(fixture.http.requests.drop(1).any { it.position > 0 })
            assertTrue("The production download upstream also caches its bytes for playback",
                fixture.playerCache.isCached(fixture.id, 0, fixture.audio.size.toLong()))

            withContext(Dispatchers.Main) { fixture.manager!!.removeDownload(fixture.id) }
            await("Removal persisted in the index and deleted the download spans") {
                fixture.manager!!.downloadIndex.getDownload(fixture.id) == null &&
                    fixture.downloadCache.getCachedSpans(fixture.id).isEmpty()
            }
            // Download removal must leave the separate streaming cache usable.
            assertTrue(fixture.playerCache.isCached(fixture.id, 0, fixture.audio.size.toLong()))
            fixture.assertFullOfflineRead()
            fixture.releaseManager()
            assertNull(DefaultDownloadIndex(fixture.provider).getDownload(fixture.id))
        } finally { fixture.close() }
    }

    @Test(timeout = 90_000)
    fun simultaneousFirstPlaybackAndDownloadKeepTheMediaIdAndProduceACompleteOfflineDownload() = runBlocking {
        val fixture = Fixture(gateBothInitialReaders = true)
        val errors = CopyOnWriteArrayList<PlaybackException>()
        var player: ExoPlayer? = null
        try {
            fixture.startManager()
            val active = withContext(Dispatchers.Main) {
                ExoPlayer.Builder(fixture.context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(fixture.playbackFactory))
                    .setAudioAttributes(AudioAttributes.DEFAULT, false)
                    .build().also { value ->
                        player = value
                        value.volume = 0f
                        value.addListener(object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) { errors += error }
                        })
                        fixture.manager!!.addDownload(fixture.request)
                        value.setMediaItem(MediaItem.Builder().setMediaId(fixture.id).setUri(fixture.id)
                            .setCustomCacheKey(fixture.id).setMimeType(MimeTypes.AUDIO_WAV).build())
                        value.prepare()
                        value.play()
                    }
            }
            // Neither request can publish a span until both real consumers have opened HTTP.
            // This prevents a sequential, already-cached replay from passing as a concurrency test.
            await("Playback and download both opened the initially empty audio") {
                fixture.http.initialReaders.count == 0L
            }
            assertEquals(0L, fixture.downloadCache.getCachedBytes(fixture.id, 0, fixture.audio.size.toLong()))
            assertEquals(0L, fixture.playerCache.getCachedBytes(fixture.id, 0, fixture.audio.size.toLong()))
            fixture.http.openGates()
            fixture.assertComplete()
            withTimeout(20_000) {
                while (!withContext(Dispatchers.Main) {
                    assertTrue("Unexpected playback errors: $errors", errors.isEmpty())
                    active.isPlaying && active.currentPosition >= 500
                }) delay(25)
            }
            withContext(Dispatchers.Main) {
                assertNull(active.playerError)
                assertEquals(fixture.id, active.currentMediaItem!!.mediaId)
                assertEquals(Uri.parse(fixture.id), active.currentMediaItem!!.localConfiguration!!.uri)
                assertEquals(fixture.id, active.currentMediaItem!!.localConfiguration!!.customCacheKey)
                active.seekTo(8_000)
            }
            withTimeout(15_000) {
                while (!withContext(Dispatchers.Main) {
                    assertTrue("Unexpected errors after seek: $errors", errors.isEmpty())
                    active.isPlaying && active.currentPosition >= 8_150
                }) delay(25)
            }
            assertTrue("Both consumers must use the same resolved fixture source", fixture.http.requests.size >= 2)
            assertTrue(fixture.resolutions.all { it.key == fixture.id })
            assertEquals(setOf(fixture.id), fixture.downloadCache.keys)
            assertTrue(fixture.playerCache.keys.all { it == fixture.id })
            fixture.assertFullOfflineRead()
        } finally {
            fixture.http.openGates()
            withContext(Dispatchers.Main) { player?.release() }
            fixture.close()
        }
    }

    private class Fixture(
        failFirstBody: Boolean = false,
        blockAfterPrefix: Boolean = false,
        gateBothInitialReaders: Boolean = false,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "download-fixture-${UUID.randomUUID()}"
        val audio = pcmWav()
        private val directory = File(context.cacheDir, "download-lifecycle-${UUID.randomUUID()}")
        private val databaseName = "download-lifecycle-${UUID.randomUUID()}.db"
        private val helper = object : SQLiteOpenHelper(context, databaseName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) = Unit
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val provider = DefaultDatabaseProvider(helper)
        val downloadCache = SimpleCache(File(directory, "download"), NoOpCacheEvictor(), provider)
        val playerCache = SimpleCache(File(directory, "player"), NoOpCacheEvictor(), provider)
        val http = FixtureHttp(audio, failFirstBody, blockAfterPrefix, gateBothInitialReaders)
        val resolutions = CopyOnWriteArrayList<DataSpec>()
        val states = CopyOnWriteArrayList<Int>()
        val failures = CopyOnWriteArrayList<Exception>()
        private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .addInterceptor { chain -> http.response(chain.request()) }.build()
        private val upstream = OkHttpDataSource.Factory(client)
        private val resolver = ResolvingDataSource.Resolver { spec ->
            check(spec.key == id) { "Lost media ID before HTTP resolution" }
            resolutions += spec
            spec.withUri(Uri.parse("https://download.fixture.invalid/audio.wav"))
        }
        private val downloadUpstream = createResolvingPlayerCacheDataSourceFactory(playerCache, upstream, resolver)
        val playbackFactory = createPlaybackCacheDataSourceFactory(downloadCache, playerCache, upstream, resolver)
        val request = DownloadRequest.Builder(id, Uri.parse(id)).setCustomCacheKey(id)
            .setMimeType(MimeTypes.AUDIO_WAV).build()
        var manager: DownloadManager? = null
            private set

        suspend fun startManager() = withContext(Dispatchers.Main) {
            check(manager == null)
            manager = DownloadManager(context, provider, downloadCache, downloadUpstream, Executor(Runnable::run)).apply {
                minRetryCount = 0
                maxParallelDownloads = 1
                requirements = Requirements(0) // Interceptor transport has no external connectivity requirement.
                addListener(object : DownloadManager.Listener {
                    override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                        finalException?.let { failures += it }
                        states += download.state
                    }
                })
                resumeDownloads()
            }
        }

        suspend fun add() = withContext(Dispatchers.Main) { manager!!.addDownload(request) }

        suspend fun awaitState(state: Int): Download {
            await("Download index state $state and listener callback") {
                manager!!.downloadIndex.getDownload(id)?.state == state && states.contains(state)
            }
            return manager!!.downloadIndex.getDownload(id)!!
        }

        suspend fun assertComplete() {
            val completed = awaitState(Download.STATE_COMPLETED)
            assertEquals(Download.FAILURE_REASON_NONE, completed.failureReason)
            assertEquals(Download.STOP_REASON_NONE, completed.stopReason)
            assertEquals(audio.size.toLong(), completed.contentLength)
            assertEquals(audio.size.toLong(), completed.bytesDownloaded)
            assertEquals(id, completed.request.customCacheKey)
            assertTrue(downloadCache.isCached(id, 0, audio.size.toLong()))
            assertEquals(audio.size.toLong(), downloadCache.getCachedBytes(id, 0, audio.size.toLong()))
            assertTrue(resolutions.all { it.key == id })
        }

        fun assertFullOfflineRead() {
            val callsBefore = http.requests.size
            val upstreamOpens = AtomicInteger()
            val upstreamReads = AtomicInteger()
            val urlResolutions = AtomicInteger()
            val offline = createPlaybackCacheDataSourceFactory(downloadCache, playerCache,
                DataSource.Factory {
                    // CacheDataSource eagerly creates its upstream even when all bytes are cached.
                    // Creating the object is harmless; only actually opening/reading it is forbidden.
                    object : DataSource by ByteArrayDataSource(byteArrayOf(0)) {
                        override fun open(dataSpec: DataSpec): Long {
                            upstreamOpens.incrementAndGet()
                            error("Completed cache unexpectedly opened HTTP")
                        }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            upstreamReads.incrementAndGet()
                            error("Completed cache unexpectedly read HTTP")
                        }
                    }
                },
                ResolvingDataSource.Resolver {
                    urlResolutions.incrementAndGet()
                    error("Completed cache unexpectedly resolved a URL")
                })
            val source = offline.createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(id).setKey(id).setLength(audio.size.toLong()).build())
                val output = ByteArrayOutputStream(audio.size)
                val buffer = ByteArray(8192)
                while (true) {
                    val size = source.read(buffer, 0, buffer.size)
                    if (size == C.RESULT_END_OF_INPUT) break
                    assertTrue("Cached read made no progress", size > 0)
                    output.write(buffer, 0, size)
                }
                assertArrayEquals(audio, output.toByteArray())
            } finally { source.close() }
            assertEquals(callsBefore, http.requests.size)
            assertEquals(0, upstreamOpens.get())
            assertEquals(0, upstreamReads.get())
            assertEquals(0, urlResolutions.get())
        }

        suspend fun releaseManager() = withContext(Dispatchers.Main) {
            manager?.release()
            manager = null
        }

        suspend fun close() {
            http.openGates()
            try { releaseManager() } finally {
                // DownloadManager.release persists/cancels tasks but does not join their threads.
                // Never tear down a cache while a cancelled fixture reader still owns a span.
                try {
                    await("HTTP responses closed before releasing the caches") { http.openBodies.get() == 0 }
                } finally {
                    try { downloadCache.release() } finally {
                        try { playerCache.release() } finally {
                            helper.close()
                            context.deleteDatabase(databaseName)
                            directory.deleteRecursively()
                            client.connectionPool.evictAll()
                            client.dispatcher.executorService.shutdown()
                        }
                    }
                }
            }
        }
    }

    private data class HttpRead(val position: Long, val length: Long)

    /** Application interceptor: no sockets, DNS, TLS, external account or server is involved. */
    private class FixtureHttp(
        private val audio: ByteArray,
        failFirstBody: Boolean,
        blockAfterPrefix: Boolean,
        private val gateBothInitialReaders: Boolean,
    ) {
        val requests = CopyOnWriteArrayList<HttpRead>()
        val prefixReached = CountDownLatch(1)
        val initialReaders = CountDownLatch(2)
        val interruptedReaders = AtomicInteger()
        val openBodies = AtomicInteger()
        private val failNext = AtomicBoolean(failFirstBody)
        private val blockNext = AtomicBoolean(blockAfterPrefix)
        private val prefixGate = CountDownLatch(1)
        private val initialGate = CountDownLatch(1)

        fun openGates() { prefixGate.countDown(); initialGate.countDown() }

        fun response(request: Request): Response {
            check(request.url.host == "download.fixture.invalid")
            val range = request.header("Range")?.let { Regex("bytes=(\\d+)-(\\d*)").matchEntire(it)!! }
            val start = range?.groupValues?.get(1)?.toInt() ?: 0
            val end = range?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: audio.lastIndex
            check(start in audio.indices && end in start..audio.lastIndex)
            val length = end - start + 1
            requests += HttpRead(start.toLong(), length.toLong())
            val shouldFail = failNext.getAndSet(false)
            val shouldBlock = blockNext.getAndSet(false)
            openBodies.incrementAndGet()
            val body = object : ResponseBody() {
                private val stream = object : Source {
                    private var position = start
                    private var initialRead = true
                    private var waitedForPrefix = false
                    private var closed = false
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        check(!closed)
                        if (byteCount == 0L) return 0
                        if (initialRead) {
                            initialRead = false
                            initialReaders.countDown()
                            if (gateBothInitialReaders) waitFor(initialGate)
                        }
                        if (position - start >= PREFIX_LENGTH) {
                            if (shouldFail) throw IOException("Intentional fixture connection loss after partial audio")
                            if (shouldBlock && !waitedForPrefix) {
                                waitedForPrefix = true
                                prefixReached.countDown()
                                waitFor(prefixGate)
                            }
                        }
                        if (position > end) return -1
                        val count = minOf(byteCount, 16_384L, (end - position + 1).toLong()).toInt()
                        sink.write(audio, position, count)
                        position += count
                        return count.toLong()
                    }
                    override fun timeout() = Timeout.NONE
                    override fun close() {
                        if (!closed) { closed = true; openBodies.decrementAndGet() }
                    }
                }.buffer()
                override fun contentType(): MediaType = "audio/wav".toMediaType()
                override fun contentLength(): Long = length.toLong()
                override fun source(): BufferedSource = stream
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (range == null) 200 else 206).message("Local audio fixture")
                .header("Content-Type", "audio/wav").header("Content-Length", length.toString())
                .header("Accept-Ranges", "bytes").apply {
                    if (range != null) header("Content-Range", "bytes $start-$end/${audio.size}")
                }.body(body).build()
        }

        private fun waitFor(gate: CountDownLatch) {
            try {
                if (!gate.await(20, TimeUnit.SECONDS)) throw IOException("Fixture reader gate timed out")
            } catch (interrupted: InterruptedException) {
                interruptedReaders.incrementAndGet()
                Thread.currentThread().interrupt()
                throw IOException("Fixture reader cancelled", interrupted)
            }
        }
    }

    companion object {
        private const val PREFIX_LENGTH = 65_536

        private suspend fun await(label: String, condition: () -> Boolean) {
            try {
                withTimeout(20_000) { while (!condition()) delay(20) }
            } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Timed out waiting for $label", timeout)
            }
        }

        private fun pcmWav(): ByteArray {
            val sampleRate = 48_000
            val byteRate = sampleRate * 2 * 2
            val dataLength = byteRate * 12
            return ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataLength); put("WAVEfmt ".toByteArray())
                putInt(16); putShort(1); putShort(2); putInt(sampleRate); putInt(byteRate)
                putShort(4); putShort(16); put("data".toByteArray()); putInt(dataLength)
                // Distinct nonzero samples detect repeated/missing Range bytes, rather than
                // accepting an all-zero suffix that accidentally has the right length.
                repeat(dataLength / 2) { sample -> putShort(((sample * 31 + sample / 251) % 4096).toShort()) }
            }.array()
        }
    }
}
