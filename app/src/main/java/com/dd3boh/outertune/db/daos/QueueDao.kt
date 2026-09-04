package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dd3boh.outertune.db.entities.QueueEntity
import com.dd3boh.outertune.db.entities.QueueSong
import com.dd3boh.outertune.db.entities.QueueSongMap
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun normalizeRestoredQueueShuffleOrder(
    songs: MutableList<MediaMetadata>,
): Boolean {
    val isValid = songs.map { it.shuffleIndex }.sorted() == songs.indices.toList()
    if (!isValid) {
        songs.forEachIndexed { index, song -> song.shuffleIndex = index }
    }
    return isValid
}

/**
 * Removes folder songs while retaining the online part of a mixed queue. The current online song
 * is preserved; if the current song was local, the next surviving playback-order item is selected.
 */
internal fun retainOnlineQueueSongs(queue: MultiQueueObject): MultiQueueObject? {
    if (queue.queue.none(MediaMetadata::isLocal)) return queue

    val currentSong = queue.queue.getOrNull(queue.queuePos)
    val playbackOrder = if (queue.shuffled) {
        queue.queue.sortedBy(MediaMetadata::shuffleIndex)
    } else {
        queue.queue.toList()
    }
    val currentPlaybackIndex = playbackOrder.indexOf(currentSong)
    val fallbackSong = if (currentPlaybackIndex >= 0) {
        playbackOrder.drop(currentPlaybackIndex + 1).firstOrNull { !it.isLocal }
            ?: playbackOrder.take(currentPlaybackIndex).lastOrNull { !it.isLocal }
    } else {
        null
    }
    val targetSong = currentSong?.takeUnless(MediaMetadata::isLocal) ?: fallbackSong
    val survivingSongs = queue.queue.filterNot(MediaMetadata::isLocal).toMutableList()
    if (survivingSongs.isEmpty()) return null

    survivingSongs.sortedBy(MediaMetadata::shuffleIndex).forEachIndexed { index, song ->
        song.shuffleIndex = index
    }
    queue.queue.clear()
    queue.queue.addAll(survivingSongs)
    queue.queuePos = targetSong?.let(survivingSongs::indexOf)?.takeIf { it >= 0 } ?: 0
    if (currentSong?.isLocal != false) {
        queue.lastSongPos = androidx.media3.common.C.TIME_UNSET
    }
    return queue
}

@Dao
interface QueueDao {

    // region Gets
    @Query("SELECT * from queue ORDER BY `index`")
    fun getAllQueues(): Flow<List<QueueEntity>>

    @Transaction
    @Query("SELECT song.*, queue_song_map.shuffledIndex from queue_song_map JOIN song ON queue_song_map.songId = song.id WHERE queueId = :queueId ORDER BY `index`")
    fun getQueueSongs(queueId: Long): Flow<List<QueueSong>>

    suspend fun readQueue(): List<MultiQueueObject> {
        val resultQueues = ArrayList<MultiQueueObject>()
        val queues = getAllQueues().first()

        queues.forEach { queue ->
            val shuffledSongs = getQueueSongs(queue.id).first()
            if (shuffledSongs.isEmpty()) return@forEach
            val restoredSongs = shuffledSongs.map {
                val song = it.song.toMediaMetadata()
                song.shuffleIndex = it.shuffledIndex
                song
            }.toMutableList()
            val shuffleOrderIsValid = normalizeRestoredQueueShuffleOrder(restoredSongs)
            resultQueues.add(
                MultiQueueObject(
                    id = queue.id,
                    title = queue.title,
                    queue = restoredSongs,
                    // Cascading song deletion can leave gaps in persisted shuffle indexes.
                    shuffled = queue.shuffled && shuffleOrderIsValid,
                    queuePos = queue.queuePos.coerceIn(0, restoredSongs.lastIndex),
                    lastSongPos = queue.lastSongPos,
                    index = queue.index,
                    playlistId = queue.playlistId
                )
            )
        }

        return resultQueues
    }

    suspend fun getResumptionQueue(): MultiQueueObject? {
        val queues = getAllQueues().first()
        if (queues.isEmpty()) return null
        val q = queues.last()
        val shuffledSongs = getQueueSongs(q.id).first()
        if (shuffledSongs.isEmpty()) return null
        val restoredSongs = shuffledSongs.map {
            val song = it.song.toMediaMetadata()
            song.shuffleIndex = it.shuffledIndex
            song
        }.toMutableList()
        val shuffleOrderIsValid = normalizeRestoredQueueShuffleOrder(restoredSongs)

        return MultiQueueObject(
            id = q.id,
            title = q.title,
            queue = restoredSongs,
            shuffled = q.shuffled && shuffleOrderIsValid,
            queuePos = q.queuePos.coerceIn(0, restoredSongs.lastIndex),
            lastSongPos = q.lastSongPos,
            index = q.index,
            playlistId = q.playlistId
        )
    }
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(queue: QueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(queueSong: QueueSongMap)
    // endregion

    // region Updates
    @Update
    fun update(queue: QueueEntity)

    @Transaction
    fun updateQueue(mq: MultiQueueObject) {
        update(
            QueueEntity(
                id = mq.id,
                title = mq.title,
                shuffled = mq.shuffled,
                queuePos = mq.queuePos,
                lastSongPos = mq.lastSongPos,
                index = mq.index,
                playlistId = mq.playlistId
            )
        )
    }

    @Transaction
    fun updateAllQueues(mqs: List<MultiQueueObject>) {
        val mqs = mqs.toList() // please no more ConcurrentModificationException I beg you
        mqs.forEachIndexed { index, q -> q.index = index }
        CoroutineScope(Dispatchers.IO).launch {
            nukeAliens(mqs.map { it.id })
            mqs.forEach { updateQueue(it) }
        }
    }

    // endregion

    // region Deletes
    @Delete
    fun delete(mq: QueueEntity)

    @Query("DELETE FROM queue")
    fun deleteAllQueues()

    @Query("DELETE FROM queue_song_map WHERE queueId = :id")
    fun deleteAllQueueSongs(id: Long)

    @Query("DELETE FROM queue WHERE id = :id")
    fun deleteQueue(id: Long)

    @Query("DELETE FROM queue WHERE id NOT IN (:ids)")
    fun nukeAliens(ids: List<Long>)
    // endregion
}
