package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AlbumFilter
import com.dd3boh.outertune.constants.AlbumSortType
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.constants.LibraryContentFilter
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise the public filter DAOs against real Room joins and persisted source flags. */
class ContentSourceFilterDatabaseTest {
    private val addedAt = LocalDateTime.of(2026, 9, 9, 12, 0)
    private val online = "online"
    private val folder = "folder"
    private val downloaded = "downloaded"
    private val missingFolder = "missing-folder"
    private val unsavedOnline = "unsaved-online"
    private val mixed = "mixed"
    private val emptyRemote = "empty-remote"
    private val emptyLocal = "empty-local"
    private val allContent = LibraryContentFilter.entries.toSet()

    private fun withDatabase(block: suspend (MusicDatabase) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try {
            block(MusicDatabase(internal))
        } finally {
            internal.close()
        }
    }

    private fun trackId(source: String) = "source-track-$source"
    private fun albumId(source: String) = "source-album-$source"
    private fun artistId(source: String) = "UCsource-artist-$source"
    private fun playlistId(source: String) = "source-playlist-$source"

    @Test
    fun bookmarksCombineWithSourcesAndCanBeClearedWithoutChangingSources() = withDatabase { database ->
        database.seedAlbumsAndArtists()
        for (source in listOf(online, folder, mixed)) {
            database.update(database.album(albumId(source)).first()!!.album.copy(bookmarkedAt = addedAt))
            database.update(database.artist(artistId(source)).first()!!.artist.copy(bookmarkedAt = addedAt))
        }
        database.insert(AlbumEntity(albumId(emptyRemote), title = "Unfetched bookmark", songCount = 0,
            duration = 0, bookmarkedAt = addedAt))
        database.insert(ArtistEntity(artistId(emptyRemote), "Unfetched bookmark", bookmarkedAt = addedAt))
        // Liking a member song does not bookmark its album or artist.
        database.update(database.song(trackId(downloaded)).first()!!.song.copy(liked = true))
        val cases = listOf(
            emptySet<LibraryContentFilter>() to setOf(online, folder, mixed, emptyRemote),
            setOf(LibraryContentFilter.LIBRARY) to setOf(online, mixed),
            setOf(LibraryContentFilter.FOLDER) to setOf(folder, mixed),
            setOf(LibraryContentFilter.DOWNLOADED) to emptySet(),
            setOf(LibraryContentFilter.LIBRARY, LibraryContentFilter.FOLDER) to setOf(online, folder, mixed),
            allContent to setOf(online, folder, mixed),
        )
        for ((filters, expected) in cases) {
            assertEquals(expected.map(::albumId).toSet(),
                database.albums(filters, AlbumSortType.NAME, false, likedOnly = true).first().map { it.id }.toSet())
            assertEquals(expected.map(::artistId).toSet(),
                database.artists(filters, ArtistSortType.NAME, false, likedOnly = true).first().map { it.id }.toSet())
        }
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.DOWNLOADED), setOf(downloaded))
        assertAlbumArtistSources(database, emptySet(), setOf(online, folder, downloaded, mixed))
    }

    @Test
    fun folderSearchIncludesDescendantsButExcludesSiblingFoldersAndUnavailableFiles() = withDatabase { database ->
        val paths = mapOf(
            "nested" to "/storage/emulated/0/Music/sub/Match.wav",
            "sibling" to "/storage/emulated/0/Music-other/Match.wav",
            "missing" to "/storage/emulated/0/Music/sub/Missing.wav",
        )
        paths.forEach { (id, path) -> database.insert(SongEntity(id, "Match", isLocal = true,
            inLibrary = if (id == "missing") null else addedAt, localPath = path)) }
        val songs = database.searchSongsAllLocalInDir("/storage/emulated/0/Music", "Match").first()
        assertEquals(listOf("nested"), songs.map { it.id })
        assertTrue(database.searchSongsAllLocalInDir("/storage/emulated/0/Music", "absent").first().isEmpty())
    }

    private fun MusicDatabase.seedSongs() {
        listOf(
            SongEntity(trackId(online), "Saved online song", isLocal = false,
                inLibrary = addedAt, localPath = null),
            SongEntity(trackId(folder), "Available folder song", isLocal = true,
                inLibrary = addedAt, localPath = "/music/folder.mp3"),
            // A downloaded online song can have a filesystem path without being a folder song.
            SongEntity(trackId(downloaded), "Download without library registration", isLocal = false,
                inLibrary = null, localPath = "/downloads/remote.m4a", dateDownload = addedAt),
            SongEntity(trackId(missingFolder), "Missing folder song", isLocal = true,
                inLibrary = null, localPath = null),
            SongEntity(trackId(unsavedOnline), "Unregistered online song", isLocal = false,
                inLibrary = null, localPath = null),
        ).forEach { insert(it) }
    }

    private fun MusicDatabase.seedAlbumsAndArtists() {
        seedSongs()
        listOf(online, folder, downloaded, missingFolder, unsavedOnline, mixed).forEach { source ->
            val members = if (source == mixed) listOf(online, folder) else listOf(source)
            val isLocal = source == folder || source == missingFolder
            insert(ArtistEntity(artistId(source), "Artist $source", isLocal = isLocal))
            insert(AlbumEntity(albumId(source), title = "Album $source", songCount = members.size,
                duration = 180 * members.size, isLocal = isLocal))
            insert(AlbumArtistMap(albumId(source), artistId(source), 0))
            members.forEachIndexed { index, member ->
                insert(SongAlbumMap(trackId(member), albumId(source), index))
                insert(SongArtistMap(trackId(member), artistId(source), index))
            }
        }
    }

    private suspend fun assertAlbumArtistSources(
        database: MusicDatabase,
        filters: Set<LibraryContentFilter>,
        expected: Set<String>,
    ) {
        assertEquals("Album sources for $filters", expected.map(::albumId).toSet(),
            database.albums(filters, AlbumSortType.NAME, false).first().map { it.id }.toSet())
        assertEquals("Artist sources for $filters", expected.map(::artistId).toSet(),
            database.artists(filters, ArtistSortType.NAME, false).first().map { it.id }.toSet())
    }

    @Test
    fun albumAndArtistFiltersSeparateSourcesAndCombineSelectionsWithOr() = withDatabase { database ->
        database.seedAlbumsAndArtists()
        val librarySources = setOf(online, mixed)
        val folderSources = setOf(folder, mixed)
        val downloadSources = setOf(downloaded)
        val visibleSources = librarySources + folderSources + downloadSources

        listOf(
            Triple(AlbumFilter.LIBRARY, ArtistFilter.LIBRARY, librarySources),
            Triple(AlbumFilter.FOLDER, ArtistFilter.FOLDER, folderSources),
            Triple(AlbumFilter.DOWNLOADED, ArtistFilter.DOWNLOADED, downloadSources),
            Triple(AlbumFilter.ALL, ArtistFilter.ALL, visibleSources),
        ).forEach { (albumFilter, artistFilter, expected) ->
            assertEquals("Single album filter $albumFilter", expected.map(::albumId).toSet(),
                database.albums(albumFilter, AlbumSortType.NAME, false).first().map { it.id }.toSet())
            assertEquals("Single artist filter $artistFilter", expected.map(::artistId).toSet(),
                database.artists(artistFilter, ArtistSortType.NAME, false).first().map { it.id }.toSet())
        }

        assertAlbumArtistSources(database, setOf(LibraryContentFilter.LIBRARY), librarySources)
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.FOLDER), folderSources)
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.DOWNLOADED), downloadSources)
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.LIBRARY, LibraryContentFilter.FOLDER),
            librarySources + folderSources)
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.LIBRARY, LibraryContentFilter.DOWNLOADED),
            librarySources + downloadSources)
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.FOLDER, LibraryContentFilter.DOWNLOADED),
            folderSources + downloadSources)
        assertAlbumArtistSources(database, emptySet(), visibleSources)
        assertAlbumArtistSources(database, allContent, visibleSources)
        // Media-browser helpers retain their established cross-source library meaning.
        assertEquals((librarySources + folderSources).map(::albumId).toSet(),
            database.albumsInLibraryAsc().first().map { it.id }.toSet())
        assertEquals((librarySources + folderSources).map(::artistId).toSet(),
            database.artistsInLibraryAsc().first().map { it.id }.toSet())
    }

    private fun MusicDatabase.seedPlaylists(seedTracks: Boolean = true) {
        if (seedTracks) seedSongs()
        listOf(online, folder, downloaded, missingFolder, unsavedOnline, mixed, emptyRemote, emptyLocal).forEach { source ->
            // Ownership is intentionally independent from the songs' sources: an app-created
            // playlist holds online songs, while a synced playlist can hold folder entries.
            insert(PlaylistEntity(id = playlistId(source), name = "Playlist $source",
                isLocal = source == online || source == mixed || source == emptyLocal,
                browseId = if (source == emptyRemote) "remote-not-fetched" else null,
                remoteSongCount = if (source == emptyRemote) 42 else null,
                bookmarkedAt = addedAt))
            val members = when (source) {
                mixed -> listOf(online, folder)
                emptyRemote, emptyLocal -> emptyList()
                else -> listOf(source)
            }
            members.forEachIndexed { position, member ->
                insert(PlaylistSongMap(playlistId = playlistId(source), songId = trackId(member), position = position))
            }
        }
        // A remote playlist that was never saved must not enter any shelf through its song alone.
        insert(PlaylistEntity(id = "unsaved-playlist", name = "Not bookmarked", isLocal = false))
        insert(PlaylistSongMap(playlistId = "unsaved-playlist", songId = trackId(online)))
    }

    private suspend fun playlistSources(database: MusicDatabase, filters: Set<LibraryContentFilter>) =
        database.playlists(filters, PlaylistSortType.NAME, false).first().map { it.id }.toSet()

    @Test
    fun playlistSourcesUseMembersAndKeepSavedUnfetchedPlaylistsInLibrary() = withDatabase { database ->
        database.seedPlaylists()
        val librarySources = setOf(online, unsavedOnline, downloaded, mixed, emptyRemote, emptyLocal)
        val folderSources = setOf(folder, mixed)
        val downloadSources = setOf(downloaded)
        // No selection shows the whole saved shelf, including a playlist whose folder file vanished.
        val allSources = librarySources + folderSources + missingFolder

        listOf(
            PlaylistFilter.LIBRARY to librarySources,
            PlaylistFilter.FOLDER to folderSources,
            PlaylistFilter.DOWNLOADED to downloadSources,
            PlaylistFilter.ALL to allSources,
        ).forEach { (filter, expected) ->
            assertEquals("Single playlist filter $filter", expected.map(::playlistId).toSet(),
                database.playlists(filter, PlaylistSortType.NAME, false).first().map { it.id }.toSet())
        }
        listOf(
            setOf(LibraryContentFilter.LIBRARY) to librarySources,
            setOf(LibraryContentFilter.FOLDER) to folderSources,
            setOf(LibraryContentFilter.DOWNLOADED) to downloadSources,
            setOf(LibraryContentFilter.LIBRARY, LibraryContentFilter.FOLDER) to librarySources + folderSources,
            setOf(LibraryContentFilter.LIBRARY, LibraryContentFilter.DOWNLOADED) to librarySources + downloadSources,
            setOf(LibraryContentFilter.FOLDER, LibraryContentFilter.DOWNLOADED) to folderSources + downloadSources,
            emptySet<LibraryContentFilter>() to allSources,
            allContent to allSources,
        ).forEach { (filters, expected) ->
            assertEquals("Playlist sources for $filters", expected.map(::playlistId).toSet(),
                playlistSources(database, filters))
        }
        // Sync and the media browser use this helper as the entire saved shelf.
        assertEquals(allSources.map(::playlistId).toSet(),
            database.playlistInLibraryAsc().first().map { it.id }.toSet())
    }

    @Test
    fun losingAndRediscoveringFolderFileChangesFolderMembershipWithoutPromotingItToLibrary() = withDatabase { database ->
        database.seedAlbumsAndArtists()
        database.seedPlaylists(seedTracks = false)
        database.disableLocalSong(trackId(folder))
        val missing = database.song(trackId(folder)).first()!!.song
        assertTrue(missing.isLocal)
        assertNull(missing.inLibrary)
        assertNull(missing.dateDownload)
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.FOLDER), emptySet())
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.LIBRARY), setOf(online, mixed))
        assertTrue(playlistSources(database, setOf(LibraryContentFilter.FOLDER)).isEmpty())
        assertEquals(setOf(online, unsavedOnline, downloaded, mixed, emptyRemote, emptyLocal).map(::playlistId).toSet(),
            playlistSources(database, setOf(LibraryContentFilter.LIBRARY)))

        database.updateLocalSongPath(trackId(folder), addedAt.plusDays(1), "/music/rediscovered.mp3")
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.FOLDER), setOf(folder, mixed))
        assertAlbumArtistSources(database, setOf(LibraryContentFilter.LIBRARY), setOf(online, mixed))
        assertEquals(setOf(folder, mixed).map(::playlistId).toSet(),
            playlistSources(database, setOf(LibraryContentFilter.FOLDER)))
    }
}
