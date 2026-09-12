package com.dd3boh.outertune.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackSessionTest {
    @Test fun `late success and late failure from old session retry with current login`() = runBlocking {
        for (oldFails in listOf(false, true)) {
            var revision = 1L
            val release = CompletableDeferred<Unit>()
            val attempts = mutableListOf<Long>()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withStablePlaybackSession({ revision }) { captured ->
                    attempts += captured
                    if (captured == 1L) {
                        release.await()
                        if (oldFails) error("old session login required")
                    }
                    "stream-$captured"
                }
            }
            revision = 3L // A -> logout -> A
            release.complete(Unit)
            assertEquals("stream-3", result.await())
            assertEquals(listOf(1L, 3L), attempts)
        }
    }

    @Test fun `stable server rejection is not retried or hidden`() = runBlocking {
        val failure = IllegalStateException("login required")
        var attempts = 0
        val result = runCatching {
            withStablePlaybackSession({ 1L }) {
                attempts++
                throw failure
            }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, attempts)
    }

    @Test fun `repeated auth changes are bounded and cancellation does not retry`() = runBlocking {
        var revision = 0L
        val changed = runCatching { withStablePlaybackSession({ revision }) { revision++; "stale" } }
        assertTrue(changed.isFailure)
        assertEquals(2L, revision)
        val cancellation = CancellationException("fixture cancellation")
        val cancelled = runCatching {
            withStablePlaybackSession({ revision }) { revision++; throw cancellation }
        }
        assertSame(cancellation, cancelled.exceptionOrNull())
        assertEquals(3L, revision)
    }
}
