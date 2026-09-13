package com.dd3boh.outertune.db

import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit large-fixture probe. Logs Android SQLite timings without a hardware-specific speed assertion. */
class ArtistGroupingPerformanceTest {
    private val groupCount = 2_500
    private fun onlineId(index: Int) = "UC" + index.toString().padStart(22, '0')
    private fun localId(index: Int) = "LA-performance-$index"

    @Test
    fun fiveThousandSourceArtistsRemainGroupedAndSearchable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(room)
        val savedAt = LocalDateTime.of(2026, 9, 13, 12, 0)
        try {
            database.awaitTransaction {
                repeat(groupCount) { index ->
                    val target = onlineId(index)
                    val source = localId(index)
                    val title = "Official ${index.toString().padStart(5, '0')}"
                    insert(ArtistEntity(target, title, onlineId = target, lastUpdateTime = savedAt))
                    insert(ArtistEntity(source, "Folder $index", isLocal = true, lastUpdateTime = savedAt))
                    val localSong = SongEntity("LS-performance-$index", "Track $index", duration = 180,
                        localPath = "/isolated-performance/$index.flac", isLocal = true, inLibrary = savedAt)
                    val onlineSong = SongEntity("YT-performance-$index", "Track $index", duration = 180,
                        localPath = null, inLibrary = savedAt)
                    insert(localSong); insert(onlineSong)
                    insert(SongArtistMap(localSong.id, source, 0))
                    insert(SongArtistMap(onlineSong.id, target, 0))
                    setLocalArtistLink(LocalArtistLink(source, target, title, null, "performance-$index"))
                }
            }
            database.openHelper.readableDatabase.query("SELECT sqlite_version()").use { cursor ->
                check(cursor.moveToFirst())
                Log.i("ArtistGroupingPerf", "SQLite ${cursor.getString(0)}; sourceArtists=5000 links=2500 songs=5000")
            }

            var start = SystemClock.elapsedRealtime()
            val artists = withTimeout(20_000) { database.savedArtistsByCreateDateAsc().first() }
            Log.i("ArtistGroupingPerf", "savedArtistsByCreateDateAsc ms=${SystemClock.elapsedRealtime() - start} rows=${artists.size}")
            assertEquals(groupCount, artists.size)
            assertTrue(artists.all { it.songCount == 2 && !it.artist.isLocal })
            assertEquals((0 until groupCount).map(::onlineId).toSet(), artists.map { it.id }.toSet())

            start = SystemClock.elapsedRealtime()
            val mappings = withTimeout(20_000) { database.artistDisplayMappings().first() }
            Log.i("ArtistGroupingPerf", "artistDisplayMappings ms=${SystemClock.elapsedRealtime() - start} rows=${mappings.size}")
            assertEquals(groupCount, mappings.size)
            val mappingsBySource = mappings.associateBy { it.sourceArtistId }
            repeat(groupCount) { index ->
                assertEquals(onlineId(index), mappingsBySource.getValue(localId(index)).canonicalArtistId)
            }

            start = SystemClock.elapsedRealtime()
            val songs = withTimeout(20_000) { database.searchArtistSongs("Official 01234").first() }
            Log.i("ArtistGroupingPerf", "searchArtistSongs ms=${SystemClock.elapsedRealtime() - start} rows=${songs.size}")
            assertEquals(setOf("LS-performance-1234", "YT-performance-1234"), songs.map { it.id }.toSet())
            assertEquals(listOf(localId(1234)), songs.single { it.song.isLocal }.artists.map { it.id })
        } finally { room.close() }
    }
}
