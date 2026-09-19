package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class QueueSaveBatch(
    val queues: List<MultiQueueObject>,
    val songQueueIds: Set<Long>,
)

/** Capture on the queue owner's thread, including the otherwise mutable shuffle indexes. */
internal fun snapshotQueuesForSave(
    queues: List<MultiQueueObject>,
    enrich: (MediaMetadata) -> MediaMetadata = { it },
    songQueueIds: Set<Long>? = null,
): List<MultiQueueObject> = queues.mapIndexed { index, queue ->
    queue.copy(index = index, queue = if (songQueueIds == null || queue.id in songQueueIds) queue.queue.map { song ->
        enrich(song).copy(shuffleIndex = song.shuffleIndex)
    }.toMutableList() else mutableListOf())
}

/** New headers supersede old ones; unchanged songs keep their earlier dirty snapshot. */
private fun mergeQueueSaves(earlier: QueueSaveBatch?, latest: QueueSaveBatch): QueueSaveBatch {
    if (earlier == null) return latest
    val earlierSongs = earlier.queues.filter { it.id in earlier.songQueueIds }.associateBy { it.id }
    val queues = latest.queues.map { queue ->
        if (queue.id !in latest.songQueueIds && queue.id in earlierSongs)
            queue.copy(queue = earlierSongs.getValue(queue.id).queue)
        else queue
    }
    return QueueSaveBatch(queues,
        (earlier.songQueueIds + latest.songQueueIds).intersect(queues.mapTo(mutableSetOf()) { it.id }))
}

/** One writer, one latest snapshot and at most one dirty marker per current queue. */
internal class QueueSaveScheduler(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val coalesce: suspend () -> Unit = { delay(5_000L) },
    private val reportFailure: (Exception) -> Unit = {},
    private val write: suspend (QueueSaveBatch) -> Unit,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var closed = false
    private var pending: QueueSaveBatch? = null

    internal val pendingQueueCount: Int get() = synchronized(lock) { pending?.queues?.size ?: 0 }
    internal val pendingSongQueueCount: Int get() = synchronized(lock) { pending?.songQueueIds?.size ?: 0 }

    init {
        scope.launch {
            for (ignored in requests) {
                // A fixed collection window also makes progress during continuous playback updates.
                coalesce()
                val batch = synchronized(lock) {
                    requests.tryReceive()
                    pending.also { pending = null }
                } ?: continue
                try {
                    write(batch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // Keep dirty songs on failure, but do not resurrect a queue deleted meanwhile.
                    synchronized(lock) {
                        if (!closed) {
                            pending = pending?.let { mergeQueueSaves(batch, it) } ?: batch
                            requests.trySend(Unit)
                        }
                    }
                    reportFailure(failure)
                }
            }
        }
    }

    fun request(
        queues: List<MultiQueueObject>,
        songQueueIds: Set<Long> = emptySet(),
        enrich: (MediaMetadata) -> MediaMetadata = { it },
    ) {
        if (synchronized(lock) { closed }) return
        // Position/order updates only copy headers. Song snapshots are replaced only when dirty.
        val snapshot = snapshotQueuesForSave(queues, enrich, songQueueIds)
        synchronized(lock) {
            if (closed) return
            pending = mergeQueueSaves(pending, QueueSaveBatch(snapshot,
                songQueueIds.intersect(snapshot.mapTo(mutableSetOf()) { it.id })))
            requests.trySend(Unit)
        }
    }

    fun shutdown() {
        synchronized(lock) {
            closed = true
            pending = null
            requests.close()
            scope.cancel()
        }
    }

    suspend fun awaitShutdown() = job.join()
}
