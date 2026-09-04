package com.dd3boh.outertune.db.daos

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRestoreTest {
    private fun song(id: String, shuffleIndex: Int, isLocal: Boolean = false) = MediaMetadata(
        id = id,
        title = id,
        artists = emptyList(),
        duration = 1,
        genre = null,
        isLocal = isLocal,
    ).also { it.shuffleIndex = shuffleIndex }

    @Test
    fun `valid shuffle order is preserved`() {
        val songs = mutableListOf(song("a", 1), song("b", 0))

        assertTrue(normalizeRestoredQueueShuffleOrder(songs))
        assertEquals(listOf(1, 0), songs.map { it.shuffleIndex })
    }

    @Test
    fun `shuffle gaps left by cascade are normalized`() {
        val songs = mutableListOf(song("a", 0), song("c", 2))

        assertFalse(normalizeRestoredQueueShuffleOrder(songs))
        assertEquals(listOf(0, 1), songs.map { it.shuffleIndex })
    }

    @Test
    fun `mixed queue retains online songs and selects next online song`() {
        val queue = MultiQueueObject(
            id = 1,
            title = "mixed",
            queue = mutableListOf(
                song("online-before", 2),
                song("local-current", 0, isLocal = true),
                song("online-after", 1),
            ),
            shuffled = true,
            queuePos = 1,
            lastSongPos = 12_345,
            index = 0,
        )

        val repaired = retainOnlineQueueSongs(queue)

        requireNotNull(repaired)
        assertEquals(listOf("online-before", "online-after"), repaired.queue.map { it.id })
        assertEquals("online-after", repaired.queue[repaired.queuePos].id)
        assertEquals(listOf(1, 0), repaired.queue.map { it.shuffleIndex })
        assertEquals(C.TIME_UNSET, repaired.lastSongPos)
    }

    @Test
    fun `queue containing only local songs is removed`() {
        val queue = MultiQueueObject(
            id = 2,
            title = "local",
            queue = mutableListOf(song("local", 0, isLocal = true)),
            queuePos = 0,
            index = 0,
        )

        assertEquals(null, retainOnlineQueueSongs(queue))
    }
}
