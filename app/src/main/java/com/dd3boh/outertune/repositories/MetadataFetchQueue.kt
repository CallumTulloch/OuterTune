package com.dd3boh.outertune.repositories

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Workers pull directly from this queue so a page opened now can pass a library backlog.
 * Equality identifies queued requests; the caller remains responsible for deduplicating in-flight work.
 */
internal class MetadataFetchQueue<T : Any>(private val foregroundBurst: Int = 3) {
    init {
        require(foregroundBurst > 0)
    }

    private val lock = Any()
    private val foreground = LinkedHashSet<T>()
    private val background = LinkedHashSet<T>()
    private val changes = MutableStateFlow(0L)
    private var foregroundStreak = 0
    private var closed = false

    /** Returns false only after close. An existing foreground request is never demoted. */
    fun offer(item: T, foreground: Boolean = false): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val changed = if (foreground) {
            background.remove(item)
            this.foreground.add(item)
        } else if (item !in this.foreground) {
            background.add(item)
        } else {
            false
        }
        if (changed) changes.value++
        true
    }

    /** Promote only queued work, without enqueueing a duplicate of a request already running. */
    fun promote(item: T): Boolean = synchronized(lock) {
        if (item in foreground) return@synchronized true
        if (!background.remove(item)) return@synchronized false
        foreground.add(item)
        changes.value++
        true
    }

    /**
     * Reclassify queued requests after navigation or playback changes. Entries retaining their
     * priority keep their order; moved entries append in their previous order. In-flight work is
     * not affected. The predicate must be cheap and must not mutate the queue.
     */
    fun updatePriority(isForeground: (T) -> Boolean) = synchronized(lock) {
        // Select both sets before mutation so a failed predicate cannot lose accepted work.
        val demoted = foreground.filterNot(isForeground)
        val promoted = background.filter(isForeground)
        if (demoted.isNotEmpty() || promoted.isNotEmpty()) {
            demoted.forEach(foreground::remove)
            promoted.forEach(background::remove)
            foreground.addAll(promoted)
            background.addAll(demoted)
            // Keep the streak: repeated page changes must not starve saved background requests.
            changes.value++
        }
    }

    /**
     * Takes compatible work from one priority at a time, with bounded lookahead through interleaved
     * languages. [canBatch] must be a cheap, non-suspending compatibility check. Queued work is not
     * removed while waiting, and cancellation of one waiter cannot consume another worker's wakeup.
     * Once a batch is returned, its worker owns completion/cleanup, including cancellation.
     */
    suspend fun takeBatch(
        maxSize: Int = 50,
        scanLimit: Int = 100,
        canBatch: (T, T) -> Boolean = { _, _ -> false },
    ): List<T>? {
        require(maxSize > 0)
        require(scanLimit >= maxSize)
        while (true) {
            currentCoroutineContext().ensureActive()
            // Read before checking the queues to avoid missing an offer between checking and waiting.
            val revision = changes.value
            val (batch, isClosed) = synchronized(lock) { pollBatch(maxSize, scanLimit, canBatch) to closed }
            if (batch != null) return batch
            if (isClosed) return null
            changes.first { it != revision }
        }
    }

    /** Stop accepting work, wake every waiter, and let workers drain requests already accepted. */
    fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            changes.value++
        }
    }

    private fun pollBatch(maxSize: Int, scanLimit: Int, canBatch: (T, T) -> Boolean): List<T>? {
        if (foreground.isEmpty() && background.isEmpty()) return null
        val selected = if (foreground.isNotEmpty() &&
            (background.isEmpty() || foregroundStreak < foregroundBurst)) foreground else background
        val iterator = selected.iterator()
        val first = iterator.next()
        val result = mutableListOf(first)
        var scanned = 1
        while (iterator.hasNext() && result.size < maxSize && scanned < scanLimit) {
            val candidate = iterator.next()
            scanned++
            if (canBatch(first, candidate)) result.add(candidate)
        }
        // Compatibility failures leave the whole queue intact. Mutation starts after selection.
        result.forEach(selected::remove)
        foregroundStreak = if (selected === foreground && background.isNotEmpty()) foregroundStreak + 1 else 0
        return result
    }
}
