package com.dd3boh.outertune.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.RecentActivityEntity
import com.dd3boh.outertune.db.entities.RecentActivityType
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.extensions.isAutoSyncEnabled
import com.dd3boh.outertune.models.toStoredJson
import com.zionhuang.innertube.YouTubeSyncPolicy
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses isolated Room data; never changes account state, preferences, or the application's database. */
class YouTubeSyncDisabledTest {
    private class NoAccountOrNetworkContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = error("Account storage must not be consulted")
        override fun getFilesDir(): File = error("Application files must not be consulted")
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            error("Shared preferences must not be consulted")
        override fun getSystemService(name: String): Any = error("Network services must not be consulted")
    }

    private suspend fun withDatabase(block: suspend (MusicDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            block(MusicDatabase(internal))
        } finally {
            internal.close()
        }
    }

    private fun assertRemoteIdle(sync: SyncUtils) {
        listOf(sync.isSyncingRemoteLikedSongs, sync.isSyncingRemoteSongs, sync.isSyncingRemoteAlbums,
            sync.isSyncingRemoteArtists, sync.isSyncingRemotePlaylists, sync.isSyncingRecentActivity)
            .forEach { assertFalse(it.value) }
    }

    @Test
    fun disabledRemoteEntryPointsPreserveSavedNamesRelationsAndFlags() = runBlocking {
        // Do not run remote entry points if this test is accidentally used with an enabled policy.
        assertFalse(YouTubeSyncPolicy.ENABLED)
        withDatabase { database ->
            val savedAt = LocalDateTime.of(2026, 9, 12, 12, 0)
            val artist = ArtistEntity("UCsyncDisabledArtist", "保存済みアーティスト", bookmarkedAt = savedAt,
                lastUpdateTime = savedAt)
            val credit = ArtistCredit(artist.name, listOf(Artist(artist.name, artist.id)),
                ArtistCreditStatus.COMPLETE, "saved-track", "ja")
            val album = AlbumEntity("MPRE-sync-disabled", title = "保存済みアルバム", songCount = 1,
                duration = 180, bookmarkedAt = savedAt, lastUpdateTime = savedAt,
                artistCreditJson = credit.toStoredJson())
            val song = SongEntity("sync-disabled-track", "保存済みの曲", duration = 180, localPath = null,
                liked = true, likedDate = savedAt, inLibrary = savedAt, dateDownload = savedAt,
                albumId = album.id, albumName = album.title, artistCreditJson = credit.toStoredJson())
            val playlist = PlaylistEntity(id = "LP-sync-disabled", name = "保存済みプレイリスト",
                browseId = "VL-sync-disabled", bookmarkedAt = savedAt)
            val activity = RecentActivityEntity(album.id, album.title, thumbnail = null, explicit = false,
                shareLink = "https://music.youtube.com/browse/${album.id}", type = RecentActivityType.ALBUM,
                playlistId = null, radioPlaylistId = null, shufflePlaylistId = null, date = savedAt)
            database.insert(artist)
            database.insert(album)
            database.insert(song)
            database.insert(SongArtistMap(song.id, artist.id, 2))
            database.insert(AlbumArtistMap(album.id, artist.id, 3))
            database.insert(playlist)
            database.insert(PlaylistSongMap(playlistId = playlist.id, songId = song.id, position = 4,
                setVideoId = "preserved-set-video-id"))
            database.insert(activity)
            val playlistMaps = database.songMapsToPlaylist(playlist.id, 0)
            var downloadRefreshCalls = 0
            val sync = SyncUtils(database, { downloadRefreshCalls++; true }, NoAccountOrNetworkContext())

            assertRemoteIdle(sync)
            for (force in listOf(false, true)) {
                assertFalse(sync.tryAutoSync(force))
                assertFalse(sync.syncRemoteLikedSongs(force))
                assertFalse(sync.syncRemoteSongs(force))
                assertFalse(sync.syncRemoteAlbums(force))
                assertFalse(sync.syncRemoteArtists(force))
                assertFalse(sync.syncRemotePlaylists(force))
                assertFalse(sync.syncRecentActivity(force))
                assertRemoteIdle(sync)
            }
            assertFalse(sync.syncPlaylist(playlist.browseId!!, playlist.id))
            sync.likeSong(song.copy(liked = false))
            sync.changeInLibrary(song.copy(inLibrary = null))

            assertRemoteIdle(sync)
            assertFalse(sync.isRefreshingLibrary.value)
            assertEquals(0, downloadRefreshCalls)
            assertEquals(song, database.songForArtistCredit(song.id))
            assertEquals(listOf(song.id), database.songsByRowIdAsc().first().map { it.id })
            assertEquals(listOf(artist.id), database.artistIdsForSong(song.id))
            assertEquals(artist, database.artistById(artist.id))
            assertEquals(album, database.albumById(album.id))
            assertEquals(listOf(artist.id), database.albumArtistIdsForAlbum(album.id))
            assertEquals(playlist, database.playlist(playlist.id).first()!!.playlist)
            assertEquals(playlistMaps, database.songMapsToPlaylist(playlist.id, 0))
            assertEquals(listOf(activity), database.recentActivity().first())
        }
    }

    @Test
    fun libraryRefreshStillReconcilesDownloadsAndSkipsRemoteCallbacks() = runBlocking {
        assertFalse(YouTubeSyncPolicy.ENABLED)
        withDatabase { database ->
            var downloadRefreshCalls = 0
            var remoteRefreshCalls = 0
            var downloadSuccess = true
            lateinit var sync: SyncUtils
            sync = SyncUtils(database, {
                downloadRefreshCalls++
                assertTrue(sync.isRefreshingLibrary.value)
                assertRemoteIdle(sync)
                downloadSuccess
            }, NoAccountOrNetworkContext())

            for (succeeds in listOf(true, false, true)) {
                downloadSuccess = succeeds
                assertEquals(succeeds, sync.refreshLibrary { remoteRefreshCalls++; false })
                assertFalse(sync.isRefreshingLibrary.value)
                assertRemoteIdle(sync)
            }
            assertEquals(3, downloadRefreshCalls)
            assertEquals(0, remoteRefreshCalls)
        }
    }

    @Test
    fun automaticSyncIsDisabledWithoutRequiringAnAccountOrAndroidServices() {
        assertFalse(YouTubeSyncPolicy.ENABLED)
        assertFalse(NoAccountOrNetworkContext().isAutoSyncEnabled())
    }

    @Test
    fun localFavouriteTogglesStillWorkWithoutChangingNamesIdsOrCredit() {
        assertFalse(YouTubeSyncPolicy.ENABLED)
        val savedAt = LocalDateTime.of(2026, 9, 12, 12, 0)
        val artist = ArtistEntity("UCsyncDisabledFavourite", "保存済みアーティスト", lastUpdateTime = savedAt)
        val credit = ArtistCredit(artist.name, listOf(Artist(artist.name, artist.id)),
            ArtistCreditStatus.COMPLETE, "saved-track", "ja").toStoredJson()
        val song = SongEntity("sync-disabled-favourite", "保存済みの曲", localPath = null,
            inLibrary = savedAt, artistCreditJson = credit)
        val album = AlbumEntity("MPRE-sync-disabled-favourite", title = "保存済みアルバム", songCount = 1,
            duration = 180, lastUpdateTime = savedAt, artistCreditJson = credit)
        val playlist = PlaylistEntity(id = "LP-sync-disabled-favourite", name = "保存済みプレイリスト",
            browseId = "VL-sync-disabled-favourite")

        val likedSong = song.toggleLike()
        assertTrue(likedSong.liked)
        assertNotNull(likedSong.likedDate)
        assertEquals(song.id, likedSong.id)
        assertEquals(song.title, likedSong.title)
        assertEquals(song.inLibrary, likedSong.inLibrary)
        assertEquals(credit, likedSong.artistCreditJson)
        assertEquals(song, likedSong.toggleLike())

        val likedAlbum = album.toggleLike()
        assertNotNull(likedAlbum.bookmarkedAt)
        assertEquals(album.id, likedAlbum.id)
        assertEquals(album.title, likedAlbum.title)
        assertEquals(credit, likedAlbum.artistCreditJson)
        assertEquals(album, likedAlbum.toggleLike())

        val likedArtist = artist.toggleLike()
        assertNotNull(likedArtist.bookmarkedAt)
        assertEquals(artist.id, likedArtist.id)
        assertEquals(artist.name, likedArtist.name)
        assertEquals(artist, likedArtist.toggleLike())

        val likedPlaylist = playlist.toggleLike()
        assertNotNull(likedPlaylist.bookmarkedAt)
        assertEquals(playlist.id, likedPlaylist.id)
        assertEquals(playlist.name, likedPlaylist.name)
        assertEquals(playlist.browseId, likedPlaylist.browseId)
        assertEquals(playlist, likedPlaylist.toggleLike())
    }
}
