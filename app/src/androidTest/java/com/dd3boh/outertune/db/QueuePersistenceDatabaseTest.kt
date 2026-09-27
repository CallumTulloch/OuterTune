package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.media3.common.C
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Check the commit boundary used when queued saves finish and the service persists its final state. */
class QueuePersistenceDatabaseTest {
    private val savedAt = LocalDateTime.of(2026, 9, 19, 23, 0)

    private fun queue(
        id: Long,
        ids: List<String>,
        shuffled: Boolean = false,
        order: List<Int> = ids.indices.toList(),
        position: Int = 0,
        playbackMs: Long = 0,
    ) = MultiQueueObject(
        id = id,
        title = "Queue $id",
        queue = ids.mapIndexed { index, songId ->
            MediaMetadata(songId, "Track $songId", emptyList(), 180, genre = null,
                shuffleIndex = order[index])
        }.toMutableList(),
        shuffled = shuffled,
        queuePos = position,
        lastSongPos = playbackMs,
        index = 99,
        playlistId = "playlist-$id",
    )

    @Test
    fun updateAllQueuesHasCompletedBeforeReturningWithinItsCallersTransaction() = runBlocking {
        val fixture = Fixture()
        try {
            val database = fixture.database
            val first = queue(10, listOf("first-a", "first-b"))
            val deleted = queue(20, listOf("deleted-queue-song"))
            val last = queue(30, listOf("last-a", "last-b"), shuffled = true, order = listOf(1, 0))
            database.saveQueueSnapshot(listOf(first, deleted, last))
            val desired = listOf(
                last.copy(title = "Moved to front", queuePos = 1, lastSongPos = 12_345),
                first.copy(title = "Current queue", queuePos = 1, lastSongPos = 54_321),
            )

            database.awaitTransaction {
                updateAllQueues(desired)
                // A detached coroutine cannot acquire this transaction's writer until it exits.
                // Reading on the owning SQLite connection therefore detects the old race without
                // relying on a sleep, a fast executor, or a later Flow eventually catching up.
                val rows = mutableListOf<List<Any?>>()
                openHelper.readableDatabase.query(
                    "SELECT id, title, `index`, queuePos, lastSongPos, shuffled, playlistId " +
                        "FROM queue ORDER BY `index`",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        rows += listOf(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
                            cursor.getInt(3), cursor.getLong(4), cursor.getInt(5), cursor.getString(6))
                    }
                }
                assertEquals(desired.mapIndexed { index, value ->
                    listOf(value.id, value.title, index, value.queuePos, value.lastSongPos,
                        if (value.shuffled) 1 else 0, value.playlistId)
                }, rows)
            }
            database.assertQueues(desired)
            assertTrue(database.getQueueSongs(deleted.id).first().isEmpty())
            assertNotNull(database.song("deleted-queue-song").first())
            fixture.reopen()
            fixture.database.assertQueues(desired)
            assertNotNull(fixture.database.song("deleted-queue-song").first())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun finalSnapshotAfterAnInFlightSaveKeepsLatestSongsAndPositionAcrossReopen() = runBlocking {
        val fixture = Fixture()
        val releaseOlder = CountDownLatch(1)
        try {
            val database = fixture.database
            val old = queue(10, listOf("old-only", "shared"))
            val removed = queue(20, listOf("removed-only"))
            database.saveQueueSnapshot(listOf(old, removed))
            val original = database.song("removed-only").first()!!.song.copy(
                inLibrary = savedAt, dateDownload = savedAt, liked = true, likedDate = savedAt,
                localPath = "/downloads/removed-only.webm",
            )
            database.update(original)

            val olderHasWritten = CountDownLatch(1)
            val older = async(Dispatchers.IO) {
                database.awaitTransaction {
                    saveQueueSnapshot(listOf(old.copy(title = "Earlier queued save", lastSongPos = 100), removed))
                    olderHasWritten.countDown()
                    check(releaseOlder.await(5, TimeUnit.SECONDS)) { "Timed out releasing earlier queue writer" }
                }
            }
            assertTrue("Earlier save did not enter its transaction", olderHasWritten.await(5, TimeUnit.SECONDS))

            // The service's final state includes a new queue and tracks that never reached a prior
            // save. Repeated song IDs are distinct queue occurrences with their own shuffle indexes.
            val desired = listOf(
                queue(30, listOf("brand-new", "brand-new"), shuffled = true,
                    order = listOf(1, 0), position = 1, playbackMs = 9_876),
                queue(10, listOf("shared", "new-last-track", "old-only"), shuffled = true,
                    order = listOf(2, 0, 1), position = 1, playbackMs = 65_432),
            )
            val finalStarted = CountDownLatch(1)
            val finalSave = async(Dispatchers.IO) {
                finalStarted.countDown()
                database.saveQueueSnapshot(desired)
            }
            assertTrue(finalStarted.await(5, TimeUnit.SECONDS))
            assertFalse("Final write should wait for the earlier transaction", finalSave.isCompleted)
            releaseOlder.countDown()
            withTimeout(10_000) {
                older.await()
                finalSave.await()
            }

            database.assertQueues(desired)
            assertEquals(original, database.song("removed-only").first()!!.song)
            assertTrue(database.getQueueSongs(20).first().isEmpty())
            fixture.reopen()
            fixture.database.assertQueues(desired)
            assertEquals(original, fixture.database.song("removed-only").first()!!.song)
            assertTrue(fixture.database.getQueueSongs(20).first().isEmpty())
        } finally {
            releaseOlder.countDown()
            fixture.close()
        }
    }

    @Test
    fun clearingQueueContentsThenAllQueuesDoesNotResurrectOldRowsOrDeleteSongs() = runBlocking {
        val fixture = Fixture()
        try {
            val database = fixture.database
            val previous = queue(10, listOf("retained-a", "retained-b"))
            database.saveQueueSnapshot(listOf(previous))

            database.saveQueueSnapshot(listOf(previous.copy(queue = mutableListOf(), queuePos = -1)))
            assertTrue(database.getQueueSongs(previous.id).first().isEmpty())
            assertTrue(database.readQueue().isEmpty())
            previous.queue.forEach { assertNotNull(database.song(it.id).first()) }

            database.saveQueueSnapshot(emptyList())
            assertTrue(database.getAllQueues().first().isEmpty())
            assertTrue(database.getQueueSongs(previous.id).first().isEmpty())
            fixture.reopen()
            assertTrue(fixture.database.getAllQueues().first().isEmpty())
            assertTrue(fixture.database.readQueue().isEmpty())
            previous.queue.forEach { assertNotNull(fixture.database.song(it.id).first()) }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun snapshotAfterScannerDeletionKeepsNewOnlineSongsWithoutRecreatingLocalRows() = runBlocking {
        val fixture = Fixture()
        try {
            val database = fixture.database
            val removed = MediaMetadata("removed-local", "Removed", emptyList(), 180,
                genre = null, isLocal = true, localPath = "/music/removed.mp3", shuffleIndex = 1)
            val surviving = MediaMetadata("surviving-local", "Surviving", emptyList(), 180,
                genre = null, isLocal = true, localPath = "/music/surviving.mp3", shuffleIndex = 3)
            database.insert(removed)
            database.insert(surviving)
            val mixed = queue(10, listOf("online-before", "online-after"), shuffled = true,
                order = listOf(0, 2), playbackMs = 12_345).copy(
                queue = mutableListOf(removed, surviving,
                    queue(10, listOf("online-before"), order = listOf(0)).queue.single(),
                    queue(10, listOf("online-after"), order = listOf(2)).queue.single()),
                queuePos = 0,
            )
            val allRemoved = mixed.copy(id = 20, queue = mutableListOf(removed), shuffled = false)
            database.saveQueueSnapshot(listOf(mixed, allRemoved))
            // A scan removes the row after the board captured it; this also cascades its maps.
            database.delete(removed.toSongEntity())
            val newOnline = queue(10, listOf("new-online"), order = listOf(4)).queue.single()
            val finalSnapshot = mixed.copy(queue = (mixed.queue + newOnline).toMutableList())
            val originalOrder = finalSnapshot.queue.map { it.shuffleIndex }

            database.saveQueueSnapshot(listOf(finalSnapshot, allRemoved))

            assertNull(database.song(removed.id).first())
            assertNotNull(database.song(newOnline.id).first())
            assertNotNull(database.song(surviving.id).first())
            assertTrue(database.getQueueSongs(allRemoved.id).first().isEmpty())
            val restored = database.readQueue().single()
            assertEquals(listOf("surviving-local", "online-before", "online-after", "new-online"),
                restored.queue.map { it.id })
            assertEquals(listOf(2, 0, 1, 3), restored.queue.map { it.shuffleIndex })
            assertEquals("online-after", restored.queue[restored.queuePos].id)
            assertEquals(C.TIME_UNSET, restored.lastSongPos)
            assertEquals(originalOrder, finalSnapshot.queue.map { it.shuffleIndex })
            assertEquals(0, finalSnapshot.queuePos)

            fixture.reopen()
            assertNull(fixture.database.song(removed.id).first())
            val reopened = fixture.database.readQueue().single()
            assertEquals(restored.queue.map { it.id }, reopened.queue.map { it.id })
            assertEquals(restored.queuePos, reopened.queuePos)
            assertEquals(restored.queue.map { it.shuffleIndex }, reopened.queue.map { it.shuffleIndex })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun savingStaleLocalQueuePreservesImportedMetadataAndDoesNotReprocessAlbums() = runBlocking {
        val statements = CopyOnWriteArrayList<String>()
        val fixture = Fixture(object : RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                statements += sqlQuery
            }
        })
        try {
            val database = fixture.database
            val artist = MediaMetadata.Artist("LA-current-artist", "Current artist", isLocal = true)
            val album = MediaMetadata.Album("LB-current-album", "Current album", isLocal = true,
                artists = listOf(artist))
            val imported = (0..2).map { index ->
                MediaMetadata("LS-queue-preserve-$index", "Current track $index", listOf(artist), 180 + index,
                    album = album, genre = listOf(MediaMetadata.Genre(null, "Current genre", isLocal = true)),
                    isLocal = true, localPath = "/music/current/$index.flac",
                    thumbnailUrl = "/music/current/$index.flac", year = 2026,
                    trackNumber = index + 1, discNumber = 1, liked = index == 1)
            }
            database.awaitTransaction { imported.forEach { insert(it) } }
            val before = database.localMetadataRows()
            val staleArtist = artist.copy(id = "LA-stale-artist", name = "Old artist")
            val stale = imported.map { song ->
                song.copy(title = "Old title", artists = listOf(staleArtist),
                    album = album.copy(title = "Old album", artists = listOf(staleArtist)),
                    genre = listOf(MediaMetadata.Genre(null, "Old genre", isLocal = true)),
                    year = 1999, localPath = "/music/old/${song.id}.flac",
                    thumbnailUrl = "/music/old/${song.id}.flac")
            }
            // Repeat one local song: occurrences retain their own queue/shuffle positions.
            val first = queue(10, listOf(stale[2].id, stale[0].id, stale[2].id, stale[1].id),
                shuffled = true, order = listOf(2, 0, 3, 1), position = 1, playbackMs = 12_345).let { queued ->
                queued.copy(queue = queued.queue.map { item ->
                    stale.first { it.id == item.id }.copy(shuffleIndex = item.shuffleIndex)
                }.toMutableList())
            }
            val latest = first.copy(title = "Latest local queue", queuePos = 3, lastSongPos = 54_321)

            for (snapshot in listOf(first, latest)) {
                statements.clear()
                database.saveQueueSnapshot(listOf(snapshot))
                val saveStatements = statements.toList()
                assertTrue("Query callback did not observe queue persistence", saveStatements.any {
                    it.contains("INSERT", ignoreCase = true) && it.contains("queue_song_map")
                })
                assertFalse("Queue save resolved local album candidates", saveStatements.any {
                    it.contains("AS albumRowId", ignoreCase = true)
                })
                val metadataWrite = Regex(
                    "(?i)\\b(?:UPDATE(?:\\s+OR\\s+\\w+)?\\s+|INSERT(?:\\s+OR\\s+\\w+)?\\s+INTO\\s+|DELETE\\s+FROM\\s+)" +
                        "[`\"]?(song|album|artist|genre|song_artist_map|song_album_map|song_genre_map|album_artist_map)\\b",
                )
                assertTrue("Local queue save wrote imported metadata: ${saveStatements.filter { metadataWrite.containsMatchIn(it) }}",
                    saveStatements.none { metadataWrite.containsMatchIn(it) })
                assertEquals(before, database.localMetadataRows())
                database.assertQueues(listOf(snapshot))
            }

            fixture.reopen()
            fixture.database.assertQueues(listOf(latest))
            assertEquals(before, fixture.database.localMetadataRows())
            val restored = fixture.database.readQueue().single()
            restored.queue.forEach { song ->
                val current = imported.first { it.id == song.id }
                assertEquals(current.title, song.title)
                assertEquals(current.localPath, song.localPath)
                assertEquals(current.thumbnailUrl, song.thumbnailUrl)
                assertEquals(album.title, song.album?.title)
                assertEquals(listOf(artist.name), song.artists.map { it.name })
            }
        } finally {
            fixture.close()
        }
    }

    private fun MusicDatabase.localMetadataRows(): Map<String, List<List<String?>>> = listOf(
        "song", "album", "artist", "genre", "song_artist_map", "song_album_map",
        "song_genre_map", "album_artist_map",
    ).associateWith { table ->
        openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add((0 until cursor.columnCount).map { column ->
                        if (cursor.isNull(column)) null else cursor.getString(column)
                    })
                }
            }
        }
    }

