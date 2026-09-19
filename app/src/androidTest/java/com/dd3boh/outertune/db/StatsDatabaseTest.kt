package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.PlayCountEntity
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsDatabaseTest {
    private val from = LocalDateTime.of(2026, 9, 12, 12, 0)
    private val recent = from.plusDays(1)
    private val timestamp get() = from.toInstant(ZoneOffset.UTC).toEpochMilli()

    private suspend fun withDatabase(block: suspend (MusicDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try { block(MusicDatabase(internal)) } finally { internal.close() }
    }

    private fun song(id: String, local: Boolean = false, saved: Boolean = true, downloaded: Boolean = false) =
        SongEntity(id, id, duration = 180, isLocal = local,
            localPath = if (local) "/music/$id.mp3" else null,
            inLibrary = recent.takeIf { saved }, dateDownload = recent.takeIf { downloaded })

    private fun MusicDatabase.play(id: String, duration: Long, time: LocalDateTime = recent) {
        insert(Event(songId = id, timestamp = time, playTime = duration))
    }

    @Test fun songRankUsesSummedTimeInTheSelectedPeriodAndAppliesOffsetAfterRanking() = runBlocking {
        withDatabase { db ->
            listOf("z-repeat", "a-long", "m-short", "old", "boundary", "zero", "never").forEach {
                db.insert(song(it, local = it == "z-repeat"))
            }
            db.play("z-repeat", 70)
            db.play("z-repeat", 70, recent.plusHours(1))
            db.play("a-long", 120)
            db.play("m-short", 60)
            db.play("old", 8_000, from.minusDays(1)) // Same month, outside the selected week.
            db.play("boundary", 9_000, from)
            db.play("zero", 0)

            val songs = db.mostPlayedSongs(timestamp, limit = 99).first()
            assertEquals(listOf("z-repeat", "a-long", "m-short"), songs.map { it.id })
            assertTrue(songs.first().song.isLocal)
            assertEquals(listOf("a-long"), db.mostPlayedSongs(timestamp, limit = 1, offset = 1).first().map { it.id })
            assertEquals(listOf("boundary", "old"), db.mostPlayedSongs(
                from.minusDays(2).toInstant(ZoneOffset.UTC).toEpochMilli(), limit = 2).first().map { it.id })
        }
    }

    @Test fun artistRankUsesExactEventDatesAndPlayCountsForLocalAndUnsavedOnlineSongs() = runBlocking {
        withDatabase { db ->
            val local = ArtistEntity("LA-stats-local", "Local artist", isLocal = true)
            val online = ArtistEntity("UC" + "A".repeat(22), "Online artist")
            val old = ArtistEntity("UC" + "B".repeat(22), "Old artist")
            val never = ArtistEntity("UC" + "C".repeat(22), "Unplayed artist")
            val zero = ArtistEntity("UC" + "D".repeat(22), "Zero duration artist")
            val boundary = ArtistEntity("UC" + "E".repeat(22), "Boundary artist")
            listOf(local, online, old, never, zero, boundary).forEach(db::insert)
            db.insert(song("local", local = true, downloaded = true))
            db.insert(song("local-unplayed", local = true))
            db.insert(song("online", saved = false))
            listOf("old", "never", "zero", "boundary").forEach { db.insert(song(it)) }
            listOf("local" to local.id, "local-unplayed" to local.id, "online" to online.id,
                "old" to old.id, "never" to never.id, "zero" to zero.id, "boundary" to boundary.id)
                .forEach { (songId, artistId) -> db.insert(SongArtistMap(songId, artistId, 0)) }
            db.play("local", 50)
            db.play("local", 50, recent.plusHours(1))
            db.play("online", 10_000) // Artists rank by play count, independently of song duration.
            repeat(10) { db.play("old", 100, from.minusDays(1).plusMinutes(it.toLong())) }
            db.play("boundary", 100, from)
            db.play("zero", 0)
            db.insert(PlayCountEntity("old", 2026, 9, 999))

            val artists = db.mostPlayedArtists(timestamp, limit = 99).first()
            assertEquals(listOf(local.id, online.id), artists.map { it.id })
            assertTrue(artists.first().artist.isLocal)
            assertEquals(2, artists.first().songCount) // Library counts, not duplicate event rows.
            assertEquals(1, artists.first().downloadCount)
            assertEquals(0, artists.last().songCount)
            assertEquals(listOf(local.id), db.mostPlayedArtists(timestamp, limit = 1).first().map { it.id })
            assertEquals(old.id, db.mostPlayedArtists(0L, limit = 1).first().single().id)
        }
    }

    @Test fun linkedTagAliasesAndAnOnlineCreditCountEachSongEventOnlyOnce() = runBlocking {
        withDatabase { db ->
            val target = ArtistEntity("UC" + "F".repeat(22), "Linked artist")
            val competitor = ArtistEntity("UC" + "G".repeat(22), "Three plays")
            val firstTag = ArtistEntity("LA-stats-tag-a", "Tag A", isLocal = true)
            val secondTag = ArtistEntity("LA-stats-tag-b", "Tag B", isLocal = true)
            listOf(target, competitor, firstTag, secondTag).forEach(db::insert)
            db.insert(song("linked", local = true, downloaded = true))
            db.insert(song("competitor"))
            listOf(firstTag.id, secondTag.id, target.id).forEachIndexed { index, id ->
                db.insert(SongArtistMap("linked", id, index))
            }
            db.insert(SongArtistMap("competitor", competitor.id, 0))
            listOf(firstTag, secondTag).forEach { tag ->
                db.setLocalArtistLink(LocalArtistLink(tag.id, target.id, target.name, null, "revision-${tag.id}"))
            }
            repeat(2) { db.play("linked", 100, recent.plusMinutes(it.toLong())) }
            repeat(3) { db.play("competitor", 100, recent.plusMinutes(it.toLong())) }

            val artists = db.mostPlayedArtists(timestamp).first()
            assertEquals(listOf(competitor.id, target.id), artists.map { it.id })
            val linked = artists.last()
            assertFalse(linked.artist.isLocal)
            assertEquals(1, linked.songCount)
            assertEquals(1, linked.downloadCount)
        }
    }

    @Test fun anEmptyHistoryProducesNoSongOrArtistStatistics() = runBlocking {
        withDatabase { db ->
            val artist = ArtistEntity("LA-stats-never", "No history", isLocal = true)
            db.insert(artist)
            db.insert(song("never", local = true))
            db.insert(SongArtistMap("never", artist.id, 0))
            db.insert(PlayCountEntity("never", 2026, 9, 10))

            assertTrue(db.mostPlayedSongs(0L).first().isEmpty())
            assertTrue(db.mostPlayedArtists(0L).first().isEmpty())
        }
    }
}
