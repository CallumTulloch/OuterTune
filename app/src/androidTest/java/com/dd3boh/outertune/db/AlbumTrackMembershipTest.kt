package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.Event
import com.dd3boh.outertune.db.entities.PlayCountEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.AlbumPage
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Album-page order must stay separate from album associations discovered by playing a song. */
class AlbumTrackMembershipTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val savedAt = LocalDateTime.of(2026, 9, 19, 21, 27)
    private val downloadedAt = savedAt.plusMinutes(1)
    private val playlistId = "LP-album-track-preservation"

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try { block(MusicDatabase(internal)) } finally { internal.close() }
    }

    private fun page(albumId: String, title: String, tracks: List<Pair<String, String>>) = AlbumPage(
        album = AlbumItem(
            browseId = albumId,
            playlistId = "OLAK-test-$albumId",
            title = title,
            artists = emptyList(),
            thumbnail = "https://example.invalid/album.jpg",
        ),
        songs = tracks.map { (id, songTitle) ->
            SongItem(
                id = id,
                title = songTitle,
                artists = emptyList(),
                album = Album(title, albumId),
                duration = 180,
                thumbnail = "https://example.invalid/album.jpg",
            )
        },
        otherVersions = emptyList(),
    )

    private fun metadata(albumId: String, albumTitle: String, id: String, title: String) = MediaMetadata(
        id = id,
        title = title,
        artists = emptyList(),
        duration = 180,
        genre = null,
        album = MediaMetadata.Album(albumId, albumTitle),
    )

    private suspend fun MusicDatabase.assertTrackOrder(albumId: String, expected: List<String>) {
        assertTrue(albumById(albumId)!!.hasTrackList)
        assertEquals(expected, albumSongs(albumId).first().map { it.id })
        assertEquals(expected, albumWithSongs(albumId).first()!!.songs.map { it.id })
    }

    private fun MusicDatabase.saveWithUserState(song: MediaMetadata) {
        insert(song) {
            it.copy(inLibrary = savedAt, dateDownload = downloadedAt,
                localPath = "/downloads/${song.id}.webm", liked = true, likedDate = savedAt)
        }
        insert(PlaylistEntity(id = playlistId, name = "Keep these recordings", isLocal = true))
        insert(PlaylistSongMap(playlistId = playlistId, songId = song.id, position = 0))
        insert(Event(songId = song.id, timestamp = savedAt, playTime = 123_456L))
        insert(PlayCountEntity(song = song.id, year = 2026, month = 9, count = 7))
    }

    private suspend fun MusicDatabase.assertUserState(songId: String, albumId: String) {
        val stored = song(songId).first()!!
        assertEquals(savedAt, stored.song.inLibrary)
        assertEquals(downloadedAt, stored.song.dateDownload)
        assertEquals("/downloads/$songId.webm", stored.song.localPath)
        assertEquals(savedAt, stored.song.likedDate)
        assertTrue(stored.song.liked)
        assertEquals(albumId, stored.song.albumId)
        assertEquals(albumId, stored.album!!.id)
        assertEquals(listOf(albumId), albumIdsForSong(songId))
        assertEquals(listOf(songId), songsByCreateDateAsc().first().map { it.id })
        assertEquals(listOf(songId), downloadedSongs().first().map { it.id })
        assertEquals(listOf(songId), playlistSongs(playlistId).first().map { it.song.id })
        val event = events().first().single()
        assertEquals(songId, event.song.id)
        assertEquals(savedAt, event.event.timestamp)
        assertEquals(123_456L, event.event.playTime)
        assertEquals(7, getLifetimePlayCount(songId))
        assertEquals(listOf(albumId), savedAlbumsByCreateDateAsc().first().map { it.id })
        assertEquals(listOf(albumId), albumsInLibraryAsc().first().map { it.id })
        assertTrue(isAlbumOriginalContextEligible(albumId))
    }

    @Test
    fun chopSueyLegacyFirstTrackCollisionIsRepairedAndSurvivesDatabaseReopen() = runBlocking {
        // IDs and order observed in the supplied backup and the 2026-09-19 album response.
        val albumId = "MPREb_hscoNGfCKJW"
        val canonical = "-cid1qHuy_U"
        val alternate = "CSvFpBOe8eY"
        val second = "cmna-FAi6t0"
        val currentPage = page(albumId, "Chop Suey!", listOf(
            canonical to "Chop Suey!",
            second to "Sugar (Live at Irving Plaza, NYC, NY - January 1999)",
        ))
        val filename = "album-track-membership-reopen.db"
        context.deleteDatabase(filename)
        var internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
        try {
            var database = MusicDatabase(internal)
            database.saveWithUserState(currentPage.songs.first().toMediaMetadata())
            database.insert(metadata(albumId, "Chop Suey!", alternate, "Chop Suey!"))
            database.insert(currentPage.songs[1].toMediaMetadata())
            // Recreate the legacy rows, where unrelated playback was assigned album index zero.
            database.upsert(SongAlbumMap(canonical, albumId, 0))
            database.upsert(SongAlbumMap(alternate, albumId, 0))
            database.upsert(SongAlbumMap(second, albumId, 1))
            assertEquals(3, database.albumWithSongs(albumId).first()!!.songs.size)

            database.update(database.albumById(albumId)!!, currentPage)
            database.assertTrackOrder(albumId, listOf(canonical, second))
            database.assertUserState(canonical, albumId)
            assertNotNull(database.song(alternate).first())
            assertEquals(listOf(albumId), database.albumIdsForSong(alternate))

            // Replaying either identity, and saving the album again, cannot restore the extra row.
            repeat(2) {
                database.insert(metadata(albumId, "Chop Suey!", alternate, "Chop Suey!"))
                database.insert(currentPage.songs.first().toMediaMetadata())
                database.insert(currentPage)
                database.assertTrackOrder(albumId, listOf(canonical, second))
                database.assertUserState(canonical, albumId)
            }
            internal.close()
            internal = Room.databaseBuilder(context, InternalDatabase::class.java, filename).build()
            database = MusicDatabase(internal)
            database.assertTrackOrder(albumId, listOf(canonical, second))
            database.assertUserState(canonical, albumId)
            assertNotNull(database.song(alternate).first())
        } finally {
            internal.close()
            context.deleteDatabase(filename)
        }
    }

    @Test
    fun nevermindRefreshUsesAllThirteenCurrentTracksWithoutTheAlternateFirstRecording() = withDatabase { database ->
        val albumId = "MPREb_jPOYfjGgApr"
        val tracks = listOf(
            "ljUtuoFt-8c" to "Smells Like Teen Spirit",
            "ng_vqlKtxLs" to "In Bloom",
            "ZEMBDKMtHqM" to "Come As You Are",
            "ox_BG6sLPq8" to "Breed",
            "_oWUgfpGi0M" to "Lithium",
            "h5cZvNWxnho" to "Polly",
            "W64Bz9wXvRs" to "Territorial Pissings",
            "jFU6xiWbHT0" to "Drain You",
            "jwcq2Ci6jx0" to "Lounge Act",
            "UGA6zBEyo8Y" to "Stay Away",
            "INFH5bt8hxM" to "On A Plain",
            "SVSjcS-N224" to "Something In The Way",
            "MCBzJ2vjYyg" to "Endless, Nameless",
        )
        val currentPage = page(albumId, "Nevermind", tracks)
        val alternate = metadata(albumId, "Nevermind", "hTWKbfoikeg", tracks.first().second)
        database.saveWithUserState(currentPage.songs.first().toMediaMetadata())
        database.insert(alternate)
        database.upsert(SongAlbumMap(tracks.first().first, albumId, 0))
        database.upsert(SongAlbumMap(alternate.id, albumId, 0))

        database.update(database.albumById(albumId)!!, currentPage)
        database.assertTrackOrder(albumId, tracks.map { it.first })
        assertEquals(13, database.albumById(albumId)!!.songCount)
        database.assertUserState(tracks.first().first, albumId)
        assertNotNull(database.song(alternate.id).first())

        database.insert(alternate)
        database.assertTrackOrder(albumId, tracks.map { it.first })
    }

    @Test
    fun replacingCanonicalIdsPreservesSavedAlternateAndItsLibraryAlbum() = withDatabase { database ->
        val albumId = "MPRE-changing-track-ids"
        val firstPage = page(albumId, "Changing album", listOf(
            "old-first" to "First song", "old-second" to "Second song",
        ))
        val replacement = page(albumId, "Changing album", listOf(
            "new-second" to "Second song", "new-first" to "First song",
        ))
        database.saveWithUserState(firstPage.songs.first().toMediaMetadata())
        database.insert(firstPage)
        database.update(database.albumById(albumId)!!.copy(bookmarkedAt = savedAt))

        database.update(database.albumById(albumId)!!, replacement)
        val expected = replacement.songs.map { it.id }
        database.assertTrackOrder(albumId, expected)
        database.assertUserState("old-first", albumId)
        assertEquals(savedAt, database.albumById(albumId)!!.bookmarkedAt)
        assertEquals(0, database.albumWithSongs(albumId).first()!!.downloadCount)
        assertEquals(1, database.savedAlbumsByCreateDateAsc().first().single().downloadCount)
        assertNotNull(database.song("old-second").first())
        assertEquals(listOf(albumId), database.albumIdsForSong("old-second"))

        // The old saved/downloaded recording alone still keeps the album in the library.
        database.update(database.albumById(albumId)!!.copy(bookmarkedAt = null))
        database.assertUserState("old-first", albumId)
        firstPage.songs.forEach { database.insert(it.toMediaMetadata()) }
        replacement.songs.forEach { database.insert(it.toMediaMetadata()) }
        database.assertTrackOrder(albumId, expected)
        database.assertUserState("old-first", albumId)
    }

    @Test
    fun sameTitleCanonicalTracksRemainSeparateAndFollowPageOrder() = withDatabase { database ->
        val albumId = "MPRE-same-titles"
        // Insert in reverse order so neither title deduplication nor row-ID sorting can pass.
        val currentPage = page(albumId, "Repeating titles", listOf(
            "same-title-z" to "Intro", "same-title-a" to "Intro", "outro" to "Outro",
        ))
        currentPage.songs.reversed().forEach { database.insert(it.toMediaMetadata()) }
        database.insert(metadata(albumId, "Repeating titles", "unlisted-intro", "Intro"))
        database.insert(currentPage)

        database.assertTrackOrder(albumId, currentPage.songs.map { it.id })
        assertEquals(2, database.albumSongs(albumId).first().count { it.title == "Intro" })
        assertNotNull(database.song("unlisted-intro").first())

        // A newly encountered queue/playback identity must not gain album position zero.
        database.insert(metadata(albumId, "Repeating titles", "later-unlisted-intro", "Intro"))
        database.assertTrackOrder(albumId, currentPage.songs.map { it.id })
        assertNotNull(database.song("later-unlisted-intro").first())
        assertEquals(listOf(albumId), database.albumIdsForSong("later-unlisted-intro"))
    }

    @Test
    fun emptyPageLeavesTheConfirmedTrackListUnchanged() = withDatabase { database ->
        val albumId = "MPRE-incomplete-refresh"
        val currentPage = page(albumId, "Complete album", listOf(
            "complete-first" to "First", "complete-second" to "Second",
        ))
        database.insert(currentPage)
        val original = database.albumById(albumId)!!
        database.update(original, currentPage.copy(
            album = currentPage.album.copy(title = "Incomplete response"),
            songs = emptyList(),
        ))

        assertEquals(original, database.albumById(albumId))
        database.assertTrackOrder(albumId, currentPage.songs.map { it.id })
    }

    @Test
    fun unresolvedCanonicalSourcesCannotReplaceConfirmedAudioMembership() = withDatabase { database ->
        val albumId = "MPREb_dqWTncCjkSp"
        val audio = page(albumId, "Thriller", listOf("Kr4EQDVETuA" to "Billie Jean")).let {
            it.copy(songs = it.songs.map { song -> song.copy(duration = 295) })
        }
        database.saveWithUserState(audio.songs.single().toMediaMetadata())
        database.insert(audio)
        val original = database.albumById(albumId)!!
        val unresolved = page(albumId, "Unresolved response", listOf("Zi_XLOBDo_Y" to "Billie Jean"))
            .copy(hasUnresolvedTrackSources = true)

        // Both direct insertion and refresh are used by album/menu consumers.
        database.insert(unresolved)
        database.update(original, unresolved)

        assertEquals(original, database.albumById(albumId))
        database.assertTrackOrder(albumId, listOf("Kr4EQDVETuA"))
        assertEquals(295, database.albumById(albumId)!!.duration)
        database.assertUserState("Kr4EQDVETuA", albumId)
        assertEquals(null, database.song("Zi_XLOBDo_Y").first())
    }

    @Test
    fun firstUnresolvedShelfRemainsAvailableUntilCanonicalSourcesResolve() = withDatabase { database ->
        val albumId = "MPRE-first-unresolved"
        val shelf = page(albumId, "Restricted album", listOf("video-first" to "First", "video-second" to "Second"))
            .copy(hasUnresolvedTrackSources = true)
        database.insert(shelf)
        database.assertTrackOrder(albumId, shelf.songs.map { it.id })

        val resolved = page(albumId, "Restricted album", listOf("audio-first" to "First", "audio-second" to "Second"))
        database.update(database.albumById(albumId)!!, resolved)
        database.assertTrackOrder(albumId, resolved.songs.map { it.id })
    }

    @Test
    fun legacyPlaybackStubKeepsNewSongsUntilACompletePageEstablishesMembership() = withDatabase { database ->
        val albumId = "MPRE-legacy-playback-stub"
        val oldSong = metadata(albumId, "Old playback stub", "legacy-song", "First recording")
        val newSong = metadata(albumId, "Old playback stub", "newly-played-song", "Second recording")
        database.insert(oldSong)
        // Before version 28 even queue-only album associations used position zero.
        database.upsert(SongAlbumMap(oldSong.id, albumId, 0))
        assertFalse(database.albumById(albumId)!!.hasTrackList)

        database.insert(newSong)
        val expected = setOf(oldSong.id, newSong.id)
        assertEquals(expected, database.albumSongs(albumId).first().map { it.id }.toSet())
        assertEquals(expected, database.albumWithSongs(albumId).first()!!.songs.map { it.id }.toSet())
        assertFalse(database.albumById(albumId)!!.hasTrackList)

        // A real single-track page must still exclude other associations, even without a playlist ID.
        val singleTrackPage = page(albumId, "Old playback stub", listOf(newSong.id to newSong.title))
            .let { it.copy(album = it.album.copy(playlistId = null)) }
        database.update(database.albumById(albumId)!!, singleTrackPage)
        database.assertTrackOrder(albumId, listOf(newSong.id))
        assertEquals(listOf(albumId), database.albumIdsForSong(oldSong.id))
        assertNotNull(database.song(oldSong.id).first())
    }

    @Test
    fun bookmarkingAnOlderAlbumSnapshotCannotEraseConfirmedMembership() = withDatabase { database ->
        val albumId = "MPRE-stale-bookmark-snapshot"
        val alternate = metadata(albumId, "Bookmark race", "alternate-recording", "First")
        database.insert(alternate)
        val beforePage = database.albumById(albumId)!!
        assertFalse(beforePage.hasTrackList)
        val currentPage = page(albumId, "Bookmark race", listOf(
            "current-first" to "First", "current-second" to "Second",
        ))
        database.insert(currentPage)

        // The UI can hold an entity obtained before the album fetch completed.
        database.update(beforePage.copy(bookmarkedAt = savedAt))
        assertEquals(savedAt, database.albumById(albumId)!!.bookmarkedAt)
        database.assertTrackOrder(albumId, currentPage.songs.map { it.id })
        database.update(beforePage.copy(bookmarkedAt = null))
        assertEquals(null, database.albumById(albumId)!!.bookmarkedAt)
        database.assertTrackOrder(albumId, currentPage.songs.map { it.id })
        assertNotNull(database.song(alternate.id).first())
    }

    @Test
    fun playbackOnlyStubsAndLocalAlbumsKeepAllTheirAssociatedSongs() = withDatabase { database ->
        val stubId = "MPRE-playback-only"
        val stubTracks = listOf("stub-first", "stub-second").map {
            metadata(stubId, "Unfetched album", it, it)
        }
        stubTracks.forEach { database.insert(it) }
        val expectedStub = stubTracks.map { it.id }.toSet()
        assertEquals(expectedStub, database.albumSongs(stubId).first().map { it.id }.toSet())
        assertEquals(expectedStub, database.albumWithSongs(stubId).first()!!.songs.map { it.id }.toSet())

        val localTracks = listOf("LS-local-first", "LS-local-second").map { id ->
            metadata("LB-local-tracks", "Local album", id, "Same local title").copy(
                album = MediaMetadata.Album("LB-local-tracks", "Local album", isLocal = true),
                isLocal = true,
                localPath = "/music/Local album/$id.flac",
            )
        }
        localTracks.forEach { database.insert(it) }
        val localAlbumId = database.albumIdsForSong(localTracks.first().id).single()
        assertTrue(database.albumById(localAlbumId)!!.isLocal)
        assertEquals(2, database.albumById(localAlbumId)!!.songCount)
        val expectedLocal = localTracks.map { it.id }.toSet()
        assertEquals(expectedLocal, database.albumSongs(localAlbumId).first().map { it.id }.toSet())
        assertEquals(expectedLocal, database.albumWithSongs(localAlbumId).first()!!.songs.map { it.id }.toSet())

        // Even a legacy mixed index set must not treat local files as an online page snapshot.
        database.upsert(SongAlbumMap(localTracks.first().id, localAlbumId, -1))
        assertEquals(expectedLocal, database.albumSongs(localAlbumId).first().map { it.id }.toSet())
        assertEquals(expectedLocal, database.albumWithSongs(localAlbumId).first()!!.songs.map { it.id }.toSet())
    }
}
