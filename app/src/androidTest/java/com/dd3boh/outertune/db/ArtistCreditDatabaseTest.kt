package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.artistCreditFromJson
import com.dd3boh.outertune.models.withArtistCredit
import com.dd3boh.outertune.models.toStoredJson
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real SQLite/Room checks for transactions, aliases and persistence across reopen. */
class ArtistCreditDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val songId = "artist-credit-test-track"
    private val raw = ArtistCredit("Alpha & Beta", emptyList(), ArtistCreditStatus.RAW, "test", "ja")

    @Test
    fun applicationBuilderOpensFreshDatabaseAndResetsVersion22() = runBlocking {
        val filename = "artist-credit-application-builder.db"
        context.deleteDatabase(filename)
        try {
            InternalDatabase.newTestInstance(context, filename).let { database ->
                try {
                    database.insert(metadata(raw))
                    assertNotNull(database.song(songId).first())
                } finally { database.close() }
            }
            // The application's actual builder must preserve a current database on reopening.
            InternalDatabase.newTestInstance(context, filename).let { database ->
                try { assertNotNull(database.song(songId).first()) } finally { database.close() }
            }
            context.deleteDatabase(filename)
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(filename), null).use {
                it.execSQL("CREATE TABLE old_artist_test (name TEXT)")
                it.version = 22
            }
            InternalDatabase.newTestInstance(context, filename).let { database ->
                try {
                    assertNull(database.song(songId).first())
                    assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, database.openHelper.readableDatabase.version)
                    database.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE name = 'old_artist_test'").use {
                        assertFalse(it.moveToFirst())
                    }
                } finally { database.close() }
            }
        } finally { context.deleteDatabase(filename) }
    }

    private fun metadata(credit: ArtistCredit) = MediaMetadata(
        id = songId, title = "Credit transaction test", artists = emptyList(), duration = 180,
        genre = null, album = MediaMetadata.Album("MPRE-test", "Original album"),
    ).withArtistCredit(credit)

    @Test
    fun emptyPlaceholderPreservesLegacySongRelationsAndUnparsedCredit() = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            val database = MusicDatabase(internal)
            val artistId = "UCcreditJapaneseLegacy"
            database.insert(ArtistEntity(artistId, "椎名林檎", onlineId = artistId))
            val placeholder = ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "context-refresh", "en")
            listOf(null, "{unparsed-credit").forEachIndexed { index, storedJson ->
                val id = "legacy-credit-$index"
                val saved = SongEntity(id, "Legacy track", 180, liked = true, localPath = null,
                    inLibrary = LocalDateTime.of(2026, 9, 12, 12, 0), artistCreditJson = storedJson)
                database.insert(saved)
                database.insert(SongArtistMap(id, artistId, 3))

                // A background resolver can publish an empty request state before any reply.
                database.applyArtistCredit(id, placeholder)
                assertEquals(saved, database.songForArtistCredit(id))
                assertEquals(listOf(artistId), database.artistIdsForSong(id))

                // Playlist sync re-inserts existing tracks through the production metadata path.
                database.insert(metadata(placeholder.copy(language = "ja", rawText = "  ")).copy(id = id, album = null))
                assertEquals(saved, database.songForArtistCredit(id))
                assertEquals(listOf("椎名林檎"), database.song(id).first()!!.artists.map { it.name })
                database.openHelper.readableDatabase.query(
                    "SELECT position FROM song_artist_map WHERE songId = ?", arrayOf(id),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                    assertFalse(cursor.moveToNext())
                }
                // Favourite snapshots must also keep a legacy null credit rather than install a placeholder.
                database.update(saved.copy(liked = false, artistCreditJson = placeholder.toStoredJson()))
                assertEquals(saved.copy(liked = false), database.songForArtistCredit(id))
                assertEquals(listOf(artistId), database.artistIdsForSong(id))
            }
        } finally {
            internal.close()
        }
    }

    @Test
    fun emptyPlaceholderPreservesLegacyAlbumRelationsAndUnparsedCredit() = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            val database = MusicDatabase(internal)
            val artistId = "UCcreditJapaneseAlbumLegacy"
            database.insert(ArtistEntity(artistId, "椎名林檎", onlineId = artistId))
            listOf(null, "{unparsed-credit").forEachIndexed { index, storedJson ->
                val id = "MPRE-legacy-credit-$index"
                val saved = AlbumEntity(id, title = "Legacy album", songCount = 1, duration = 180,
                    bookmarkedAt = LocalDateTime.of(2026, 9, 12, 12, 0), artistCreditJson = storedJson)
                database.insert(saved)
                database.insert(AlbumArtistMap(id, artistId, 4))

                database.applyAlbumArtistCredit(id,
                    ArtistCredit("  ", emptyList(), ArtistCreditStatus.RAW, "context-refresh", "en"))

                assertEquals(saved, database.albumById(id))
                assertEquals(listOf(artistId), database.albumArtistIdsForAlbum(id))
                assertEquals("椎名林檎", database.artistById(artistId)!!.name)
                database.openHelper.readableDatabase.query(
                    "SELECT `order` FROM album_artist_map WHERE albumId = ?", arrayOf(id),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(4, cursor.getInt(0))
                    assertFalse(cursor.moveToNext())
                }
                database.update(saved.copy(bookmarkedAt = null, artistCreditJson =
                    ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "context-refresh", "en").toStoredJson()))
                assertEquals(saved.copy(bookmarkedAt = null), database.albumById(id))
                assertEquals(listOf(artistId), database.albumArtistIdsForAlbum(id))
            }
        } finally {
            internal.close()
        }
    }

    @Test
    fun japaneseCompleteCreditSurvivesThinPlaylistReinsertion() = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            val database = MusicDatabase(internal)
            val credit = ArtistCredit("椎名林檎", listOf(Artist("椎名林檎", "UCcreditJapaneseComplete")),
                ArtistCreditStatus.COMPLETE, "playlist", "ja")
            database.insert(metadata(credit))
            val saved = database.songForArtistCredit(songId)!!
            val artistIds = database.artistIdsForSong(songId)
            listOf(
                ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "context-refresh", "ja"),
                ArtistCredit("椎名林檎", emptyList(), ArtistCreditStatus.RAW, "playlist", "ja"),
                ArtistCredit("Sheena Ringo", emptyList(), ArtistCreditStatus.RAW, "playlist", "en"),
            ).forEach { thin ->
                database.insert(metadata(thin))
                assertEquals(saved, database.songForArtistCredit(songId))
                assertEquals(artistIds, database.artistIdsForSong(songId))
                assertEquals(listOf("椎名林檎"), database.song(songId).first()!!.artists.map { it.name })
            }
        } finally {
            internal.close()
        }
    }

    @Test
    fun partialCompleteAndLateIdPreserveTrackAndProjectTheExistingOnlineIdentity() = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            val database = MusicDatabase(internal)
            database.insert(metadata(raw))
            assertTrue(database.song(songId).first()!!.artists.isEmpty())
            val addedAt = LocalDateTime.of(2026, 9, 7, 12, 0)
            database.update(database.songForArtistCredit(songId)!!.copy(liked = true, inLibrary = addedAt))

            val partial = raw.copy(status = ArtistCreditStatus.PARTIAL, artists = listOf(Artist("Alpha", null)))
            database.applyArtistCredit(songId, partial)
            assertEquals(listOf("Alpha"), database.song(songId).first()!!.artists.map { it.name })
            assertEquals("Alpha & Beta", database.artistCredit(songId).first()!!.rawText)
            val alphaRef = database.artistCredit(songId).first()!!.artists.single().ref!!

            val complete = partial.copy(status = ArtistCreditStatus.COMPLETE,
                artists = listOf(Artist("Alpha", null, alphaRef), Artist("Beta", null)))
            database.applyArtistCredit(songId, complete)
            val betaRef = database.artistCredit(songId).first()!!.artists[1].ref!!
            database.insert(ArtistEntity(id = "UCcreditTestBeta", name = "Beta page name", onlineId = "UCcreditTestBeta"))
            database.applyArtistCredit(songId, complete.copy(artists = listOf(
                Artist("Alpha", null, alphaRef), Artist("Beta", "UCcreditTestBeta", betaRef),
            )))

            val resolved = database.artistCredit(songId).first()!!
            assertEquals(listOf("Alpha", "Beta"), resolved.artists.map { it.name })
            assertEquals(alphaRef, resolved.artists[0].ref)
            assertEquals(betaRef, resolved.artists[1].ref)
            assertEquals("UCcreditTestBeta", resolved.artists[1].id)
            assertEquals("UCcreditTestBeta", database.resolveArtistId("UCcreditTestBeta"))
            assertEquals("UCcreditTestBeta", database.artist(betaRef).first()!!.id)
            assertEquals("Beta page name", database.artist(betaRef).first()!!.title)
            assertEquals("Beta", database.artistEntityByExactId(betaRef)!!.name)
            assertNotNull(database.artistEntityByExactId(betaRef)!!.albumGroupId)
            assertNotNull(database.artistEntityByExactId("UCcreditTestBeta"))
            assertEquals(listOf(alphaRef, betaRef), database.artistIdsForSong(songId))
            assertEquals(2, database.song(songId).first()!!.artists.size)

            // A later search/save with only the original string cannot undo accepted names or IDs.
            database.insert(metadata(raw))
            val saved = database.songForArtistCredit(songId)!!
            assertTrue(saved.liked)
            assertEquals(addedAt, saved.inLibrary)
            assertEquals(180, saved.duration)
            assertEquals("MPRE-test", saved.albumId)
            assertEquals("Original album", saved.albumName)
            assertEquals(resolved, artistCreditFromJson(saved.artistCreditJson))
            // A menu opened before enrichment can still submit a favourite/library snapshot.
            database.update(saved.copy(liked = false, artistCreditJson = raw.toStoredJson()))
            assertFalse(database.songForArtistCredit(songId)!!.liked)
            assertEquals(resolved, database.artistCredit(songId).first())
            // An unchanged JSON value must not skip repairing a missing relation set.
            database.deleteSongArtistMaps(songId)
            database.applyArtistCredit(songId, resolved)
            assertEquals(listOf(alphaRef, betaRef), database.artistIdsForSong(songId))
        } finally {
            internal.close()
        }
    }

    @Test
    fun adoptedIdlessNamesSurviveReopeningTheDatabase() = runBlocking {
        val filename = "artist-credit-instrumentation.db"
        context.deleteDatabase(filename)
        val credit = raw.copy(status = ArtistCreditStatus.COMPLETE, artists = listOf(
            Artist("Alpha", null), Artist("Beta", null),
        ))
        var internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
        try {
            MusicDatabase(internal).insert(metadata(credit))
            internal.close()
            internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
            val database = MusicDatabase(internal)
            val restored = database.artistCredit(songId).first()!!
            assertEquals(ArtistCreditStatus.COMPLETE, restored.status)
            assertEquals(listOf("Alpha", "Beta"), restored.artists.map { it.name })
            assertEquals(ArtistIdentity.stableId(songId, "Alpha"), restored.artists[0].ref)
            assertTrue(restored.artists.all { it.id == null })
            assertEquals(2, database.song(songId).first()!!.artists.size)
        } finally {
            internal.close()
            context.deleteDatabase(filename)
        }
    }

    @Test
    fun lateIdProjectsTracksAndAlbumCreditsTogetherWithoutRewritingTheirSourceRefs() = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            val database = MusicDatabase(internal)
            val remoteId = "UCcreditSharedBeta"
            val otherSongId = "other-credit-track"
            val otherAlbumId = "MPRE-other-credit-album"
            val bookmark = LocalDateTime.of(2026, 9, 7, 13, 0)
            val otherCredit = ArtistCredit("Beta alternate spelling", listOf(Artist("Beta alternate spelling", remoteId)),
                ArtistCreditStatus.COMPLETE, "other-track", "ja")
            database.insert(MediaMetadata(otherSongId, "Other track", emptyList(), 120, genre = null,
                album = MediaMetadata.Album(otherAlbumId, "Other album")).withArtistCredit(otherCredit)) {
                it.copy(inLibrary = bookmark)
            }
            val albumCredit = ArtistCredit("Album Beta", listOf(Artist("Album Beta", remoteId)),
                ArtistCreditStatus.COMPLETE, "album-header", "ja")
            database.applyAlbumArtistCredit(otherAlbumId, albumCredit)
            database.update(database.artistById(remoteId)!!.copy(bookmarkedAt = bookmark, thumbnailUrl = "kept-image"))
            val originalOnline = database.artistEntityByExactId(remoteId)!!
            val originalTrack = database.songForArtistCredit(otherSongId)!!
            val originalAlbum = database.albumById(otherAlbumId)!!

            val idless = ArtistCredit("Beta", listOf(Artist("Beta", null)), ArtistCreditStatus.COMPLETE, "track", "ja")
            database.insert(metadata(idless)) { it.copy(inLibrary = bookmark) }
            val stableId = database.artistCredit(songId).first()!!.artists.single().ref!!
            val originalSource = database.artistEntityByExactId(stableId)!!
            database.applyArtistCredit(songId, idless.copy(artists = listOf(Artist("Beta", remoteId, stableId))))

            assertEquals(remoteId, database.resolveArtistId(remoteId))
            assertEquals(setOf(stableId, remoteId), database.artistsBySource(false).map { it.id }.toSet())
            assertEquals(listOf(stableId), database.artistIdsForSong(songId))
            assertEquals(listOf(remoteId), database.artistIdsForSong(otherSongId))
            assertEquals(listOf(remoteId), database.albumArtistIdsForAlbum(otherAlbumId))
            val restoredTrack = database.artistCredit(otherSongId).first()!!
            assertEquals("Beta alternate spelling", restoredTrack.rawText)
            assertEquals("Beta alternate spelling", restoredTrack.artists.single().name)
            assertEquals(remoteId, restoredTrack.artists.single().ref)
            val restoredAlbum = database.albumById(otherAlbumId)!!.artistCredit!!
            assertEquals("Album Beta", restoredAlbum.rawText)
            assertEquals("Album Beta", restoredAlbum.artists.single().name)
            assertEquals(remoteId, restoredAlbum.artists.single().ref)
            assertEquals(originalOnline, database.artistEntityByExactId(remoteId))
            assertEquals(originalSource.copy(onlineId = remoteId), database.artistEntityByExactId(stableId))
            assertEquals(originalTrack, database.songForArtistCredit(otherSongId))
            assertEquals(originalAlbum, database.albumById(otherAlbumId))
            assertEquals(stableId, database.artistCredit(songId).first()!!.artists.single().ref)
            assertEquals(remoteId, database.artistCredit(songId).first()!!.artists.single().id)
            for (route in listOf(remoteId, stableId)) {
                val visible = database.artist(route).first()!!
                assertEquals(remoteId, visible.id)
                assertEquals("Beta alternate spelling", visible.title)
                assertEquals(bookmark, visible.artist.bookmarkedAt)
                assertEquals("kept-image", visible.thumbnailUrl)
                assertEquals(2, visible.songCount)
                assertEquals(setOf(songId, otherSongId), database.artistSongsPreview(route, 99).first().map { it.id }.toSet())
            }
            assertEquals(listOf(remoteId), database.savedArtistsByCreateDateAsc().first().map { it.id })
            assertEquals(setOf(otherAlbumId, "MPRE-test"), database.artistAlbumsPreview(remoteId, 99)
                .first().map { it.id }.toSet())
        } finally {
            internal.close()
        }
    }

    @Test
    fun mergingDuplicateRelationsKeepsTheEarlierSongAndAlbumPosition() = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            val database = MusicDatabase(internal)
            val remoteId = "UCcreditCollision"
            val adopted = ArtistCredit("Beta", listOf(Artist("Beta", null)), ArtistCreditStatus.COMPLETE, "track", "ja")
            database.insert(metadata(adopted))
            val stableId = database.artistCredit(songId).first()!!.artists.single().ref!!
            database.insert(ArtistEntity(remoteId, "Beta page", onlineId = remoteId))
            database.deleteSongArtistMaps(songId)
            database.insert(SongArtistMap(songId, stableId, 5))
            database.insert(SongArtistMap(songId, remoteId, 2))
            val albumId = "MPRE-credit-collision"
            database.insert(AlbumEntity(albumId, title = "Collision album", songCount = 1, duration = 180,
                artistCreditJson = adopted.copy(artists = listOf(Artist("Beta", remoteId, remoteId))).toStoredJson()))
            database.insert(AlbumArtistMap(albumId, stableId, 7))
            database.insert(AlbumArtistMap(albumId, remoteId, 3))

            database.mergeArtistIdentity(database.artistById(stableId)!!, database.artistById(remoteId)!!)

            assertEquals(listOf(stableId), database.artistIdsForSong(songId))
            assertEquals(listOf(stableId), database.albumArtistIdsForAlbum(albumId))
            database.openHelper.readableDatabase.query(
                "SELECT position FROM song_artist_map WHERE songId = ? AND artistId = ?", arrayOf(songId, stableId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
                assertFalse(cursor.moveToNext())
            }
            database.openHelper.readableDatabase.query(
                "SELECT `order` FROM album_artist_map WHERE albumId = ? AND artistId = ?", arrayOf(albumId, stableId),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3, cursor.getInt(0))
                assertFalse(cursor.moveToNext())
            }
            assertEquals(stableId, database.albumById(albumId)!!.artistCredit!!.artists.single().ref)
            assertEquals(stableId, database.resolveArtistId(remoteId))
            assertEquals(1, database.artistsBySource(false).size)
        } finally {
            internal.close()
        }
    }

    @Test
    fun albumRawCreditDoesNotBorrowTrackArtistsAndThinAlbumRefreshDoesNotRegress() = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            val database = MusicDatabase(internal)
            val trackArtistId = "UCcreditGuest"
            val trackCredit = ArtistCredit("Guest", listOf(Artist("Guest", trackArtistId)),
                ArtistCreditStatus.COMPLETE, "track", "ja")
            database.insert(metadata(trackCredit)) { it.copy(inLibrary = LocalDateTime.of(2026, 9, 7, 14, 0)) }
            val albumId = "MPRE-test"
            val albumRaw = ArtistCredit("Album artist", emptyList(), ArtistCreditStatus.RAW, "album-header", "ja")
            database.applyAlbumArtistCredit(albumId, albumRaw)
            assertEquals(albumRaw, database.albumById(albumId)!!.artistCredit)
            assertTrue(database.albumArtistIdsForAlbum(albumId).isEmpty())
            assertTrue(database.artistAlbumsPreview(trackArtistId).first().isEmpty())
            assertEquals(listOf(trackArtistId), database.artistIdsForSong(songId))

            val bookmark = LocalDateTime.of(2026, 9, 7, 15, 0)
            database.update(database.albumById(albumId)!!.copy(bookmarkedAt = bookmark))
            val albumComplete = albumRaw.copy(status = ArtistCreditStatus.COMPLETE,
                artists = listOf(Artist("Album artist", "UCcreditAlbumArtist")))
            database.applyAlbumArtistCredit(albumId, albumComplete)
            val accepted = database.albumById(albumId)!!.artistCredit
            database.applyAlbumArtistCredit(albumId, albumRaw)
            assertEquals(accepted, database.albumById(albumId)!!.artistCredit)
            assertEquals(bookmark, database.albumById(albumId)!!.bookmarkedAt)
            assertEquals(listOf("UCcreditAlbumArtist"), database.albumArtistIdsForAlbum(albumId))
            database.update(database.albumById(albumId)!!.copy(artistCreditJson = albumRaw.toStoredJson()))
            assertEquals(accepted, database.albumById(albumId)!!.artistCredit)
            database.deleteAlbumArtistMaps(albumId)
            database.applyAlbumArtistCredit(albumId, accepted!!)
            assertEquals(listOf("UCcreditAlbumArtist"), database.albumArtistIdsForAlbum(albumId))
            assertTrue(database.artistAlbumsPreview(trackArtistId).first().isEmpty())
            assertEquals(listOf(albumId), database.artistAlbumsPreview("UCcreditAlbumArtist").first().map { it.id })
        } finally {
            internal.close()
        }
    }
}
