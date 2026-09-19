package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class QueueSaveSchedulerTest {
    @Test fun `burst retains latest state and dirty songs for every current queue`() = fixture { f ->
        val first = queue(1)
        val second = queue(2)
        f.scheduler.request(listOf(first, second), setOf(first.id))
        f.scheduler.request(listOf(first, second), setOf(second.id))
        repeat(1_000) { index ->
            second.lastSongPos = index.toLong()
            f.scheduler.request(listOf(first, second))
        }
        f.run()
        assertTrue(f.writes.isEmpty())
        assertEquals(2, f.scheduler.pendingQueueCount)
        assertEquals(2, f.scheduler.pendingSongQueueCount)
        f.flush()
        assertEquals(1, f.writes.size)
        assertEquals(setOf(1L, 2L), f.writes.single().songQueueIds)
        assertEquals(999L, f.writes.single().queues.last().lastSongPos)
        assertEquals(0, f.scheduler.pendingQueueCount)
    }

    @Test fun `queued snapshot never reads later song shuffle or queue mutations`() = fixture { f ->
        val queue = queue(1)
        queue.queue.single().shuffleIndex = 3
        f.scheduler.request(listOf(queue), setOf(queue.id))
        queue.queue.single().shuffleIndex = 9
        queue.title = "later title"
        queue.queue.clear()
        f.flush()
        val saved = f.writes.single().queues.single()
        assertEquals("Queue 1", saved.title)
        assertEquals(3, saved.queue.single().shuffleIndex)
    }

    @Test fun `position updates retain enriched dirty songs without reading or enriching songs again`() = fixture { f ->
        val queue = queue(1)
        var enrichments = 0
        f.scheduler.request(listOf(queue), setOf(1L)) {
            enrichments++
            it.copy(title = "Resolved song")
        }
        queue.queue.single().shuffleIndex = 99
        queue.lastSongPos = 42
        f.scheduler.request(listOf(queue), enrich = { error("Position update must not inspect songs") })
        f.flush()
        val saved = f.writes.single().queues.single()
        assertEquals(1, enrichments)
        assertEquals("Resolved song", saved.queue.single().title)
        assertEquals(-1, saved.queue.single().shuffleIndex)
        assertEquals(42L, saved.lastSongPos)
    }

    @Test fun `a later song edit replaces the earlier dirty snapshot before a position update`() = fixture { f ->
        val queue = queue(1)
        f.scheduler.request(listOf(queue), setOf(1L))
        queue.queue += MediaMetadata("new-song", "New", emptyList(), duration = 60, genre = null)
        queue.queue.first().shuffleIndex = 1
        queue.queue.last().shuffleIndex = 0
        f.scheduler.request(listOf(queue), setOf(1L))
        queue.lastSongPos = 123
        f.scheduler.request(listOf(queue))
        f.flush()
        val saved = f.writes.single().queues.single()
        assertEquals(listOf("song1", "new-song"), saved.queue.map { it.id })
        assertEquals(listOf(1, 0), saved.queue.map { it.shuffleIndex })
        assertEquals(123L, saved.lastSongPos)
    }

    @Test fun `deletion and reorder prune dirty IDs without losing surviving edits`() = fixture { f ->
        val first = queue(1)
        val second = queue(2)
        val third = queue(3)
        f.scheduler.request(listOf(first, second, third), setOf(1L, 2L, 3L))
        f.scheduler.request(listOf(third, first))
        f.flush()
        val saved = f.writes.single()
        assertEquals(listOf(3L, 1L), saved.queues.map { it.id })
        assertEquals(listOf(0, 1), saved.queues.map { it.index })
        assertEquals(setOf(1L, 3L), saved.songQueueIds)
        f.scheduler.request(emptyList())
        f.flush()
        assertTrue(f.writes.last().queues.isEmpty())
        assertTrue(f.writes.last().songQueueIds.isEmpty())
    }

    @Test fun `updates during a write wait and form one subsequent batch`() = fixture { f ->
        val firstWrite = CompletableDeferred<Unit>()
        f.writeHook = { if (f.writes.size == 1) firstWrite.await() }
        val queue = queue(1)
        f.scheduler.request(listOf(queue), setOf(1L))
        f.flush()
        repeat(100) { index ->
            queue.lastSongPos = index.toLong()
            f.scheduler.request(listOf(queue), if (index == 0) setOf(1L) else emptySet())
        }
        f.run()
        assertEquals(1, f.writes.size)
        assertEquals(1, f.maxConcurrentWrites)
        firstWrite.complete(Unit)
        f.run()
        f.flush()
        assertEquals(2, f.writes.size)
        assertEquals(99L, f.writes.last().queues.single().lastSongPos)
        assertEquals(setOf(1L), f.writes.last().songQueueIds)
        assertEquals(1, f.maxConcurrentWrites)
    }

    @Test fun `failed save keeps dirty songs but retries the latest surviving queues`() = fixture { f ->
        val release = CompletableDeferred<Unit>()
        f.writeHook = {
            if (f.writes.size == 1) {
                release.await()
                error("temporary storage failure")
            }
        }
        val first = queue(1)
        val second = queue(2)
        f.scheduler.request(listOf(first, second), setOf(1L, 2L))
        f.flush()
        second.lastSongPos = 42
        f.scheduler.request(listOf(second))
        release.complete(Unit)
        f.run()
        assertEquals(1, f.failures.size)
        f.flush()
        val retry = f.writes.last()
        assertEquals(listOf(2L), retry.queues.map { it.id })
        assertEquals(42L, retry.queues.single().lastSongPos)
        assertEquals(setOf(2L), retry.songQueueIds)
        assertEquals("song2", retry.queues.single().queue.single().id)
    }

    @Test fun `shutdown cancels delayed writes and rejects later requests`() = fixture { f ->
        f.scheduler.request(listOf(queue(1)), setOf(1L))
        f.run()
        f.scheduler.shutdown()
        f.scheduler.request(listOf(queue(2)), setOf(2L))
        var stopped = false
        f.scope.launch { f.scheduler.awaitShutdown(); stopped = true }
        f.run()
        assertTrue(stopped)
        assertTrue(f.writes.isEmpty())
        assertEquals(0, f.scheduler.pendingQueueCount)
    }

    @Test fun `awaitShutdown waits for an already running transaction to finish`() = fixture { f ->
        val transactionFinished = CompletableDeferred<Unit>()
        f.writeHook = { withContext(NonCancellable) { transactionFinished.await() } }
        f.scheduler.request(listOf(queue(1)), setOf(1L))
        f.flush()
        f.scheduler.request(listOf(queue(2)), setOf(2L))
        f.scheduler.shutdown()
        var stopped = false
        f.scope.launch { f.scheduler.awaitShutdown(); stopped = true }
        f.run()
        assertFalse(stopped)
        transactionFinished.complete(Unit)
        f.run()
        assertTrue(stopped)
        assertEquals(1, f.writes.size)
        assertEquals(0, f.scheduler.pendingQueueCount)
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally {
            fixture.scheduler.shutdown()
            fixture.scope.cancel()
            fixture.run()
        }
    }

    private class Fixture {
        private val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val windows = ArrayDeque<CompletableDeferred<Unit>>()
        val writes = mutableListOf<QueueSaveBatch>()
        val failures = mutableListOf<Exception>()
        var writeHook: suspend (QueueSaveBatch) -> Unit = {}
        var concurrentWrites = 0
        var maxConcurrentWrites = 0
        val scheduler = QueueSaveScheduler(
            dispatcher = dispatcher,
            coalesce = { CompletableDeferred<Unit>().also(windows::addLast).await() },
            reportFailure = failures::add,
        ) { batch ->
            concurrentWrites++
            maxConcurrentWrites = maxOf(maxConcurrentWrites, concurrentWrites)
            writes += batch
            try { writeHook(batch) } finally { concurrentWrites-- }
        }
        fun run() = dispatcher.runCurrent()
        fun flush() {
            run()
            windows.removeFirst().complete(Unit)
            run()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    companion object {
        private fun queue(id: Long) = MultiQueueObject(id, "Queue $id", mutableListOf(
            MediaMetadata("song$id", "Song $id", emptyList(), duration = 60, genre = null)),
            queuePos = 0, index = 0)
    }
}
