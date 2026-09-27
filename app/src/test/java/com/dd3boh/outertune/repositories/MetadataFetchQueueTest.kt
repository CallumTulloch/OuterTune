package com.dd3boh.outertune.repositories

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFetchQueueTest {
    @Test fun `opened album passes a large queued library without discarding it`() = runBlocking {
        val queue = MetadataFetchQueue<String>()
        repeat(2_000) { queue.offer("library-$it") }
        queue.offer("opened-album", foreground = true)
        assertEquals(listOf("opened-album"), queue.takeBatch())
        queue.close()
        val retained = mutableListOf<String>()
        while (true) retained += queue.takeBatch(canBatch = { _, _ -> true }) ?: break
        assertEquals((0 until 2_000).map { "library-$it" }, retained)
    }

    @Test fun `promotion deduplicates queued requests and never duplicates in flight work`() = runBlocking {
        val queue = MetadataFetchQueue<String>()
        queue.offer("older")
        queue.offer("album")
        assertTrue(queue.promote("album"))
        queue.offer("album") // Background refresh must not demote an opened page.
        queue.offer("album", foreground = true)
        assertEquals(listOf("album"), queue.takeBatch())
        assertFalse(queue.promote("album")) // The worker now owns it.
        queue.close()
        assertEquals(listOf("older"), queue.takeBatch())
        assertNull(queue.takeBatch())
    }

    @Test fun `foreground offer promotes an existing background request`() = runBlocking {
        val queue = MetadataFetchQueue<String>()
        queue.offer("older")
        queue.offer("album")
        queue.offer("album", foreground = true)
        queue.close()
        assertEquals(listOf("album"), queue.takeBatch())
        assertEquals(listOf("older"), queue.takeBatch())
        assertNull(queue.takeBatch())
    }

    @Test fun `switching albums demotes the old page promotes the new page and retains playback priority`() = runBlocking {
        val queue = MetadataFetchQueue<String>()
        queue.offer("library-0")
        queue.offer("album-a-0", foreground = true)
        queue.offer("playing", foreground = true)
        queue.offer("album-a-1", foreground = true)
        queue.offer("album-b-0")
        queue.offer("library-1")
        queue.offer("album-b-1")

        queue.updatePriority { it.startsWith("album-b-") || it == "playing" }
        queue.close()
        val order = (0 until 7).map { queue.takeBatch(maxSize = 1)!!.single() }
        assertEquals(listOf("playing", "album-b-0", "album-b-1", "library-0", "library-1", "album-a-0", "album-a-1"), order)
        assertEquals(order.size, order.toSet().size)
        assertNull(queue.takeBatch())
    }

    @Test fun `repeated navigation cannot reset background fairness`() = runBlocking {
        val queue = MetadataFetchQueue<String>(foregroundBurst = 2)
        queue.offer("library")
        queue.offer("album-a-0", foreground = true)
        queue.offer("album-a-1", foreground = true)
        queue.offer("album-b")
        assertEquals(listOf("album-a-0"), queue.takeBatch())
        queue.updatePriority { it == "album-b" }
        assertEquals(listOf("album-b"), queue.takeBatch())
        queue.offer("album-c")
        queue.updatePriority { it == "album-c" }
        queue.close()
        assertEquals(listOf("library"), queue.takeBatch())
        assertEquals(listOf("album-c"), queue.takeBatch())
        assertEquals(listOf("album-a-1"), queue.takeBatch())
        assertNull(queue.takeBatch())
    }

    @Test fun `failed priority update leaves both queues intact`() = runBlocking {
        val queue = MetadataFetchQueue<String>()
        queue.offer("foreground", foreground = true)
        queue.offer("background")
        val result = runCatching {
            queue.updatePriority { item ->
                if (item == "background") error("Invalid priority snapshot")
                false
            }
        }
        assertTrue(result.isFailure)
        queue.close()
        assertEquals(listOf("foreground"), queue.takeBatch())
        assertEquals(listOf("background"), queue.takeBatch())
        assertNull(queue.takeBatch())
    }

    @Test fun `sustained foreground work still advances the library`() = runBlocking {
        val queue = MetadataFetchQueue<String>(foregroundBurst = 3)
        repeat(2) { queue.offer("library-$it") }
        repeat(6) { queue.offer("page-$it", foreground = true) }
        queue.close()
        val order = (0 until 8).map { queue.takeBatch(maxSize = 1)!!.single() }
        assertEquals(listOf("page-0", "page-1", "page-2", "library-0",
            "page-3", "page-4", "page-5", "library-1"), order)
        assertNull(queue.takeBatch())
    }

    @Test fun `batching preserves incompatible requests and keeps background out of foreground batches`() = runBlocking {
        data class Request(val id: String, val language: String)
        val queue = MetadataFetchQueue<Request>()
        queue.offer(Request("background", "en"))
        queue.offer(Request("one", "en"), foreground = true)
        queue.offer(Request("one", "ja"), foreground = true)
        queue.offer(Request("two", "en"), foreground = true)
        queue.offer(Request("two", "ja"), foreground = true)
        queue.close()
        val compatible: (Request, Request) -> Boolean = { first, next -> first.language == next.language }
        assertEquals(listOf(Request("one", "en"), Request("two", "en")), queue.takeBatch(canBatch = compatible))
        assertEquals(listOf(Request("one", "ja"), Request("two", "ja")), queue.takeBatch(canBatch = compatible))
        assertEquals(listOf(Request("background", "en")), queue.takeBatch(canBatch = compatible))
        assertNull(queue.takeBatch())
    }

    @Test fun `lookahead and batch limits bound work without losing unmatched entries`() = runBlocking {
        val queue = MetadataFetchQueue<Int>()
        repeat(10) { queue.offer(it) }
        queue.close()
        assertEquals(listOf(0, 2), queue.takeBatch(maxSize = 3, scanLimit = 4) { first, next -> first % 2 == next % 2 })
        assertEquals(listOf(1, 3, 4), queue.takeBatch(maxSize = 3, scanLimit = 4) { _, _ -> true })
        assertEquals(listOf(5, 6, 7, 8, 9), queue.takeBatch { _, _ -> true })
        assertNull(queue.takeBatch())
    }

    @Test fun `cancelled waiter leaves offered work available to another worker`() = runBlocking {
        withTimeout(5_000) {
            val queue = MetadataFetchQueue<String>()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { queue.takeBatch() }
            queue.offer("album")
            cancelled.cancelAndJoin() // Cancel before the offered-work wakeup resumes this waiter.
            assertEquals(listOf("album"), queue.takeBatch())
            queue.close()
            assertNull(queue.takeBatch())
        }
    }

    @Test fun `close wakes all waiting workers and rejects new requests`() = runBlocking {
        withTimeout(5_000) {
            val queue = MetadataFetchQueue<String>()
            val workers = List(3) { async(start = CoroutineStart.UNDISPATCHED) { queue.takeBatch() } }
            queue.close()
            assertEquals(listOf(null, null, null), workers.awaitAll())
            assertFalse(queue.offer("late"))
            queue.close()
        }
    }

    @Test fun `three workers claim every accepted request once across concurrent offers`() = runBlocking {
        withTimeout(5_000) {
            val queue = MetadataFetchQueue<Int>()
            val workers = List(3) {
                async(Dispatchers.Default) {
                    val completed = mutableListOf<Int>()
                    while (true) {
                        completed += queue.takeBatch(maxSize = 7) { _, _ -> true } ?: break
                        yield()
                    }
                    completed
                }
            }
            repeat(1_000) { queue.offer(it, foreground = it % 4 == 0) }
            queue.close()
            val completed = workers.awaitAll().flatten()
            assertEquals(1_000, completed.size)
            assertEquals((0 until 1_000).toSet(), completed.toSet())
        }
    }
}
