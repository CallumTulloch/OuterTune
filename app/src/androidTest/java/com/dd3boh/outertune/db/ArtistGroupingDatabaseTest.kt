package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.ArtistSortType
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.constants.LibraryContentFilter
import com.dd3boh.outertune.db.entities.*
import com.dd3boh.outertune.models.toStoredJson
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.time.LocalDateTime
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual Room views and DAO observers; source tags/maps remain independently editable. */
class ArtistGroupingDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val savedAt = LocalDateTime.of(2026, 9, 13, 12, 0)
    private val onlineId = "UC" + "a".repeat(22)
    private val alternateId = "UC" + "b".repeat(22)

    private inner class Fixture(val database: MusicDatabase, withOnline: Boolean = true) {
        val localA = ArtistEntity("LA-group-a", "Folder Alias A", isLocal = true,
            bookmarkedAt = savedAt, lastUpdateTime = savedAt, thumbnailUrl = "source-image-a", channelId = "source-channel-a")
        val localB = ArtistEntity("LA-group-b", "Folder Alias B", isLocal = true, lastUpdateTime = savedAt,
            thumbnailUrl = "source-image-b", channelId = "source-channel-b")
        val online = ArtistEntity(onlineId, "Online Canonical", onlineId = onlineId,
            thumbnailUrl = "online-image", lastUpdateTime = savedAt)
        val credit = ArtistCredit(localA.name, emptyList(), ArtistCreditStatus.RAW, "file-tags", "").toStoredJson()
        val localSong = SongEntity("LS-shared-credit", "Same title", duration = 180, isLocal = true,
            inLibrary = savedAt, localPath = "/isolated/group-a/track.flac", artistCreditJson = credit)
        val secondLocalSong = localSong.copy(id = "LS-second-local", localPath = "/isolated/group-b/track.flac")
        val onlineSong = SongEntity("remote-track", "Same title", duration = 180, inLibrary = savedAt,
            dateDownload = savedAt, localPath = null)
        val album = AlbumEntity("LB-group-album", title = "Local album", songCount = 2, duration = 360,
            isLocal = true, lastUpdateTime = savedAt, artistCreditJson = credit)
        val choiceA = LocalArtistLink(localA.id, onlineId, "Chosen Online", "choice-image", "revision-a")
        val choiceB = choiceA.copy(localArtistId = localB.id, revision = "revision-b")

        init {
            database.insert(localA); database.insert(localB)
            database.insert(localSong); database.insert(secondLocalSong)
            database.insert(SongArtistMap(localSong.id, localA.id, 0))
            database.insert(SongArtistMap(localSong.id, localB.id, 1))
            database.insert(SongArtistMap(secondLocalSong.id, localB.id, 0))
            database.insert(album)
            database.insert(SongAlbumMap(localSong.id, album.id, 0))
            database.insert(SongAlbumMap(secondLocalSong.id, album.id, 1))
            database.insert(AlbumArtistMap(album.id, localA.id, 0))
            database.insert(AlbumArtistMap(album.id, localB.id, 1))
            if (withOnline) {
                database.insert(online); database.insert(onlineSong)
                database.insert(SongArtistMap(onlineSong.id, online.id, 0))
            }
            database.setLocalArtistLink(choiceA); database.setLocalArtistLink(choiceB)
        }

        fun assertRawPreserved() {
            assertEquals(localA, database.artistById(localA.id))
            assertEquals(localB, database.artistById(localB.id))
            assertEquals(localSong, database.songForArtistCredit(localSong.id))
            assertEquals(secondLocalSong, database.songForArtistCredit(secondLocalSong.id))
            assertEquals(album, database.albumById(album.id))
            assertEquals(listOf(localA.id, localB.id), database.artistIdsForSong(localSong.id))
            assertEquals(listOf(localA.id, localB.id), database.albumArtistIdsForAlbum(album.id))
        }
    }

    private suspend fun withDatabase(block: suspend (MusicDatabase) -> Unit) {
        val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try { block(MusicDatabase(room)) } finally { room.close() }
    }

    @Test
    fun listsCountsSongsAndAlbumsGroupByOnlineIdentityWithoutMergingAudioOrStoredCredits() = runBlocking {
        withDatabase { database ->
            val fixture = Fixture(database)
            val artist = database.artists(ArtistFilter.ALL, ArtistSortType.NAME, false).first().single()
            assertEquals(onlineId, artist.id)
            assertEquals("Online Canonical", artist.title)
            assertEquals(3, artist.songCount)
            assertEquals(1, artist.downloadCount)
            assertFalse(artist.artist.isLocal)
            for (id in listOf(onlineId, fixture.localA.id, fixture.localB.id)) {
                assertEquals(onlineId, database.artist(id).first()?.id)
                assertEquals(3, database.artistSongs(id, ArtistSongSortType.NAME, false).first().size)
                assertEquals(3, database.artistSongsPreview(id, 99).first().size)
                assertEquals(listOf(fixture.album.id), database.artistAlbumsPreview(id, 99).first().map { it.id })
            }
            val sources = database.artistSongs(onlineId, ArtistSongSortType.NAME, false).first()
            assertEquals(2, sources.count { it.song.isLocal })
            assertEquals(3, sources.count { it.song.title == "Same title" })
            assertEquals(listOf(fixture.localA.id, fixture.localB.id), sources.first { it.id == fixture.localSong.id }.artists.map { it.id })
            val unrelated = fixture.localA.copy(id = "LA-unrelated", name = "Different album artist")
            val unrelatedAlbum = fixture.album.copy(id = "LB-unrelated")
            database.insert(unrelated); database.insert(unrelatedAlbum)
            database.insert(AlbumArtistMap(unrelatedAlbum.id, unrelated.id, 0))
            database.insert(SongAlbumMap(fixture.localSong.id, unrelatedAlbum.id, 0))
            // Song participation does not override a different, explicit album credit.
            assertEquals(listOf(fixture.album.id), database.artistAlbumsPreview(onlineId, 99).first().map { it.id })
            fixture.assertRawPreserved()
        }
    }

    @Test
    fun folderAndDownloadFiltersUseTheSongsSourceRatherThanTheRepresentativeType() = runBlocking {
        withDatabase { database ->
            Fixture(database)
            fun filtered(filter: LibraryContentFilter) = database.artists(setOf(filter), ArtistSortType.NAME, false)
            val folder = filtered(LibraryContentFilter.FOLDER).first().single()
            assertEquals(onlineId, folder.id)
            assertEquals(2, folder.songCount)
            assertEquals(0, folder.downloadCount)
            assertEquals(1, filtered(LibraryContentFilter.LIBRARY).first().single().songCount)
            assertEquals(1, filtered(LibraryContentFilter.DOWNLOADED).first().single().songCount)
            val both = database.artists(setOf(LibraryContentFilter.FOLDER, LibraryContentFilter.DOWNLOADED),
                ArtistSortType.NAME, false).first().single()
            assertEquals(3, both.songCount)
        }
    }

    @Test
    fun searchesUseTheDestinationNamesAndNeverAddLinkedFolderAliases() = runBlocking {
        withDatabase { database ->
            val fixture = Fixture(database)
            database.recordMetadataNames(listOf(MetadataNameEntity("ARTIST", onlineId, "ja", "確認済みの名前", "ArtistPage")))
            for (query in listOf("Online Canonical", "確認済みの名前")) {
                assertEquals(listOf(onlineId), database.searchArtists(query).first().map { it.id })
                assertEquals(listOf(onlineId), database.searchLocalArtists(query).first().map { it.id })
                assertEquals(listOf(onlineId), database.artistsByNameFuzzy(query).first().map { it.id })
                assertEquals(3, database.searchArtistSongs(query).first().size)
            }
            for (query in listOf(fixture.localA.name, fixture.localB.name)) {
                assertTrue(database.searchArtists(query).first().isEmpty())
                assertTrue(database.searchLocalArtists(query).first().isEmpty())
                assertTrue(database.artistsByNameFuzzy(query).first().isEmpty())
                assertTrue(database.searchArtistSongs(query).first().isEmpty())
            }
            assertTrue(database.removeLocalArtistLink(fixture.localA.id, fixture.choiceA.revision))
            assertEquals(listOf(fixture.localA.id), database.searchArtists(fixture.localA.name).first().map { it.id })
            fixture.assertRawPreserved()
        }
    }

    @Test
    fun anUnsavedDestinationAndZeroSongSourcesRemainManageableAndRequestLanguageNames() = runBlocking {
        withDatabase { database ->
            val fixture = Fixture(database, withOnline = false)
            assertNull(database.artistById(onlineId))
            assertEquals("Chosen Online", database.artist(onlineId).first()?.title)
            assertEquals(2, database.artist(onlineId).first()?.songCount)
            assertEquals(setOf(fixture.localA.id, fixture.localB.id), database.artistDisplayMappings().first().map { it.sourceArtistId }.toSet())
            assertTrue(database.metadataLibraryTargets().first().contains(MetadataTargetEntity("ARTIST", onlineId)))
            val sources = database.localArtistLinkSources(onlineId).first()
            assertEquals(listOf("/isolated/group-a"), sources.single { it.localArtist.id == fixture.localA.id }.folders)
            assertEquals(2, sources.single { it.localArtist.id == fixture.localB.id }.localArtist.songCount)
            database.setLocalArtistLink(fixture.choiceA.copy(thumbnailUrl = null))
            database.setLocalArtistLink(fixture.choiceB.copy(thumbnailUrl = null))
            // A missing online image/channel is not permission to present a source tag's profile as online.
            assertNull(database.artist(onlineId).first()?.thumbnailUrl)
            assertNull(database.artist(onlineId).first()?.artist?.channelId)
            assertTrue(database.artistDisplayMappings().first().all { it.thumbnailUrl == null })
            assertEquals("source-image-a", database.rawArtist(fixture.localA.id).first()?.thumbnailUrl)
            database.delete(fixture.localSong); database.delete(fixture.secondLocalSong); database.delete(fixture.album)
            database.safeDeleteArtist(fixture.localA.id); database.safeDeleteArtist(fixture.localB.id)
            assertEquals(0, database.artist(onlineId).first()?.songCount)
            assertEquals(2, database.localArtistLinkSources().first().size)
            assertTrue(database.localArtistLinkSources().first().all { it.localArtist.songCount == 0 && it.folders.isEmpty() })
            assertTrue(database.localArtistLinkSources(alternateId).first().isEmpty())
        }
    }

    @Test
    fun existingOnlineBookmarkWinsAndSyntheticBookmarkOverridesDoNotChangeLocalBookmarks() = runBlocking {
        withDatabase { database ->
            val fixture = Fixture(database)
            assertNull(database.artist(onlineId).first()?.artist?.bookmarkedAt)
            database.toggleArtistBookmark(fixture.localA.id)
            assertNotNull(database.artist(onlineId).first()?.artist?.bookmarkedAt)
            database.toggleArtistBookmark(onlineId)
            assertNull(database.artist(onlineId).first()?.artist?.bookmarkedAt)
            database.delete(fixture.onlineSong)
            database.safeDeleteArtist(onlineId)
            assertNotNull(database.artistById(onlineId))
            assertEquals(2, database.artist(onlineId).first()?.songCount)
            fixture.assertRawPreserved()
        }
        withDatabase { database ->
            val fixture = Fixture(database, withOnline = false)
            assertNotNull(database.artist(onlineId).first()?.artist?.bookmarkedAt)
            database.toggleArtistBookmark(onlineId)
            assertNotNull(database.artistById(onlineId))
            assertNull(database.artist(onlineId).first()?.artist?.bookmarkedAt)
            database.safeDeleteArtist(onlineId)
            assertNotNull(database.artistById(onlineId))
            assertTrue(database.removeLocalArtistLink(fixture.localA.id, fixture.choiceA.revision))
            assertEquals(savedAt, database.artist(fixture.localA.id).first()?.artist?.bookmarkedAt)
            assertNull(database.artist(onlineId).first()?.artist?.bookmarkedAt)
            fixture.assertRawPreserved()
        }
    }

    @Test
    fun anExistingOnlineSurrogateAndAliasJoinTheSameGroupWithoutRewritingTheirRelations() = runBlocking {
        withDatabase { database ->
            val fixture = Fixture(database, withOnline = false)
            val surrogate = fixture.online.copy(id = "LA-online-stable-ref")
            database.insert(surrogate)
            database.insert(ArtistAlias(onlineId, surrogate.id))
            database.insert(fixture.onlineSong)
            database.insert(SongArtistMap(fixture.onlineSong.id, surrogate.id, 0))
            assertEquals(listOf(onlineId), database.artistsInLibraryAsc().first().map { it.id })
            assertEquals(3, database.artistSongsPreview(onlineId, 99).first().size)
            assertEquals(onlineId, database.artist(surrogate.id).first()?.id)
            assertNull(database.artist(onlineId).first()?.artist?.bookmarkedAt)
            database.toggleArtistBookmark(onlineId)
            assertNotNull(database.artist(onlineId).first()?.artist?.bookmarkedAt)
            assertEquals(surrogate, database.artistEntityByExactId(surrogate.id))
            assertEquals(listOf(surrogate.id), database.artistIdsForSong(fixture.onlineSong.id))
            val mappings = database.artistDisplayMappings().first()
            assertEquals(setOf(fixture.localA.id, fixture.localB.id, surrogate.id), mappings.map { it.sourceArtistId }.toSet())
            assertEquals(setOf(onlineId), mappings.map { it.canonicalArtistId }.toSet())
            assertTrue(mappings.all { it.name == "Online Canonical" && it.thumbnailUrl == "online-image" })
            fixture.assertRawPreserved()
        }
    }

    @Test
    fun liveListAndMappingObserversFollowLinkChangesAndUnlinkWithoutRawProfileWrites() = runBlocking {
        withDatabase { database ->
            val fixture = Fixture(database, withOnline = false)
            val listSnapshots = Channel<List<Artist>>(Channel.UNLIMITED)
            val mappingSnapshots = Channel<List<ArtistDisplayMapping>>(Channel.UNLIMITED)
            val listJob = launch { database.artists(ArtistFilter.FOLDER, ArtistSortType.NAME, false).collect { listSnapshots.send(it) } }
            val mappingJob = launch { database.artistDisplayMappings().collect { mappingSnapshots.send(it) } }
            try {
                withTimeout(5_000) { listSnapshots.receiveAsFlow().first { it.singleOrNull()?.id == onlineId } }
                withTimeout(5_000) { mappingSnapshots.receiveAsFlow().first { it.size == 2 } }
                // Equal online labels never identify two different destination IDs as one person.
                val changed = fixture.choiceA.copy(onlineArtistId = alternateId, revision = "changed")
                database.setLocalArtistLink(changed)
                val split = withTimeout(5_000) { listSnapshots.receiveAsFlow().first { it.map { it.id }.toSet() == setOf(onlineId, alternateId) } }
                assertEquals(1, split.single { it.id == alternateId }.songCount)
                assertEquals(listOf("Chosen Online", "Chosen Online"), split.map { it.title })
                withTimeout(5_000) { mappingSnapshots.receiveAsFlow().first { rows -> rows.any { it.sourceArtistId == fixture.localA.id && it.canonicalArtistId == alternateId } } }
                assertTrue(database.removeLocalArtistLink(fixture.localA.id, changed.revision))
                withTimeout(5_000) { listSnapshots.receiveAsFlow().first { it.any { it.id == fixture.localA.id && it.title == fixture.localA.name } } }
                withTimeout(5_000) { mappingSnapshots.receiveAsFlow().first { it.size == 1 && it.single().sourceArtistId == fixture.localB.id } }
                fixture.assertRawPreserved()
            } finally { listJob.cancelAndJoin(); mappingJob.cancelAndJoin(); listSnapshots.close(); mappingSnapshots.close() }
        }
    }

}
