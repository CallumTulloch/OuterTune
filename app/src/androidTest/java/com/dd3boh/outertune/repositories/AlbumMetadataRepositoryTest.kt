package com.dd3boh.outertune.repositories

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AlbumFilter
import com.dd3boh.outertune.constants.AlbumSortType
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.artistDisplayText
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Room flows and the same song-save entry point as downloads; HTTP is supplied by the test. */
class AlbumMetadataRepositoryTest {
    private val albumId = "MPRE-album-header-test"
    private val artistId = "UC-album-header-artist"
    private val locale = YouTubeLocale(gl = "JP", hl = "ja")

    private fun header(
        id: String = albumId,
        credit: ArtistCredit = ArtistCredit("正式なアルバム人物", listOf(Artist("正式なアルバム人物", artistId)),
            ArtistCreditStatus.COMPLETE, "AlbumPage", "ja"),
    ) = AlbumItem(browseId = id, playlistId = "OLAK5-header", title = "Remote album title",
        artists = credit.artists, thumbnail = "https://example.invalid/album", artistCredit = credit)

    @Test fun downloadedSongCreatesAlbumWhoseLibraryFlowReceivesHeaderWithoutOpeningAlbum() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<AlbumItem>>()
        val calls = AtomicInteger()
        val fixture = Fixture { _, _ -> calls.incrementAndGet(); started.complete(Unit); reply.await() }
        try {
            // A prior name-only request must not postpone credit completion when the album is saved later.
            fixture.database.recordMetadataFetch(MetadataFetchEntity("ALBUM", albumId, "ja",
                MetadataFetchEntity.SUCCESS, fixture.now.get(), fixture.contextKey.get()))
            val repository = fixture.repository()
            fixture.database.insert(MediaMetadata(id = "song-header", title = "Saved song title",
                artists = listOf(MediaMetadata.Artist("UC-track-only", "Track performer")),
                duration = 241, genre = null, album = MediaMetadata.Album(albumId, "Saved album title")))
            fixture.database.updateDownloadStatus("song-header", LocalDateTime.of(2026, 9, 7, 1, 2))
            withTimeout(15_000) { started.await() }
            val before = fixture.database.albumById(albumId)!!.copy(bookmarkedAt = LocalDateTime.of(2026, 9, 7, 1, 3))
            fixture.database.update(before)
            assertTrue(fixture.database.album(albumId).first()!!.artists.isEmpty())
            repeat(5) { repository.refreshSavedAlbums() }
            reply.complete(Result.success(header()))
            val row = withTimeout(15_000) {
                fixture.database.albums(AlbumFilter.DOWNLOADED, AlbumSortType.CREATE_DATE, false)
                    .first { rows -> rows.any { it.id == albumId && it.artists.isNotEmpty() } }
                    .single { it.id == albumId }
            }
            fixture.awaitIdle(repository)
            assertEquals(1, calls.get())
            assertEquals("正式なアルバム人物", row.artistDisplayText())
            assertEquals(listOf(artistId), row.artists.map { it.onlineArtistId })
            assertEquals(before, row.album.copy(artistCreditJson = before.artistCreditJson))
            assertEquals(1, row.downloadCount)
            assertEquals(listOf("song-header"), fixture.database.albumSongs(albumId).first().map { it.id })
            assertEquals(listOf("UC-track-only"), fixture.database.song("song-header").first()!!.artists.map { it.onlineArtistId })
        } finally { fixture.close() }
    }

    @Test fun failedAndEmptyHeadersKeepPartialCreditAndRetryIndependentlyAcrossRestart() = runBlocking {
        val response = AtomicReference<Result<AlbumItem>>(Result.failure(IOException("offline")))
        val calls = AtomicInteger()
        val fixture = Fixture { _, _ -> calls.incrementAndGet(); response.get() }
        try {
            fixture.database.insert(AlbumEntity(albumId, title = "Saved", songCount = 9, duration = 1234))
            fixture.database.applyAlbumArtistCredit(albumId, ArtistCredit("Saved literal byline",
                listOf(Artist("Known person", artistId)), ArtistCreditStatus.PARTIAL, "prior-header", "ja"))
            val before = fixture.database.album(albumId).first()!!
            var repository = fixture.repository()
            repository.refreshSavedAlbums()
            fixture.awaitIdle(repository)
            assertEquals(1, calls.get())
            assertEquals(MetadataFetchEntity.FAILED, fixture.state(albumId)!!.status)
            assertEquals(before, fixture.database.album(albumId).first())
            repository = fixture.repository()
            repository.refreshSavedAlbums()
            fixture.awaitIdle(repository)
            assertEquals(1, calls.get())

            fixture.now.addAndGet(ALBUM_CREDIT_FAILURE_RETRY_MS + 1)
            response.set(Result.success(header(credit = ArtistCredit("", emptyList(), ArtistCreditStatus.RAW, "AlbumPage", "ja"))))
            repository.refreshSavedAlbums()
            fixture.awaitIdle(repository)
            assertEquals(2, calls.get())
            assertEquals(MetadataFetchEntity.EMPTY, fixture.state(albumId)!!.status)
            assertEquals(before, fixture.database.album(albumId).first())
            repository = fixture.repository()
            repository.refreshSavedAlbums()
            fixture.awaitIdle(repository)
            assertEquals(2, calls.get())

            fixture.now.addAndGet(ALBUM_CREDIT_EMPTY_RETRY_MS + 1)
            response.set(Result.success(header()))
            repository.refreshSavedAlbums()
            fixture.awaitIdle(repository)
            assertEquals(3, calls.get())
            val after = fixture.database.album(albumId).first()!!
            assertEquals(ArtistCreditStatus.COMPLETE, after.artistCredit!!.status)
            assertEquals(before.album, after.album.copy(artistCreditJson = before.album.artistCreditJson))
            assertEquals(before.artists.map { it.id }, after.artists.map { it.id })
        } finally { fixture.close() }
    }

    @Test fun sharedHeaderCannotPromoteUnsavedTargetOrUpdateLocalAlbumOrWrongLocale() = runBlocking {
        val fixture = Fixture { _, _ -> error("No request should be needed") }
        try {
            val repository = fixture.repository(start = false)
            assertFalse(repository.acceptHeader(header(), locale, fixture.contextKey.get()))
            assertNull(fixture.database.albumById(albumId))
            assertNull(fixture.database.artistByOnlineId(artistId))
            assertNull(fixture.state(albumId))
            val local = AlbumEntity(albumId, title = "Local", songCount = 2, duration = 300, isLocal = true)
            fixture.database.insert(local)
            assertFalse(repository.acceptHeader(header(), locale, fixture.contextKey.get()))
            assertEquals(local, fixture.database.albumById(albumId))
            fixture.database.delete(local)
            val saved = local.copy(isLocal = false)
            fixture.database.insert(saved)
            assertFalse(repository.acceptHeader(header(), locale.copy(hl = "en"), fixture.contextKey.get()))
            assertFalse(repository.acceptHeader(header(), locale, "obsolete-account"))
            assertEquals(saved, fixture.database.albumById(albumId))
            assertNull(fixture.database.artistByOnlineId(artistId))
            assertNull(fixture.state(albumId))
        } finally { fixture.close() }
    }

    @Test fun obsoleteAccountResponseIsDiscardedAndNewRequestFillsSavedAlbum() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstReply = CompletableDeferred<Result<AlbumItem>>()
        val secondReply = CompletableDeferred<Result<AlbumItem>>()
        val calls = AtomicInteger()
        val fixture = Fixture { _, _ ->
            if (calls.incrementAndGet() == 1) { firstStarted.complete(Unit); firstReply.await() }
            else { secondStarted.complete(Unit); secondReply.await() }
        }
        try {
            fixture.database.insert(AlbumEntity(albumId, title = "Saved", songCount = 1, duration = 100))
            val repository = fixture.repository()
            withTimeout(15_000) { firstStarted.await() }
            fixture.contextKey.set("JP:changed-account")
            firstReply.complete(Result.success(header()))
            withTimeout(15_000) { secondStarted.await() }
            assertNull(fixture.database.albumById(albumId)!!.artistCredit)
            assertNull(fixture.database.artistByOnlineId(artistId))
            secondReply.complete(Result.success(header()))
            fixture.awaitIdle(repository)
            assertEquals(ArtistCreditStatus.COMPLETE, fixture.database.albumById(albumId)!!.artistCredit!!.status)
            assertEquals(2, calls.get())
        } finally { fixture.close() }
    }

    @Test fun wrongAlbumIdResponseDoesNotCreateRelationships() = runBlocking {
        val fixture = Fixture { _, _ -> Result.success(header(id = "MPRE-unrelated")) }
        try {
            val album = AlbumEntity(albumId, title = "Saved", songCount = 1, duration = 100)
            fixture.database.insert(album)
            val repository = fixture.repository()
            repository.refreshSavedAlbums()
            fixture.awaitIdle(repository)
            assertEquals(album, fixture.database.albumById(albumId))
            assertNull(fixture.database.albumById("MPRE-unrelated"))
            assertNull(fixture.database.artistByOnlineId(artistId))
            assertEquals(MetadataFetchEntity.FAILED, fixture.state(albumId)!!.status)
        } finally { fixture.close() }
    }

    @Test fun otherLanguageRawHeaderCanCompleteOnlyUsingExplicitMatchingArtistIds() = runBlocking {
        val fixture = Fixture { _, _ -> error("Only shared headers are used") }
        try {
            fixture.database.insert(AlbumEntity(albumId, title = "Saved", songCount = 1, duration = 100))
            fixture.database.applyAlbumArtistCredit(albumId, ArtistCredit("Unparsed old header", emptyList(),
                ArtistCreditStatus.RAW, "prior-header", "en"))
            val repository = fixture.repository(start = false)
            assertTrue(repository.acceptHeader(header(), locale, fixture.contextKey.get()))
            assertEquals("ja", fixture.database.albumById(albumId)!!.artistCredit!!.language)
            assertEquals(ArtistCreditStatus.COMPLETE, fixture.database.albumById(albumId)!!.artistCredit!!.status)

            val secondId = "MPRE-partial-other-language"
            fixture.database.insert(AlbumEntity(secondId, title = "Second", songCount = 1, duration = 100))
            fixture.database.applyAlbumArtistCredit(secondId, ArtistCredit("Same name",
                listOf(Artist("正式なアルバム人物", "UC-different-person")), ArtistCreditStatus.PARTIAL, "prior-header", "en"))
            val before = fixture.database.album(secondId).first()!!
            assertFalse(repository.acceptHeader(header(id = secondId), locale, fixture.contextKey.get()))
            assertEquals(before, fixture.database.album(secondId).first())
        } finally { fixture.close() }
    }

    private class Fixture(private val fetch: suspend (String, YouTubeLocale) -> Result<AlbumItem>) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(internal)
        val now = AtomicLong(1_789_000_000_000L)
        val contextKey = AtomicReference("JP:test")
        private var job: Job? = null

        suspend fun repository(start: Boolean = true): AlbumMetadataRepository {
            job?.cancelAndJoin()
            val newJob = SupervisorJob().also { job = it }
            val locale = YouTubeLocale(gl = "JP", hl = "ja")
            return AlbumMetadataRepository(database, context, AlbumMetadataRepository.Runtime(
                scope = CoroutineScope(newJob + Dispatchers.IO), now = now::get,
                locale = { locale }, configuration = flowOf(locale),
                contextKey = { contextKey.get() }, fetch = fetch,
            )).also { if (start) it.start() }
        }

        fun state(albumId: String) = database.metadataFetch("ALBUM", albumId, "ja", "album-credit:${contextKey.get()}")

        suspend fun awaitIdle(repository: AlbumMetadataRepository) = withTimeout(15_000) {
            while (repository.pendingRequestCount != 0) delay(10)
        }

        suspend fun close() {
            job?.cancelAndJoin()
            database.close()
        }
    }
}
