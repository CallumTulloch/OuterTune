package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.PlaylistSongReferences
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.AlbumPage
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room, both album acquisition paths and a controlled clock expose retry bypasses. */
class MetadataOriginalRetryTest {
    @Test(timeout = 45_000)
    fun aStrongAlbumObservationCanRecoverImmediatelyAfterAnOrdinaryLookupFailed() = runBlocking {
        withFixture { f ->
            f.ordinaryQueueEnabled = true
            f.failMain = true
            f.start()
            f.repository.setPlayingSong(IDS.first())
            f.await { f.sourceState(IDS.first()) == MetadataFetchEntity.FAILED }
            f.awaitIdle()
            assertEquals(1, f.mainCount(IDS.first()))

            f.failMain = false
            f.ordinaryQueueEnabled = false
            f.open(ALBUM_A)
            f.awaitAlbum(ALBUM_A, MetadataFetchEntity.SUCCESS)
            f.awaitIdle()

            assertEquals(2, f.mainCount(IDS.first()))
            assertEquals(listOf(1, 1), IDS.drop(1).map(f::mainCount))
            IDS.forEach { id ->
                assertTrue(latestOriginalRows(f.database.metadataNames("SONG", id)).any {
                    originalCandidate(it)?.albumId == ALBUM_A
                })
            }
        }
    }