    private suspend fun MusicDatabase.assertQueues(expected: List<MultiQueueObject>) {
        val actual = withTimeout(5_000) { readQueue() }
        assertEquals(expected.map { it.id }, actual.map { it.id })
        expected.zip(actual).forEachIndexed { index, (wanted, restored) ->
            assertEquals(wanted.title, restored.title)
            assertEquals(index, restored.index)
            assertEquals(wanted.playlistId, restored.playlistId)
            assertEquals(wanted.shuffled, restored.shuffled)
            assertEquals(wanted.queuePos, restored.queuePos)
            assertEquals(wanted.lastSongPos, restored.lastSongPos)
            assertEquals(wanted.queue.map { it.id }, restored.queue.map { it.id })
            assertEquals(wanted.queue.map { it.shuffleIndex }, restored.queue.map { it.shuffleIndex })
        }
        val resumed = withTimeout(5_000) { getResumptionQueue() }!!
        val current = expected.last()
        assertEquals(current.id, resumed.id)
        assertEquals(current.queuePos, resumed.queuePos)
        assertEquals(current.lastSongPos, resumed.lastSongPos)
        assertEquals(current.queue[current.queuePos].id, resumed.getCurrentSong()!!.id)
    }

    private class Fixture(private val queryCallback: RoomDatabase.QueryCallback? = null) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val filename = "queue-persistence-${UUID.randomUUID()}.db"
        private var room = open()
        var database = MusicDatabase(room)
            private set

        private fun open() = Room.databaseBuilder(context, InternalDatabase::class.java, filename)
            .apply { queryCallback?.let { setQueryCallback(it, Executor { command -> command.run() }) } }
            .build()

        fun reopen() {
            room.close()
            room = open()
            database = MusicDatabase(room)
        }

        fun close() {
            room.close()
            context.deleteDatabase(filename)
        }
    }
}
