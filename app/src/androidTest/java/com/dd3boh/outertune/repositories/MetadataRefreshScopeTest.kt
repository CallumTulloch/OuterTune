package com.dd3boh.outertune.repositories

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataTargetEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A viewed page persists real rows, but only saved interests should renew names indefinitely. */
class MetadataRefreshScopeTest {
    @Test fun browsedAlbumPlaybackHistoryAndObservedNamesAloneDoNotBecomeRefreshTargets() = runBlocking {
        withDatabase { database ->
            database.insert(album("browsed-album"))
            database.insert(SongEntity("played-song", "Played", localPath = null, albumId = "browsed-album"))
            database.insert(ArtistEntity("UC-browsed", "Browsed artist"))
            database.insert(SongAlbumMap("played-song", "browsed-album", 0))
            database.insert(SongArtistMap("played-song", "UC-browsed", 0))
            database.insert(AlbumArtistMap("browsed-album", "UC-browsed", 0))
            database.recordMetadataNames(listOf(MetadataNameEntity(
                "SONG", "search-only", "en", "Search result", "search", 50, 1L,
            )))

            assertTrue(database.metadataRefreshTargets().first().isEmpty())
            assertEquals(3, database.metadataLibraryTargets().first().size)
            assertEquals("Search result", database.metadataNames("SONG", "search-only").single().name)
        }
    }

    @Test fun savedSongsAlbumsPlaylistsAndExplicitArtistLinksKeepTheirRemoteIdentities() = runBlocking {
        withDatabase { database ->
            database.insert(album("saved-album").copy(bookmarkedAt = SAVED_AT))
            database.insert(album("song-album"))
            database.insert(album("mapped-album"))
            val songs = listOf(
                SongEntity("library", "Library", localPath = null, inLibrary = SAVED_AT, albumId = "song-album"),
                SongEntity("liked", "Liked", localPath = null, liked = true),
                SongEntity("downloaded", "Downloaded", localPath = "/downloaded", dateDownload = SAVED_AT),
                SongEntity("saved-album-track", "Saved album track", localPath = null, albumId = "saved-album"),
                SongEntity("mapped-album-track", "Mapped album track", localPath = null),
                SongEntity("saved-playlist-track", "Playlist track", localPath = null),
                SongEntity("local-playlist-track", "Local playlist remote track", localPath = null),
                SongEntity("browsed-playlist-track", "Browsed playlist track", localPath = null),
                SongEntity("local-song", "Local", localPath = "/local", isLocal = true, inLibrary = SAVED_AT),
            )
            songs.forEach { database.insert(it) }
            database.insert(SongAlbumMap("mapped-album-track", "saved-album", 0))
            database.insert(SongAlbumMap("liked", "mapped-album", -1))
            database.insert(PlaylistEntity("saved-playlist", "Saved", bookmarkedAt = SAVED_AT))
            database.insert(PlaylistEntity("local-playlist", "Local", isLocal = true))
            database.insert(PlaylistEntity("browsed-playlist", "Browsed", browseId = "VL-browsed"))
            database.insert(PlaylistSongMap(playlistId = "saved-playlist", songId = "saved-playlist-track"))
            database.insert(PlaylistSongMap(playlistId = "local-playlist", songId = "local-playlist-track"))
            database.insert(PlaylistSongMap(playlistId = "browsed-playlist", songId = "browsed-playlist-track"))
            database.insert(ArtistEntity("UC-bookmarked", "Bookmarked", bookmarkedAt = SAVED_AT))
            database.insert(ArtistEntity("credit-key", "Song credit", onlineId = "UC-song-credit"))
            database.insert(ArtistEntity("UC-album-credit", "Album credit"))
            database.insert(ArtistEntity("local-artist", "Local artist", isLocal = true))
            database.insert(ArtistEntity("UC-browsed", "Browsed artist"))
            database.insert(SongArtistMap("library", "credit-key", 0))
            database.insert(SongArtistMap("browsed-playlist-track", "UC-browsed", 0))
            database.insert(AlbumArtistMap("mapped-album", "UC-album-credit", 0))
            database.setLocalArtistLink(LocalArtistLink("local-artist", "UCabcdefghijklmnopqrstuv", "Linked", null, "one"))

            val expected = setOf(
                target("SONG", "library"), target("SONG", "liked"), target("SONG", "downloaded"),
                target("SONG", "saved-album-track"), target("SONG", "mapped-album-track"),
                target("SONG", "saved-playlist-track"), target("SONG", "local-playlist-track"),
                target("ALBUM", "saved-album"), target("ALBUM", "song-album"), target("ALBUM", "mapped-album"),
                target("ARTIST", "UC-bookmarked"), target("ARTIST", "UC-song-credit"),
                target("ARTIST", "UC-album-credit"), target("ARTIST", "UCabcdefghijklmnopqrstuv"),
            )
            assertEquals(expected, database.metadataRefreshTargets().first().toSet())
        }
    }

    @Test fun savingAndRemovingPlaylistInterestInvalidatesRefreshScopeWithoutDeletingNames() = runBlocking {
        withDatabase { database ->
            val playlist = PlaylistEntity("playlist", "Playlist", browseId = "VL-playlist")
            database.insert(playlist)
            database.insert(SongEntity("track", "Raw", localPath = null))
            database.insert(PlaylistSongMap(playlistId = playlist.id, songId = "track"))
            database.recordMetadataNames(listOf(MetadataNameEntity(
                "SONG", "track", "ja", "保存された名前", "detail", 100, 1L,
            )))
            val observed = Channel<Set<MetadataTargetEntity>>(Channel.UNLIMITED)
            val collector = launch { database.metadataRefreshTargets().collect { observed.send(it.toSet()) } }
            suspend fun awaitTargets(expected: Set<MetadataTargetEntity>) = withTimeout(5_000) {
                while (observed.receive() != expected) Unit
            }
            try {
                awaitTargets(emptySet())
                database.update(playlist.copy(bookmarkedAt = SAVED_AT))
                awaitTargets(setOf(target("SONG", "track")))
                database.update(playlist)
                awaitTargets(emptySet())
                assertEquals("保存された名前", database.metadataNames("SONG", "track").single().name)
            } finally { collector.cancelAndJoin() }
        }
    }

    private suspend fun withDatabase(block: suspend (MusicDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
        try { block(database) } finally { database.close() }
    }

    companion object {
        private val SAVED_AT = LocalDateTime.of(2026, 9, 27, 0, 0)
        private fun album(id: String) = AlbumEntity(id, title = id, songCount = 1, duration = 180)
        private fun target(kind: String, id: String) = MetadataTargetEntity(kind, id)
    }
}