    @Test(timeout = 45_000)
    fun aFailedStrongObservationIsNotRetriedByItsPlaylistOrAnotherEditionBeforeTheDeadline() = runBlocking {
        withFixture { f ->
            f.failMain = true
            f.start()
            f.open(ALBUM_A)
            f.awaitAlbum(ALBUM_A, MetadataFetchEntity.FAILED)
            f.awaitIdle()
            assertEquals(List(IDS.size) { 1 }, IDS.map(f::mainCount))

            f.clock.addAndGet(60_000)
            f.open(ALBUM_B)
            f.awaitAlbum(ALBUM_B, MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.open(ALBUM_A)
            f.repository.refreshTargets()
            f.awaitIdle()
            assertEquals("Navigation and playlist recovery cannot restart the same failed Main request",
                List(IDS.size) { 1 }, IDS.map(f::mainCount))

            f.clock.set(NOW + 5 * 60_000L + 1)
            f.failMain = false
            f.repository.refreshTargets()
            f.awaitAlbum(ALBUM_A, MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            assertEquals("Navigation must not extend the original failure's retry deadline",
                List(IDS.size) { 2 }, IDS.map(f::mainCount))
        }
    }

    @Test(timeout = 45_000)
    fun eachNewAuthenticationSessionMayRetryOnceButItsOwnFailuresStillObeyTheDelay() = runBlocking {
        withFixture { f ->
            f.failMain = true
            f.start()
            f.open(ALBUM_A)
            f.awaitAlbum(ALBUM_A, MetadataFetchEntity.FAILED)
            f.awaitIdle()
            assertEquals(List(IDS.size) { 1 }, IDS.map(f::mainCount))

            f.auth.value = 1
            f.repository.refreshTargets()
            f.await { IDS.all { f.mainCount(it) >= 2 } }
            f.awaitIdle()
            assertEquals(List(IDS.size) { 2 }, IDS.map(f::mainCount))
            f.open(ALBUM_B)
            f.awaitAlbum(ALBUM_B, MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.open(ALBUM_A)
            f.repository.refreshTargets()
            f.awaitIdle()
            assertEquals("Failures made after login are not failures from the previous login",
                List(IDS.size) { 2 }, IDS.map(f::mainCount))

            f.auth.value = 2
            f.repository.refreshTargets()
            f.await { IDS.all { f.mainCount(it) >= 3 } }
            f.awaitIdle()
            assertEquals(List(IDS.size) { 3 }, IDS.map(f::mainCount))
        }
    }

    @Test(timeout = 45_000)
    fun anAlbumYieldedDuringNavigationResumesOnReturnWithoutRepeatingCompletedSources() = runBlocking {
        withFixture { f ->
            f.holdMain = true
            f.start()
            f.open(ALBUM_A)
            f.await { IDS.all { f.mainCount(it) == 1 } }
            f.open(ALBUM_B)
            f.holdMain = false
            f.mainRelease.complete(Unit)
            f.awaitAlbum(ALBUM_B, MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            assertNull("The interrupted album must not acquire a fake complete/failed cache entry",
                f.albumState(ALBUM_A))

            f.open(ALBUM_A)
            f.awaitAlbum(ALBUM_A, MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            assertEquals(List(IDS.size) { 1 }, IDS.map(f::mainCount))
            assertNotNull(f.albumState(ALBUM_A))
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
        private val job = SupervisorJob()
        private val locale = YouTubeLocale("JP", "ja")
        val auth = MutableStateFlow(0L)
        val clock = AtomicLong(NOW)
        private val calls = ConcurrentHashMap<String, AtomicInteger>()
        val mainRelease = CompletableDeferred<Unit>()
        @Volatile var failMain = false
        @Volatile var holdMain = false
        @Volatile var ordinaryQueueEnabled = false
        private lateinit var observer: (List<YTItem>, YouTubeLocale, String) -> Unit
        lateinit var repository: MetadataNameRepository
            private set

        suspend fun start() {
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                preferences = MutableStateFlow(preferencesOf(ContentCountryKey to "JP", ContentLanguageKey to "ja",
                    PreferEnglishOriginalKey to true)),
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { auth.value }, authUpdates = auth, now = clock::get,
                contextKey = { CONTEXT_KEY }, observeMetadata = { observer = it }, publishNames = { _, _ -> },
                queue = { ids, requestLocale -> Result.success(if (ordinaryQueueEnabled)
                    songs(ALBUM_A, requestLocale.hl).filter { it.id in ids }.map { it.copy(album = null) }
                    else emptyList()) },
                album = { id, requestLocale -> Result.success(album(id, requestLocale.hl)) },
                albumPage = { id, _ -> Result.success(AlbumPage(album(id, "en"), songs(id, "en"), emptyList())) },
                artist = { id, _ -> Result.success(ArtistItem(id, "Retry Artist", null,
                    shuffleEndpoint = null, radioEndpoint = null)) },
                main = { id, _ ->
                    calls.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                    if (holdMain) mainRelease.await()
                    if (failMain) Result.failure(IOException("Temporary original source failure"))
                    else {
                        val title = TITLES[IDS.indexOf(id)]
                        Result.success(ArtTrackOriginalMetadata(id, title, "Retry Artist - Topic", "UCretry",
                            "Provided to YouTube by Retry Records\n\n$title · Retry Artist\n\n$ALBUM_TITLE\n\n" +
                                "℗ 2026 Retry Records\n\nAuto-generated by YouTube."))
                    }
                },
                playlistReferences = { id, _ ->
                    val albumId = if (id == playlistId(ALBUM_A)) ALBUM_A else ALBUM_B
                    check(id == playlistId(albumId))
                    Result.success(PlaylistSongReferences(id,
                        IDS.mapIndexed { index, source -> PlaylistSongReference(id, "entry-$index", source, source) },
                        songs(albumId, "en"), songs(albumId, "en")))
                },
                albumSongSources = { _, _ -> Result.success(emptyList()) },
                mainSongReference = { _, _ -> Result.success(null) },
            ))
            repository.start()
            withTimeout(15_000) { repository.initialized.first { it } }
        }

        suspend fun open(id: String) {
            val previous = database.metadataNames("ALBUM", id).filter { it.source == "album" }.maxOfOrNull { it.observedAt } ?: 0
            repository.setForegroundAlbum(id, true)
            delay(2) // Distinguish an observer packet from an earlier visit at the same fake clock.
            observer(listOf(album(id, "ja")) + songs(id, "ja"), locale, "album")
            await { database.metadataNames("ALBUM", id).any { it.source == "album" && it.observedAt > previous } }
        }

        private fun album(id: String, language: String) = AlbumItem(id, playlistId(id),
            title = if (language == "en") ALBUM_TITLE else "再試行アルバム", artists = emptyList(), thumbnail = "")

        private fun songs(albumId: String, language: String) = IDS.mapIndexed { index, id ->
            SongItem(id, if (language == "en") TITLES[index] else "保存された曲 ${index + 1}", emptyList(),
                Album(if (language == "en") ALBUM_TITLE else "再試行アルバム", albumId), duration = 180, thumbnail = "",
                endpoint = WatchEndpoint(videoId = id, watchEndpointMusicSupportedConfigs =
                    WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                        WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_ATV"))))
        }

        fun mainCount(id: String) = calls[id]?.get() ?: 0
        fun sourceState(id: String) = database.metadataFetch("SONG", id, "und", originalMetadataContextKey(locale))?.status
        fun albumState(id: String) = database.metadataFetch("ALBUM", id, "und", albumOriginalContextKey(CONTEXT_KEY))?.status
        suspend fun awaitAlbum(id: String, status: String) = await { albumState(id) == status }
        suspend fun await(predicate: () -> Boolean) = withTimeout(15_000) { while (!predicate()) delay(10) }
        suspend fun awaitIdle() = withTimeout(15_000) {
            var quiet = 0
            while (quiet < 10) {
                quiet = if (repository.pendingRequestCount == 0) quiet + 1 else 0
                delay(10)
            }
        }
        suspend fun close() { job.cancelAndJoin(); database.close() }
    }

    companion object {
        private const val ALBUM_A = "MPRE-retry-album-a"
        private const val ALBUM_B = "MPRE-retry-album-b"
        private const val ALBUM_TITLE = "A Collection Of Songs We Will Always Remember"
        private const val CONTEXT_KEY = "JP:original-retry"
        private const val NOW = 1_800_000_000_000L
        private val IDS = listOf("retrytest01", "retrytest02", "retrytest03")
        private val TITLES = listOf("I Will Always Remember You", "There Is A Light That Never Goes Out",
            "We Are Never Ever Getting Back Together")
        private fun playlistId(albumId: String) = "OLAK-retry-$albumId"
    }
}
