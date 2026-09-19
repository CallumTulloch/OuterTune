package com.dd3boh.outertune.playback

import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.PlayCountEntity
import com.dd3boh.outertune.db.entities.SongEntity
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The production playback-history callback runs on Room's own query executor. A blocking wait
 * for another Room Flow on that executor must not consume the worker needed to finish the read.
 * One query worker makes this starvation deterministic instead of depending on playback timing.
 */
class PlaybackHistoryLivenessTest {
    private val songId = "history-liveness-track"
    private val timestamp = LocalDateTime.of(2026, 9, 19, 22, 0)

    @Test
    fun firstPlaybackHistoryCallbackCompletesAndLibraryRemainsReadable() {
        assertHistoryAndLibraryProgress(initialCount = 0, callbackCount = 1)
    }

    @Test
    fun repeatedPlaybackHistoryCallbacksIncrementExistingCountAndKeepLibraryReadable() {
        assertHistoryAndLibraryProgress(initialCount = 7, callbackCount = 24)
    }

    @Test
    fun concurrentHistoryCallbacksCannotExhaustAFourWorkerQueryPool() {
        assertHistoryAndLibraryProgress(initialCount = 7, callbackCount = 4, queryWorkers = 4)
    }

    private fun assertHistoryAndLibraryProgress(initialCount: Int, callbackCount: Int, queryWorkers: Int = 1) {
        val fixture = Fixture(queryWorkers)
        try {
            val database = fixture.database
            database.insert(SongEntity(
                id = songId,
                title = "History liveness fixture",
                duration = 180,
                localPath = null,
                inLibrary = timestamp,
            ))
            if (initialCount > 0) {
                val now = LocalDateTime.now()
                database.insert(PlayCountEntity(songId, now.year, now.monthValue, initialCount))
            }
            val entered = CountDownLatch(1)
            val firstWave = CountDownLatch(queryWorkers)
            val completed = CountDownLatch(callbackCount)
            val errors = CopyOnWriteArrayList<Throwable>()
            repeat(callbackCount) { index ->
                // Same executor entry point and write order as MusicService.onPlaybackStatsReady.
                database.query {
                    entered.countDown()
                    try {
                        // Occupy each query worker before allowing the first write to begin. This
                        // reproduces pool saturation without relying on device speed or sleeps.
                        if (index < queryWorkers) {
                            firstWave.countDown()
                            check(firstWave.await(5, TimeUnit.SECONDS)) { "History callback wave did not enter" }
                        }
                        incrementPlayCount(songId)
                        insert(Event(songId = songId, timestamp = timestamp.plusSeconds(index.toLong()),
                            playTime = 90_000L))
                    } catch (failure: Throwable) {
                        errors += failure
                    } finally {
                        completed.countDown()
                    }
                }
            }
            if (!entered.await(5, TimeUnit.SECONDS)) {
                fixture.failWithStacks("Playback history callback never entered the query executor")
            }
            // This is an independent library observer, not a query nested inside the callback.
            val library = fixture.probeExecutor.submit<List<String>> {
                runBlocking {
                    database.songsByCreateDateAsc().first().map { it.id }
                }
            }
            if (!completed.await(5, TimeUnit.SECONDS)) {
                fixture.failWithStacks(
                    "Playback history blocked its query executor: " +
                        "completed=${callbackCount - completed.count}/$callbackCount; " +
                        "libraryDone=${library.isDone}",
                )
            }
            if (errors.isNotEmpty()) {
                throw AssertionError("Playback history write failed", errors.first())
            }
            val songs = try {
                library.get(5, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                fixture.failWithStacks("History writes completed but the library observer did not")
            }
            // The liveness read is allowed to run between callbacks. Inspect counts only after
            // every callback has committed, so the four-worker case does not assume a read order.
            val finishedState = fixture.probeExecutor.submit<Snapshot> {
                runBlocking {
                    Snapshot(songs, database.events().first().map { it.event },
                        database.getLifetimePlayCount(songId))
                }
            }
            val snapshot = try {
                finishedState.get(5, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                fixture.failWithStacks("Final playback statistics could not be read after callbacks completed")
            }
            assertEquals(listOf(songId), snapshot.songs)
            assertEquals(initialCount + callbackCount, snapshot.playCount)
            assertEquals(callbackCount, snapshot.events.size)
            assertEquals(setOf(songId), snapshot.events.map { it.songId }.toSet())
            assertEquals(callbackCount * 90_000L, snapshot.events.sumOf { it.playTime })
        } finally {
            fixture.close()
        }
    }

    private data class Snapshot(val songs: List<String>, val events: List<Event>, val playCount: Int)

    private class Fixture(queryWorkers: Int) {
        private val prefix = "HistoryLiveness-${UUID.randomUUID()}"
        private val queryExecutor = executor("query", queryWorkers)
        private val transactionExecutor = executor("transaction")
        val probeExecutor = executor("library-probe")
        private val room = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            InternalDatabase::class.java,
        ).setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .build()
        val database = MusicDatabase(room)

        private fun executor(role: String, workers: Int = 1): ExecutorService {
            val sequence = AtomicInteger()
            return Executors.newFixedThreadPool(workers) { runnable ->
                Thread(runnable, "$prefix-$role-${sequence.incrementAndGet()}").apply { isDaemon = true }
            }
        }

        fun failWithStacks(message: String): Nothing {
            val dump = Thread.getAllStackTraces().entries
                .filter { (worker, _) -> worker.name.startsWith(prefix) }
                .sortedBy { it.key.name }
                .joinToString("\n\n") { (worker, frames) ->
                    "${worker.name} state=${worker.state}\n" +
                        frames.joinToString("\n") { "    at $it" }
                }
            Log.e("PlaybackHistoryLiveness", "$message\n$dump")
            throw AssertionError("$message\n$dump")
        }

        fun close() {
            // Interrupt runBlocking on failure. Do not close Room synchronously while its worker
            // still owns a transaction, which would turn a useful test failure into a hung run.
            val cleanup = thread(name = "$prefix-cleanup", isDaemon = true) {
                probeExecutor.shutdownNow()
                queryExecutor.shutdownNow()
                transactionExecutor.shutdownNow()
                val stopped = listOf(probeExecutor, queryExecutor, transactionExecutor)
                    .map { it.awaitTermination(1, TimeUnit.SECONDS) }.all { it }
                if (stopped) room.close()
            }
            cleanup.join(5_000L)
            if (cleanup.isAlive) {
                Log.e("PlaybackHistoryLiveness", "Timed out closing isolated test fixture")
            }
        }
    }
}
