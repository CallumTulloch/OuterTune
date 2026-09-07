package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the real queue-save path which creates stubs for unplayed search results. */
class AlbumOriginalContextEligibilityTest {
    private val savedAt = LocalDateTime.of(2026, 9, 7, 12, 0)

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try { block(MusicDatabase(internal)) }
        finally { internal.close() }
    }

    @Test fun queueOnlyAlbumsStayExcludedWhileOneDownloadingAlbumBecomesEligible() = withDatabase { database ->
        val queue = MultiQueueObject(id = 1, title = "Search results", index = 0,
            queue = (0 until 30).map { index ->
                MediaMetadata(id = "context-song-$index", title = "Result $index", artists = emptyList(),
                    duration = 180, genre = null,
                    album = MediaMetadata.Album("MPRE-context-$index", "Result album $index"),
                    shuffleIndex = index)
            }.toMutableList())
        database.saveQueue(queue)
        val albumIds = database.remoteAlbumsForMetadata().first().map { it.id }
        assertEquals(30, albumIds.size)
        assertEquals(30, database.getQueueSongs(1).first().size)
        assertTrue(albumIds.none(database::isAlbumOriginalContextEligible))

        // Epoch millisecond 1 is the app's in-progress download marker; completion is not required.
        database.updateDownloadStatus("context-song-0", LocalDateTime.ofInstant(Instant.ofEpochMilli(1), ZoneOffset.UTC))
        assertEquals(listOf("MPRE-context-0"), albumIds.filter(database::isAlbumOriginalContextEligible))
        database.updateDownloadStatus("context-song-0", savedAt)
        assertEquals(listOf("MPRE-context-0"), albumIds.filter(database::isAlbumOriginalContextEligible))
        database.updateDownloadStatus("context-song-0", null)
        assertTrue(albumIds.none(database::isAlbumOriginalContextEligible))
        assertEquals(30, database.getQueueSongs(1).first().size)
    }

    @Test fun bookmarkAndRelatedLibrarySongQualifyButLocalAndUnknownAlbumsDoNot() = withDatabase { database ->
        val bookmarked = AlbumEntity("MPRE-bookmarked", title = "Bookmarked", songCount = 0, duration = 0,
            bookmarkedAt = savedAt)
        database.insert(bookmarked)
        assertTrue(database.isAlbumOriginalContextEligible(bookmarked.id))
        database.update(bookmarked.copy(bookmarkedAt = null))
        assertFalse(database.isAlbumOriginalContextEligible(bookmarked.id))

        val libraryAlbum = AlbumEntity("MPRE-library", title = "Library", songCount = 1, duration = 180)
        val librarySong = SongEntity("library-song", "Library song", localPath = null, inLibrary = savedAt)
        database.insert(libraryAlbum)
        database.insert(librarySong)
        assertFalse(database.isAlbumOriginalContextEligible(libraryAlbum.id))
        database.insert(SongAlbumMap(librarySong.id, libraryAlbum.id, 0))
        assertTrue(database.isAlbumOriginalContextEligible(libraryAlbum.id))
        database.update(librarySong.copy(inLibrary = null))
        assertFalse(database.isAlbumOriginalContextEligible(libraryAlbum.id))

        val localAlbum = AlbumEntity("LB-local-context", title = "Local", songCount = 1, duration = 180,
            isLocal = true, bookmarkedAt = savedAt)
        database.insert(localAlbum)
        database.insert(SongAlbumMap(librarySong.id, localAlbum.id, 0))
        database.updateDownloadStatus(librarySong.id, savedAt)
        assertFalse(database.isAlbumOriginalContextEligible(localAlbum.id))
        assertFalse(database.isAlbumOriginalContextEligible("MPRE-unknown"))
    }
}
