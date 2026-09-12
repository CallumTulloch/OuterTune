package com.dd3boh.outertune.utils.potoken

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Publish a generator only after its streaming token is ready, and never close it during use. */
internal class PoTokenSessionCache<G>(
    private val create: suspend () -> G,
    private val expired: (G) -> Boolean,
    private val generate: suspend (G, String) -> String,
    private val close: suspend (G) -> Unit,
) {
    private class Entry<G>(val sessionId: String, val revision: Long, val generator: G, val streamingToken: String)
    private val mutex = Mutex()
    private var cached: Entry<G>? = null

    suspend fun get(videoId: String, sessionId: String, revision: Long): PoTokenResult = mutex.withLock {
        repeat(2) { attempt ->
            var created = false
            val existing = cached
            val entry = if (existing != null && existing.sessionId == sessionId && existing.revision == revision &&
                !expired(existing.generator)) existing else {
                discard()
                val generator = create()
                try {
                    Entry(sessionId, revision, generator, generate(generator, sessionId)).also {
                        cached = it
                        created = true
                    }
                } catch (failure: Throwable) {
                    closeAfterFailure(generator, failure)
                    throw failure
                }
            }
            try {
                return@withLock PoTokenResult(generate(entry.generator, videoId), entry.streamingToken)
            } catch (cancelled: CancellationException) {
                discard(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                discard(failure)
                // One warm generator failure can recreate it; a fresh generator failure stays visible.
                if (created || attempt == 1) throw failure
            }
        }
        error("PoToken retry exhausted")
    }

    private suspend fun discard(failure: Throwable? = null) {
        val previous = cached ?: return
        cached = null
        if (failure == null) withContext(NonCancellable) { close(previous.generator) }
        else closeAfterFailure(previous.generator, failure)
    }

    private suspend fun closeAfterFailure(generator: G, failure: Throwable) {
        try {
            withContext(NonCancellable) { close(generator) }
        } catch (closeFailure: Throwable) {
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
    }
}
