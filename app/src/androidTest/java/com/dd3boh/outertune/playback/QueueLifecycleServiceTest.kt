package com.dd3boh.outertune.playback

import android.content.Intent
import android.os.SystemClock
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AutoLoadMoreKey
import com.dd3boh.outertune.constants.MaxQueuesKey
import com.dd3boh.outertune.constants.PersistentQueueKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.fixtures.SearchUiFixtureActivity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.scanners.LocalMediaLifecycle
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real service lifecycle with a separate Room database; no existing library rows are purged. */
class QueueLifecycleServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test(timeout = 60_000)
    fun pendingPlaybackCannotRestorePurgedLocalSongsAndImmediateReloadAndStopKeepLatestQueue() {
        val context = instrumentation.targetContext
        val token = UUID.randomUUID().toString()
        val databaseName = "queue-lifecycle-$token.db"
        fun openDatabase() = MusicDatabase(
            Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build(),
        )
        var database = openDatabase()
        val settings = runBlocking(Dispatchers.IO) { context.dataStore.data.first() }
        val onlineA = track("online-a-$token")
        val onlineB = track("online-b-$token")
        val local = track("LS-purge-$token", local = true)
        val delayed = DelayedQueue(local)
        var activity: SearchUiFixtureActivity? = null
        var connection: PlayerConnection? = null
        var ownsService = false
        var shutdownComplete = false
        try {
            runBlocking(Dispatchers.IO) {
                context.dataStore.edit {
                    // Starting an empty board avoids even reading the user's persisted queue.
                    it[PersistentQueueKey] = false
                    it[AutoLoadMoreKey] = false
                    it[MaxQueuesKey] = 19
                }
            }
            val host = instrumentation.startActivitySync(
                Intent(context, SearchUiFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as SearchUiFixtureActivity
            activity = host
            val connected = AtomicReference<PlayerConnection?>()
            onMain {
                ViewModelProvider(host)[MediaControllerViewModel::class.java].also { model ->
                    model.addControllerCallback(host.lifecycle) { _, _ ->
                        connected.compareAndSet(null, PlayerConnection(model, database))
                    }
                    host.lifecycle.addObserver(model)
                }
            }
            awaitCondition("MusicService connection") { connected.get() != null }
            val active = connected.get()!!
            connection = active
            awaitCondition("Initial empty queue") { active.service.qbInit.value }
            onMain {
                assertEquals("Run alone in a fresh app process", 0, active.player.mediaItemCount)
                assertTrue("Queue persistence must be disabled before service startup",
                    active.service.queueBoard.value.masterQueues.isEmpty())
                // Service queue writers resolve this property at write time. Keep it pointing at
                // the fixture until onDestroy finishes, including every delayed/final writer.
                active.service.database = database
                ownsService = true
            }
            runBlocking(Dispatchers.IO) {
                database.insert(local)
                context.dataStore.edit { it[PersistentQueueKey] = true }
                withTimeout(10_000) {
                    withContext(Dispatchers.Main) {
                        // Enable persistence on a newly initialized board in the isolated DB.
                        active.service.initQueue()
                        active.service.queueBoard.value.addQueue(
                            title = "Mixed queue $token",
                            mediaList = listOf(onlineA, local, onlineB),
                            startIndex = 2,
                        )
                        // Same Main turn: the five-second scheduled save cannot have run yet.
                        active.service.initQueue()
                    }
                }
                val immediatelyReloaded = database.readQueue().single()
                assertEquals(listOf(onlineA.id, local.id, onlineB.id), immediatelyReloaded.queue.map { it.id })
                assertEquals(2, immediatelyReloaded.queuePos)
            }

            onMain { active.service.playQueue(delayed, playWhenReady = false) }
            runBlocking(Dispatchers.IO) {
                withTimeout(5_000) { delayed.entered.await() }
                // This executes the actual purge coordinator, including its pre-save and reload.
                withTimeout(10_000) { LocalMediaLifecycle.removeImportedMedia(database, active) }
                assertFalse(database.hasLocalSongs())
                assertNull(database.song(local.id).first())
                val surviving = database.readQueue().single()
                assertEquals(listOf(onlineA.id, onlineB.id), surviving.queue.map { it.id })
                assertEquals(1, surviving.queuePos)
                delayed.release.complete(Unit)
                withTimeout(5_000) { delayed.returned.await() }
                // getInitialStatus returns on IO, then the service continuation is dispatched to
                // Main. Allow that actual continuation to run before inspecting negative results.
                delay(300)
                withContext(Dispatchers.Main) {
                    assertTrue(active.service.qbInit.value)
                    assertEquals(0, active.player.mediaItemCount)
                    assertEquals(listOf(onlineA.id, onlineB.id),
                        active.service.queueBoard.value.masterQueues.single().queue.map { it.id })
                    // A final mutation immediately before service destruction must be committed.
                    active.service.queueBoard.value.addQueue(
                        title = "Final queue $token",
                        mediaList = listOf(onlineB, onlineA),
                        startIndex = 1,
                    )
                }
            }

            onMain {
                active.dispose()
                host.fixtureView.disposeComposition()
                host.finish()
                // Disconnect the service's own notification controller as well as this host.
                // Otherwise stopService leaves a bound service and never reaches onDestroy.
                active.service.sessions.forEach { it.release() }
            }
            activity = null
            context.stopService(Intent(context, MusicService::class.java))
            awaitCondition("Final queue save during service shutdown", timeoutMs = 10_000) {
                // onDestroy and this read both own Main. False is observed only after the
                // destruction callback has returned, including its blocking final DB save.
                onMain { !active.service.qbInit.value }
            }
            shutdownComplete = true
            runBlocking(Dispatchers.IO) {
                assertFalse(database.hasLocalSongs())
                assertEquals(listOf(onlineB.id, onlineA.id), database.readQueue().last().queue.map { it.id })
                assertEquals(1, database.readQueue().last().queuePos)
                database.close()
                database = openDatabase()
                val reopened = database.readQueue()
                assertEquals(2, reopened.size)
                assertEquals(listOf(onlineA.id, onlineB.id), reopened.first().queue.map { it.id })
                assertEquals(listOf(onlineB.id, onlineA.id), reopened.last().queue.map { it.id })
                assertEquals(1, reopened.last().queuePos)
                assertNull(database.song(local.id).first())
            }
        } finally {
            delayed.release.complete(Unit)
            if (ownsService && !shutdownComplete) {
                // Even a failing test keeps all subsequent service writes inside the fixture DB.
                onMain {
                    connection?.let { it.player.pause(); it.player.clearMediaItems(); it.dispose() }
                    activity?.let { it.fixtureView.disposeComposition(); it.finish() }
                    connection?.service?.sessions?.forEach { it.release() }
                }
                context.stopService(Intent(context, MusicService::class.java))
                runCatching {
                    awaitCondition("Failed test service shutdown", timeoutMs = 10_000) {
                        onMain { connection?.service?.qbInit?.value == false }
                    }
                }.onSuccess { shutdownComplete = true }
            } else if (!ownsService) {
                onMain {
                    connection?.dispose()
                    activity?.let { it.fixtureView.disposeComposition(); it.finish() }
                }
            }
            runBlocking(Dispatchers.IO) {
                context.dataStore.edit {
                    it.restore(settings, PersistentQueueKey)
                    it.restore(settings, AutoLoadMoreKey)
                    it.restore(settings, MaxQueuesKey)
                }
            }
            LocalMediaScanner.resumeScannerOperations()
            if (!ownsService || shutdownComplete) {
                database.close()
                context.deleteDatabase(databaseName)
            }
        }
    }

    private class DelayedQueue(private val local: MediaMetadata) : Queue {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        override val preloadItem: MediaMetadata? = null
        override val playlistId: String? = null
        override val startShuffled = false
        override suspend fun getInitialStatus(): Queue.Status {
            entered.complete(Unit)
            release.await()
            returned.complete(Unit)
            return Queue.Status("Deleted local queue", listOf(local), 0)
        }
        override fun hasNextPage() = false
        override suspend fun nextPage(): List<MediaMetadata> = error("No next page")
    }

    private fun track(id: String, local: Boolean = false) = MediaMetadata(
        id = id, title = id, artists = emptyList(), duration = 3, genre = null,
        isLocal = local, localPath = if (local) "/fixture/never-played.wav" else null,
    )

    private fun <T> MutablePreferences.restore(previous: Preferences, key: Preferences.Key<T>) {
        previous[key]?.let { this[key] = it } ?: remove(key)
    }

    private fun awaitCondition(label: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $label" }
            SystemClock.sleep(25)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val value = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { value.set(runCatching(block)) }
        return value.get().getOrThrow()
    }
}
