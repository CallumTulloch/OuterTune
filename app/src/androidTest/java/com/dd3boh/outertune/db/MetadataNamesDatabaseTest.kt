package com.dd3boh.outertune.db

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataTargetEntity
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room queries: alternate spellings must not become duplicate library rows or join by name. */
class MetadataNamesDatabaseTest {
    private val savedAt = LocalDateTime.of(2026, 9, 9, 12, 0)

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            block(MusicDatabase(internal))
        } finally {
            internal.close()
        }
    }

    private fun name(kind: String, id: String, language: String, text: String, source: String = "search") =
        MetadataNameEntity(kind, id, language, text, source, observedAt = 1)

    @Test
    fun cacheRetainsBothLanguagesAndSameLanguageVariantsAcrossFailedRequests() = withDatabase { database ->
        val english = name("ARTIST", "UCNirvana", "en", "Nirvana")
        val japanese = name("ARTIST", "UCNirvana", "ja", "ニルヴァーナ")
        database.recordMetadataNames(listOf(english, japanese))
        database.recordMetadataNames(listOf(
            english.copy(observedAt = 2, sourcePriority = 10),
            english.copy(source = "artist-page"),
            japanese.copy(name = "Nirvana", source = "music-video"),
        ), MetadataFetchEntity("ARTIST", "UCNirvana", "en", MetadataFetchEntity.SUCCESS,
            updatedAt = 2, contextKey = "JP/public"))
        database.recordMetadataFetch(MetadataFetchEntity("ARTIST", "UCNirvana", "ja", MetadataFetchEntity.FAILED,
            updatedAt = 3, contextKey = "JP/public"))
        database.recordMetadataFetch(MetadataFetchEntity("ARTIST", "UCNirvana", "en", MetadataFetchEntity.EMPTY,
            updatedAt = 4, contextKey = "US/public"))

        val names = database.metadataNames("ARTIST", "UCNirvana")
        assertEquals(4, names.size)
        assertEquals(2L, names.single { it.language == "en" && it.source == "search" }.observedAt)
        assertEquals(setOf("ニルヴァーナ", "Nirvana"), names.filter { it.language == "ja" }.map { it.name }.toSet())
        assertEquals(MetadataFetchEntity.SUCCESS, database.metadataFetch("ARTIST", "UCNirvana", "en", "JP/public")!!.status)
        assertEquals(MetadataFetchEntity.EMPTY, database.metadataFetch("ARTIST", "UCNirvana", "en", "US/public")!!.status)
        assertEquals(MetadataFetchEntity.FAILED, database.metadataFetch("ARTIST", "UCNirvana", "ja", "JP/public")!!.status)
        assertEquals(3, database.metadataFetchStates("ARTIST", "UCNirvana").size)
        assertNull(database.metadataFetch("ARTIST", "UCNirvana", "ja", "US/public"))
        assertEquals(listOf(MetadataTargetEntity("ARTIST", "UCNirvana")), database.allMetadataTargets())
        assertNull(database.artistById("UCNirvana"))
        assertTrue(database.metadataLibraryTargets().first().isEmpty())
    }

    @Test
    fun repeatedDetailNamesKeepTheStrongestObservationsTimeAndEvidenceInEitherArrivalOrder() = withDatabase { database ->
        for (batched in listOf(false, true)) {
            for (reversed in listOf(false, true)) {
                val artistId = "UCpriority-$batched-$reversed"
                val evidence = "{\"source\":\"artist-detail\"}"
                val detail = name("ARTIST", artistId, "en", "Nirvana", "detail")
                    .copy(sourcePriority = 100, observedAt = 10, originEvidenceJson = evidence)
                val laterByline = detail.copy(sourcePriority = 20, observedAt = 20, originEvidenceJson = null)
                val observations = listOf(detail, laterByline).let { if (reversed) it.reversed() else it }
                if (batched) database.recordMetadataNames(observations)
                else observations.forEach { database.recordMetadataNames(listOf(it)) }
                // A still older byline must not alter the authoritative observation.
                database.recordMetadataNames(listOf(detail.copy(sourcePriority = 1, observedAt = 1, originEvidenceJson = null)))

                val stored = database.metadataNames("ARTIST", artistId).single()
                assertEquals("batch=$batched reversed=$reversed", 100, stored.sourcePriority)
                assertEquals(10L, stored.observedAt)
                assertEquals(evidence, stored.originEvidenceJson)
            }
        }
    }

    @Test
    fun laterEmbeddedSpellingCannotMakeAnOldHeaderWinOverTheLatestLocalizedHeader() = withDatabase { database ->
        val old = name("ARTIST", "UC-localized", "ja", "Romanized", "detail")
            .copy(sourcePriority = 100, observedAt = 10)
        val current = old.copy(name = "日本語の正式名", observedAt = 20)
        database.recordMetadataNames(listOf(old, current))
        database.recordMetadataNames(listOf(old.copy(sourcePriority = 20, observedAt = 30)))
        val rows = database.metadataNames("ARTIST", "UC-localized")
        assertEquals("日本語の正式名", rows.first().name)
        assertEquals(setOf("Romanized", "日本語の正式名"), rows.map { it.name }.toSet())
        database.recordMetadataNames(listOf(old.copy(observedAt = 40)))
        assertEquals("Romanized", database.metadataNames("ARTIST", "UC-localized").first().name)
    }

    @Test
    fun aliasesMatchSavedTitlesAndStableArtistIdsWithoutDuplicatingResultsOrCounts() = withDatabase { database ->
        database.insert(SongEntity("track", "Smells Like Teen Spirit", localPath = null, dateDownload = savedAt))
        database.insert(SongEntity("second-track", "Another song", localPath = null, inLibrary = savedAt))
        database.insert(SongEntity("unsaved-track", "Hidden song", localPath = null))
        database.insert(SongEntity("folder-track", "Folder title", localPath = "/music/local.mp3",
            isLocal = true, inLibrary = savedAt))
        database.insert(ArtistEntity("LAnirvana", "Nirvana", onlineId = "UCNirvana"))
        database.insert(ArtistEntity("UCGuest", "Guest"))
        database.insert(ArtistEntity("UClocal", "Folder artist", isLocal = true))
        database.insert(SongArtistMap("track", "LAnirvana", 0))
        database.insert(SongArtistMap("second-track", "LAnirvana", 0))
        database.insert(SongArtistMap("track", "UCGuest", 1))
        database.insert(SongArtistMap("folder-track", "UClocal", 0))
        database.insert(AlbumEntity("album", title = "Nevermind", songCount = 2, duration = 360))
        database.insert(SongAlbumMap("track", "album", 0))
        database.insert(SongAlbumMap("second-track", "album", 1))
        database.recordMetadataNames(listOf(
            name("SONG", "track", "ja", "スメルズ・ライク・ティーン・スピリット"),
            name("SONG", "track", "ja", "スメルズ・ライク・ティーン・スピリット", "album"),
            name("SONG", "unsaved-track", "ja", "スメルズ"),
            name("SONG", "folder-track", "ja", "スメルズ"),
            name("SONG", "only-cached", "ja", "スメルズ"),
            name("ARTIST", "UCNirvana", "ja", "ニルヴァーナ"),
            name("ARTIST", "UCNirvana", "ja", "ニルヴァーナ", "album"),
            name("ARTIST", "UCNirvana", "ja", "共通別名"),
            name("ARTIST", "UCGuest", "ja", "共通別名"),
            name("ARTIST", "UCUnrelated", "ja", "ニルヴァーナ"),
            name("ARTIST", "UClocal", "ja", "ニルヴァーナ"),
            name("ALBUM", "album", "ja", "ネヴァーマインド"),
            name("ALBUM", "album", "ja", "ネヴァーマインド", "song"),
        ))

        assertEquals(listOf("track"), database.searchSongs("スメルズ").first().map { it.id })
        assertEquals(listOf("track"), database.searchSongs("Smells").first().map { it.id })
        assertEquals(setOf("track", "unsaved-track"), database.searchSongsInDb("スメルズ").first().map { it.id }.toSet())
        assertEquals(listOf("LAnirvana"), database.searchArtists("ニルヴァーナ").first().map { it.id })
        assertEquals(listOf("LAnirvana"), database.searchArtists("Nirvana").first().map { it.id })
        val artist = database.searchArtists("ニルヴァーナ").first().single()
        assertEquals(2, artist.songCount)
        assertEquals(1, artist.downloadCount)
        val artistSongs = database.searchArtistSongs("共通別名").first()
        assertEquals(2, artistSongs.size)
        assertEquals(setOf("track", "second-track"), artistSongs.map { it.id }.toSet())
        val album = database.searchAlbums("ネヴァーマインド").first().single()
        assertEquals("album", album.id)
        assertEquals(1, album.downloadCount)
        assertEquals("Nirvana", database.artistById("LAnirvana")!!.name)
        assertEquals("Smells Like Teen Spirit", database.song("track").first()!!.title)
        assertEquals("Nevermind", database.albumById("album")!!.title)
        assertFalse(database.songExists("only-cached"))
    }

    @Test
    fun persistedTargetsIncludeUnsavedOnlineItemsButExcludeLocalAndUnresolvedArtists() = withDatabase { database ->
        database.insert(SongEntity("online-track", "Online", localPath = null))
        database.insert(SongEntity("folder-track", "Local", localPath = "/music/local.mp3", isLocal = true))
        database.insert(AlbumEntity("online-album", title = "Online", songCount = 1, duration = 180))
        database.insert(AlbumEntity("folder-album", title = "Local", songCount = 1, duration = 180, isLocal = true))
        database.insert(ArtistEntity("LAstable", "Artist", onlineId = "UCremote"))
        database.insert(ArtistEntity("UCremote", "Artist old ref"))
        database.insert(ArtistEntity("LAunresolved", "Artist"))
        database.insert(ArtistEntity("UClocal", "Local artist", isLocal = true))
        assertEquals(setOf(
            MetadataTargetEntity("SONG", "online-track"),
            MetadataTargetEntity("ALBUM", "online-album"),
            MetadataTargetEntity("ARTIST", "UCremote"),
        ), database.metadataLibraryTargets().first().toSet())
    }

    @Test
    fun targetForeignKeysRejectOrphansAndCascadeOnlyWithinTheMetadataCache() = withDatabase { database ->
        val orphan = runCatching {
            database.upsertMetadataNames(listOf(name("SONG", "same-id", "en", "Orphan")))
        }.exceptionOrNull()
        assertTrue(orphan is SQLiteConstraintException)
        database.insert(SongEntity("same-id", "Library song", localPath = null, inLibrary = savedAt))
        database.recordMetadataNames(listOf(
            name("SONG", "same-id", "en", "Song name"),
            name("ALBUM", "same-id", "en", "Album name"),
        ), MetadataFetchEntity("SONG", "same-id", "en", MetadataFetchEntity.SUCCESS))
        database.deleteMetadataTarget("SONG", "same-id")
        assertNull(database.metadataTarget("SONG", "same-id"))
        assertTrue(database.metadataNames("SONG", "same-id").isEmpty())
        assertTrue(database.metadataFetchStates("SONG", "same-id").isEmpty())
        assertEquals("Album name", database.metadataNames("ALBUM", "same-id").single().name)
        assertEquals("Library song", database.song("same-id").first()!!.title)
    }

    @Test
    fun addingAnAliasInvalidatesAnAlreadyObservedLocalSearch() = withDatabase { database ->
        database.insert(SongEntity("track", "Original title", localPath = null, inLibrary = savedAt))
        coroutineScope {
            val observedEmpty = CompletableDeferred<Unit>()
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) {
                    database.searchSongs("別名").onEach {
                        if (it.isEmpty()) observedEmpty.complete(Unit)
                    }.first { it.isNotEmpty() }
                }
            }
            withTimeout(5_000) { observedEmpty.await() }
            database.recordMetadataNames(listOf(name("SONG", "track", "ja", "別名")))
            assertEquals(listOf("track"), result.await().map { it.id })
        }
    }
}
