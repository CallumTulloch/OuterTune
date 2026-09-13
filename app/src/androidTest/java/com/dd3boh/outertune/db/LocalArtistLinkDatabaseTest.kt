package com.dd3boh.outertune.db

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.Artist as SavedArtist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.toStoredJson
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LocalArtistLinkDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val savedAt = LocalDateTime.of(2026, 9, 12, 12, 0)
    private val onlineId = "UCPCiIrrrNJOKvi_5vr3G6PA"
    private val otherOnlineId = "UCbrWU0y_rLsEOYgaTX5Y74A"

    private data class Source(
        val artist: ArtistEntity,
        val remoteArtist: ArtistEntity,
        val song: SongEntity,
        val album: AlbumEntity,
    )

    private fun seed(database: MusicDatabase): Source {
        val artist = ArtistEntity("LA-manual-link", "ファイルの表記", thumbnailUrl = "local-image",
            isLocal = true, bookmarkedAt = savedAt, lastUpdateTime = savedAt)
        val remote = ArtistEntity(onlineId, "オンラインの表記", thumbnailUrl = "existing-remote-image",
            onlineId = onlineId, lastUpdateTime = savedAt)
        val credit = ArtistCredit(artist.name, listOf(Artist(artist.name, null, artist.id)),
            ArtistCreditStatus.COMPLETE, "file-tags", "ja").toStoredJson()
        val album = AlbumEntity("LA-manual-album", title = "ファイルのアルバム", songCount = 1,
            duration = 180, isLocal = true, bookmarkedAt = savedAt, lastUpdateTime = savedAt,
            artistCreditJson = credit)
        val song = SongEntity("LS-manual-song", "ファイルの曲", duration = 180, isLocal = true,
            localPath = "/isolated-test/music.flac", liked = true, likedDate = savedAt, inLibrary = savedAt,
            albumId = album.id, albumName = album.title, artistCreditJson = credit)
        database.insert(artist)
        database.insert(remote)
        database.insert(album)
        database.insert(song)
        database.insert(SongArtistMap(song.id, artist.id, 2))
        database.insert(AlbumArtistMap(album.id, artist.id, 3))
        return Source(artist, remote, song, album)
    }

    private fun link(id: String = "LA-manual-link", target: String = onlineId) = LocalArtistLink(
        localArtistId = id, onlineArtistId = target, onlineName = "選択したオンライン名",
        thumbnailUrl = "selected-image", revision = UUID.randomUUID().toString(),
    )

    private suspend fun withDatabase(block: suspend (MusicDatabase) -> Unit) {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        try { block(MusicDatabase(internal)) } finally { internal.close() }
    }

    private fun assertSourceUnchanged(database: MusicDatabase, source: Source) {
        assertEquals(source.artist, database.artistById(source.artist.id))
        assertEquals(source.remoteArtist, database.artistById(source.remoteArtist.id))
        assertEquals(source.song, database.songForArtistCredit(source.song.id))
        assertEquals(source.album, database.albumById(source.album.id))
        assertEquals(listOf(source.artist.id), database.artistIdsForSong(source.song.id))
        assertEquals(listOf(source.artist.id), database.albumArtistIdsForAlbum(source.album.id))
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Invalid manual link must be rejected")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun linkChangeAndRevisionCheckedUnlinkNeverRewriteSourceMetadata() = runBlocking {
        withDatabase { database ->
            val source = seed(database)
            val originalLink = link()
            assertNull(database.localArtistLink(source.artist.id).first())
            database.setLocalArtistLink(originalLink)
            assertEquals(originalLink, database.localArtistLink(source.artist.id).first())
            assertSourceUnchanged(database, source)

            val otherLocal = source.artist.copy(id = "LA-manual-other", name = "別のファイル表記")
            database.insert(otherLocal)
            val sameTarget = link(otherLocal.id)
            database.setLocalArtistLink(sameTarget)
            assertEquals(2, database.localArtistLinks().first().size)

            val changed = link(target = otherOnlineId).copy(thumbnailUrl = null)
            database.setLocalArtistLink(changed)
            assertFalse(database.removeLocalArtistLink(source.artist.id, originalLink.revision))
            assertEquals(changed, database.localArtistLinkById(source.artist.id))
            assertTrue(database.removeLocalArtistLink(source.artist.id, changed.revision))
            assertFalse(database.removeLocalArtistLink(source.artist.id, changed.revision))
            assertNull(database.localArtistLink(source.artist.id).first())
            assertEquals(listOf(sameTarget), database.localArtistLinks().first())
            assertSourceUnchanged(database, source)
        }
    }

    @Test
    fun onlyAnExistingLocalArtistAndCompleteManualChoiceCanBeLinked() = runBlocking {
        withDatabase { database ->
            val source = seed(database)
            val valid = link()
            for (invalid in listOf(valid.copy(localArtistId = "missing"),
                valid.copy(localArtistId = source.remoteArtist.id), valid.copy(onlineArtistId = "LA-internal"),
                valid.copy(onlineArtistId = "UCshort"),
                valid.copy(onlineArtistId = "FEmusic_library_privately_owned_artist123"),
                valid.copy(onlineName = "  "), valid.copy(revision = ""))) {
                expectInvalid { database.setLocalArtistLink(invalid) }
            }
            assertTrue(database.localArtistLinks().first().isEmpty())
            assertSourceUnchanged(database, source)
        }
    }

    @Test
    fun rescanReusesTheLocalIdentityAndChangedTagsDoNotInheritItsChoice() = runBlocking {
        withDatabase { database ->
            val source = seed(database)
            val chosen = link()
            database.setLocalArtistLink(chosen)
            // These are the production DAO operations used by a full scanner refresh.
            database.awaitTransaction {
                val resolved = resolveAndInsertArtist("LA-fresh-scanner-id", " ${source.artist.name} ", true)
                assertEquals(source.artist.id, resolved.id)
                unlinkSongArtists(source.song.id)
                insert(SongArtistMap(source.song.id, resolved.id, 0))
            }
            assertEquals(chosen, database.localArtistLinkById(source.artist.id))
            assertSourceUnchanged(database, source)

            database.awaitTransaction {
                val changedTagArtist = resolveAndInsertArtist(null, "別人のファイル表記", true)
                assertNotEquals(source.artist.id, changedTagArtist.id)
                assertNull(localArtistLinkById(changedTagArtist.id))
                unlinkSongArtists(source.song.id)
                insert(SongArtistMap(source.song.id, changedTagArtist.id, 0))
                safeDeleteArtist(source.artist.id)
            }
            assertEquals(chosen, database.localArtistLinkById(source.artist.id))
            assertEquals(source.artist, database.artistById(source.artist.id))
        }
    }

    @Test
    fun removingTheLastFilesAndDisablingLocalMediaRetainsOnlyManualChoices() = runBlocking {
        withDatabase { database ->
            val source = seed(database)
            val unbookmarked = source.artist.copy(bookmarkedAt = null)
            database.update(unbookmarked)
            val chosen = link()
            database.setLocalArtistLink(chosen)
            database.delete(source.song)
            database.delete(source.album)
            database.safeDeleteArtist(source.artist.id)
            assertEquals(unbookmarked, database.artistById(source.artist.id))
            val unlinked = ArtistEntity("LA-unlinked", "削除対象", isLocal = true)
            database.insert(unlinked)

            database.nukeLocalData()
            assertNull(database.artistById(unlinked.id))
            assertEquals(chosen, database.localArtistLinkById(source.artist.id))
            assertEquals(unbookmarked, database.resolveAndInsertArtist(null, source.artist.name, true))
            assertEquals(source.remoteArtist, database.artistById(source.remoteArtist.id))

            assertTrue(database.removeLocalArtistLink(source.artist.id, chosen.revision))
            database.safeDeleteArtist(source.artist.id)
            assertNull(database.artistById(source.artist.id))
        }
    }

    @Test
    fun duplicateCleanupMovesManualChoiceAndRejectsDifferentDestinationsAtomically() = runBlocking {
        withDatabase { database ->
            val source = seed(database)
            val survivor = source.artist.copy(id = "LA-manual-survivor")
            database.insert(survivor)
            val chosen = link()
            database.setLocalArtistLink(chosen)
            val conflicting = link(survivor.id, otherOnlineId)
            database.setLocalArtistLink(conflicting)
            try {
                LocalMediaScanner.swapArtists(source.artist, survivor, database)
                fail("Different manual choices must not be merged")
            } catch (_: IllegalArgumentException) { }
            assertSourceUnchanged(database, source)
            assertEquals(chosen, database.localArtistLinkById(source.artist.id))
            assertEquals(conflicting, database.localArtistLinkById(survivor.id))

            assertTrue(database.removeLocalArtistLink(survivor.id, conflicting.revision))
            LocalMediaScanner.swapArtists(source.artist, survivor, database)
            assertNull(database.localArtistLinkById(source.artist.id))
            assertEquals(chosen.copy(localArtistId = survivor.id), database.localArtistLinkById(survivor.id))
            assertEquals(listOf(survivor.id), database.artistIdsForSong(source.song.id))
            assertEquals(listOf(survivor.id), database.albumArtistIdsForAlbum(source.album.id))
            assertEquals(source.song, database.songForArtistCredit(source.song.id))
        }
    }

    @Test
    fun duplicateCleanupKeepsSurvivorRevisionWhenBothManualChoicesAgree() = runBlocking {
        withDatabase { database ->
            val source = seed(database)
            val survivor = source.artist.copy(id = "LA-manual-survivor")
            database.insert(survivor)
            val oldChoice = link()
            val survivorChoice = link(survivor.id).copy(onlineName = "直近の確認名", thumbnailUrl = "survivor-image")
            database.setLocalArtistLink(oldChoice)
            database.setLocalArtistLink(survivorChoice)
            LocalMediaScanner.swapArtists(source.artist, survivor, database)
            assertEquals(survivorChoice, database.localArtistLinkById(survivor.id))
            assertNull(database.localArtistLinkById(source.artist.id))
            assertFalse(database.removeLocalArtistLink(survivor.id, oldChoice.revision))
            assertEquals(listOf(survivor.id), database.artistIdsForSong(source.song.id))
        }
    }

    @Test
    fun rawArtistObserverEmitsLinkedImageChangesAndRestoresItsLocalImageOnUnlink() = runBlocking {
        withDatabase { database ->
            val source = seed(database)
            val snapshots = Channel<SavedArtist>(Channel.UNLIMITED)
            val observer = launch {
                database.rawArtist(source.artist.id).collect { artist ->
                    snapshots.send(requireNotNull(artist))
                }
            }
            suspend fun next(revision: String?) = withTimeout(5_000L) {
                snapshots.receiveAsFlow().first { it.localLink?.revision == revision }
            }
            try {
                assertEquals("local-image", next(null).thumbnailUrl)
                val first = link()
                database.setLocalArtistLink(first)
                assertEquals(first.thumbnailUrl, next(first.revision).thumbnailUrl)
                val second = link(target = otherOnlineId).copy(thumbnailUrl = "changed-image")
                database.setLocalArtistLink(second)
                assertEquals("changed-image", next(second.revision).thumbnailUrl)
                assertTrue(database.removeLocalArtistLink(source.artist.id, second.revision))
                assertEquals("local-image", next(null).thumbnailUrl)
                assertSourceUnchanged(database, source)
            } finally {
                observer.cancelAndJoin()
                snapshots.close()
            }
        }
    }

    @Test
    fun applicationBuilderPreservesCurrentLinksAcrossReopen() = runBlocking {
        val filename = "manual-artist-link-reopen-${UUID.randomUUID()}.db"
        var database = InternalDatabase.newTestInstance(context, filename)
        try {
            val source = seed(database)
            database.close()
            database = InternalDatabase.newTestInstance(context, filename)
            assertSourceUnchanged(database, source)
            assertEquals(MusicDatabase.MUSIC_DATABASE_VERSION, database.openHelper.readableDatabase.version)
            assertTrue(database.localArtistLinks().first().isEmpty())
            val chosen = link()
            database.setLocalArtistLink(chosen)
            database.close()
            database = InternalDatabase.newTestInstance(context, filename)
            assertEquals(chosen, database.localArtistLinkById(source.artist.id))
            assertSourceUnchanged(database, source)
        } finally {
            database.close()
            context.deleteDatabase(filename)
        }
    }
}
