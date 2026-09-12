package com.dd3boh.outertune.utils.potoken

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class PoTokenSessionCacheTest {
    @Test
    fun timedOutInitializationReleasesTheLockForTheNextPlaybackAttempt() = runBlocking {
        var created = 0
        val cache = PoTokenSessionCache(create = {
            ++created
            if (created == 1) awaitCancellation()
            created
        }, expired = { false }, generate = { generator: Int, id -> "$generator:$id" }, close = {})
        assertNull(withTimeoutOrNull(50) { cache.get("video", "session", 1) })
        val recovered = withTimeout(3000) { cache.get("video", "session", 1) }
        assertEquals("2:video", recovered.playerRequestPoToken)
        assertEquals("2:session", recovered.streamingDataPoToken)
    }

    @Test
    fun factoryFailureAfterLoginCannotReuseClosedPreviousGenerator() = runBlocking {
        var created = 0
        val closed = mutableListOf<Int>()
        val cache = PoTokenSessionCache(create = {
            ++created
            if (created == 2) error("fixture factory failure")
            created
        }, expired = { false }, generate = { generator: Int, id -> "$generator:$id" }, close = { closed += it })
        assertEquals("1:session", cache.get("video", "session", 1).streamingDataPoToken)
        assertTrue(runCatching { cache.get("video", "session", 3) }.isFailure)
        assertEquals(listOf(1), closed)
        val recovered = cache.get("video", "session", 3)
        assertEquals("3:session", recovered.streamingDataPoToken)
        assertEquals("3:video", recovered.playerRequestPoToken)
    }

    @Test
    fun streamingFailureClosesCandidateAndCannotPublishOldStreamingToken() = runBlocking {
        var created = 0
        val closed = mutableListOf<Int>()
        val cache = PoTokenSessionCache(create = { ++created }, expired = { false },
            generate = { generator: Int, id ->
                if (generator == 2 && id == "session") error("fixture streaming failure")
                "$generator:$id"
            }, close = { closed += it })
        cache.get("video", "session", 1)
        assertTrue(runCatching { cache.get("video", "session", 3) }.isFailure)
        assertEquals(listOf(1, 2), closed)
        val recovered = cache.get("video", "session", 3)
        assertEquals("3:session", recovered.streamingDataPoToken)
        assertEquals("3:video", recovered.playerRequestPoToken)
    }

    @Test
    fun closeFailureStillEvictsTheOldGeneratorBeforeNextManualRetry() = runBlocking {
        var created = 0
        val cache = PoTokenSessionCache(create = { ++created }, expired = { false },
            generate = { generator: Int, id -> "$generator:$id" }, close = { generator ->
                if (generator == 1) error("fixture close failure")
            })
        cache.get("video", "session", 1)
        assertTrue(runCatching { cache.get("video", "session", 3) }.isFailure)
        assertEquals("2:video", cache.get("video", "session", 3).playerRequestPoToken)
    }

    @Test
    fun anotherSessionWaitsUntilTheCurrentPlayerTokenFinishesBeforeClosing() = runBlocking {
        var created = 0
        val closed = mutableListOf<Int>()
        val release = CompletableDeferred<Unit>()
        val cache = PoTokenSessionCache(create = { ++created }, expired = { false },
            generate = { generator: Int, id ->
                if (id == "held-video") release.await()
                assertFalse(closed.contains(generator))
                "$generator:$id"
            }, close = { closed += it })
        cache.get("warm-video", "session", 1)
        val old = async(start = CoroutineStart.UNDISPATCHED) { cache.get("held-video", "session", 1) }
        val fresh = async(start = CoroutineStart.UNDISPATCHED) { cache.get("fresh-video", "session", 3) }
        assertFalse(fresh.isCompleted)
        assertTrue(closed.isEmpty())
        release.complete(Unit)
        withTimeout(3000) {
            assertEquals("1:held-video", old.await().playerRequestPoToken)
            assertEquals("2:fresh-video", fresh.await().playerRequestPoToken)
        }
        assertEquals(listOf(1), closed)
    }

    @Test
    fun cancelledPlayerRequestClosesGeneratorAndDoesNotRetryInsideCancelledJob() = runBlocking {
        var created = 0
        val closed = mutableListOf<Int>()
        val never = CompletableDeferred<Unit>()
        val cache = PoTokenSessionCache(create = { ++created }, expired = { false },
            generate = { generator: Int, id ->
                if (id == "cancelled-video") never.await()
                "$generator:$id"
            }, close = { closed += it })
        val request = async(start = CoroutineStart.UNDISPATCHED) { cache.get("cancelled-video", "session", 1) }
        request.cancelAndJoin()
        assertEquals(listOf(1), closed)
        assertEquals(1, created)
        assertEquals("2:video", cache.get("video", "session", 1).playerRequestPoToken)
    }

    @Test
    fun warmFailureRecreatesOnceWithoutRecursiveLocking() = runBlocking {
        var created = 0
        val closed = mutableListOf<Int>()
        val cache = PoTokenSessionCache(create = { ++created }, expired = { false },
            generate = { generator: Int, id ->
                if (generator == 1 && id == "retry-video") error("fixture lost WebView")
                "$generator:$id"
            }, close = { closed += it })
        cache.get("warm-video", "session", 1)
        val recovered = withTimeout(3000) { cache.get("retry-video", "session", 1) }
        assertEquals("2:session", recovered.streamingDataPoToken)
        assertEquals("2:retry-video", recovered.playerRequestPoToken)
        assertEquals(2, created)
        assertEquals(listOf(1), closed)
    }
}
