package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.PlaylistSongReferences
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.AlbumPage
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room and repository scheduling; held fixture responses make overlap/order deterministic. */
class MetadataFetchEfficiencyTest {
    @Test(timeout = 45_000)
    fun openingAnAlbumPassesTheSavedLibraryBacklogAfterTheActiveRequestsFinish(): Unit = runBlocking {
        withFixture { f ->
            val savedArtists = 60
            repeat(savedArtists) { index ->
                f.database.insert(ArtistEntity("UCbacklog$index", "Saved Artist $index",
                    bookmarkedAt = LocalDateTime.of(2026, 9, 27, 0, 0)))
            }
            f.holdBackground = true
            f.start()
            f.await { f.backgroundStarted.get() == 3 && f.repository.pendingRequestCount >= savedArtists * 2 }
            f.repository.setForegroundAlbum(ALBUM_ID, true)
            // All three album requests must be queued before any occupied worker is released.
            f.await { f.repository.pendingRequestCount >= savedArtists * 2 + 3 }
            repeat(3) { f.backgroundRelease.send(Unit) }
            withTimeout(15_000) { f.pageStarted.await() }

            val starts = f.remoteStarts.toList()
            val pageIndex = starts.indexOf("page:en")
            assertTrue("The full album must start before draining the saved artists: $starts", pageIndex >= 0)
            assertTrue("Only the active workers plus a bounded fairness allowance may precede the page: $starts",
                starts.take(pageIndex).count { it.startsWith("artist:UCbacklog") } <= 6)
            assertTrue("The remaining saved requests must not be discarded", f.repository.pendingRequestCount > 20)
            assertTrue(f.remotePeak.get() <= 3)
        }
    }

    @Test(timeout = 45_000)
    fun albumOriginalsOverlapAnActiveDirectLookupWithoutDuplicatingItOrExceedingThreeCalls(): Unit = runBlocking {
        withFixture { f ->
            f.directQueueResults = true
            f.holdMain = true
            f.start()
            f.repository.setPlayingSong(f.ids.first())
            withTimeout(15_000) { f.firstMainStarted.await() }
            assertEquals(1, f.mainStarted.get())

            // The first album track is already held by the single-song path. Other album tracks
            // must progress without waiting for its response or requesting that same ID again.
            f.openAlbum()
            withTimeout(15_000) { f.parallelMainStarted.await() }
            assertTrue(f.mainPeak.get() > 1)
            f.mainRelease.complete(Unit)
            f.awaitAlbumSuccess()
            f.awaitIdle()

            assertEquals(List(f.ids.size) { 1 }, f.ids.map(f::mainCount))
            assertTrue("Album child tasks must share the repository's network limit", f.remotePeak.get() <= 3)
            assertTrue(f.mainPeak.get() in 2..3)
            assertEquals(f.ids.toSet(), f.database.metadataNameSnapshot().filter {
                it.kind == "SONG" && it.targetId in f.ids && originalCandidate(it) != null
            }.map { it.targetId }.toSet())
            assertNull("Opening a page cannot bookmark the album", f.database.albumById(ALBUM_ID))
        }
    }

    @Test(timeout = 45_000)
    fun theFullAlbumHeaderIsReusedForPlaylistProofAndARepeatedFreshVisitUsesNoNetwork(): Unit = runBlocking {
        withFixture { f ->
            f.start()
            f.openAlbum()
            f.awaitAlbumSuccess()
            f.awaitIdle()
            assertEquals(1, f.pageCalls.get())
            assertEquals(1, f.playlistCalls.get())
            assertTrue("An early normal detail request is allowed; playlist proof must reuse the full page header",
                f.englishHeaderCalls.get() <= 1)
            assertEquals(List(f.ids.size) { 1 }, f.ids.map(f::mainCount))

            val before = f.remoteStarts.toList()
            val observedBefore = f.database.metadataNames("ALBUM", ALBUM_ID)
                .filter { it.source == "album" }.maxOf { it.observedAt }
            f.repository.setForegroundAlbum(ALBUM_ID, false)
            delay(2) // The observation timestamp distinguishes the second processed packet.
            f.openAlbum()
            f.await {
                f.database.metadataNames("ALBUM", ALBUM_ID).any { it.source == "album" && it.observedAt > observedBefore }
            }
            f.repository.refreshTargets()
            f.awaitIdle()
            assertEquals("Fresh cache entries must survive another visit and explicit refresh", before, f.remoteStarts.toList())
            assertEquals(1, f.pageCalls.get())
            assertEquals(1, f.playlistCalls.get())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val databaseRoom = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(databaseRoom)
        private val job = SupervisorJob()
        private val locale = YouTubeLocale("JP", "ja")
        private val locales = MutableStateFlow(locale)
        private val auth = MutableStateFlow(0L)
        private val preferences = MutableStateFlow(preferencesOf(ContentCountryKey to "JP",
            ContentLanguageKey to "ja", PreferEnglishOriginalKey to true))
        val ids = (1..6).map { "efficien${it.toString().padStart(3, '0')}" }
        private val titles = listOf("I Will Always Remember You", "There Is A Light That Never Goes Out",
            "We Are Never Ever Getting Back Together", "You Have Always Been My Friend",
            "I Want To Stay With You Forever", "We Will Find Our Way Back Home")
        private val englishSongs = ids.mapIndexed { index, id -> SongItem(id, titles[index],
            listOf(Artist(ARTIST_NAME, ARTIST_ID)), Album(ALBUM_TITLE, ALBUM_ID), duration = 180, thumbnail = "",
            endpoint = WatchEndpoint(videoId = id, watchEndpointMusicSupportedConfigs =
                WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_ATV")))) }
        private val japaneseSongs = englishSongs.mapIndexed { index, song ->
            song.copy(title = "日本語の曲名 ${index + 1}", album = Album("日本語のアルバム", ALBUM_ID))
        }
        val remoteStarts = CopyOnWriteArrayList<String>()
        private val remoteActive = AtomicInteger()
        val remotePeak = AtomicInteger()
        val mainStarted = AtomicInteger()
        private val mainActive = AtomicInteger()
        val mainPeak = AtomicInteger()
        private val mainCalls = ConcurrentHashMap<String, AtomicInteger>()
        val pageCalls = AtomicInteger()
        val englishHeaderCalls = AtomicInteger()
        val playlistCalls = AtomicInteger()
        val backgroundStarted = AtomicInteger()
        val backgroundRelease = Channel<Unit>(Channel.UNLIMITED)
        val pageStarted = CompletableDeferred<Unit>()
        val firstMainStarted = CompletableDeferred<Unit>()
        val parallelMainStarted = CompletableDeferred<Unit>()
        val mainRelease = CompletableDeferred<Unit>()
        @Volatile var holdMain = false
        @Volatile var holdBackground = false
        @Volatile var directQueueResults = false
        private lateinit var observer: (List<YTItem>, YouTubeLocale, String) -> Unit
        lateinit var repository: MetadataNameRepository
            private set

        suspend fun start() {
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), preferences = preferences,
                locale = { locale }, localeUpdates = locales, authRevision = { auth.value }, authUpdates = auth,
                now = { NOW }, contextKey = { CONTEXT_KEY }, observeMetadata = { observer = it },
                publishNames = { _, _ -> },
                queue = { requestedIds, requested -> remote("queue:${requested.hl}") {
                    Result.success(if (!directQueueResults) emptyList() else
                        (if (requested.hl == "en") englishSongs else japaneseSongs).filter { it.id in requestedIds })
                } },
                album = { id, requested -> remote("header:${requested.hl}") {
                    check(id == ALBUM_ID)
                    if (requested.hl == "en") englishHeaderCalls.incrementAndGet()
                    Result.success(album(requested.hl))
                } },
                albumPage = { id, requested -> remote("page:${requested.hl}") {
                    check(id == ALBUM_ID && requested.hl == "en")
                    pageCalls.incrementAndGet()
                    pageStarted.complete(Unit)
                    Result.success(AlbumPage(album("en"), englishSongs, emptyList()))
                } },
                albumContext = { _, _ -> error("The full production album-page response must be used") },
                artist = { id, _ -> remote("artist:$id") {
                    if (id.startsWith("UCbacklog")) {
                        backgroundStarted.incrementAndGet()
                        if (holdBackground) backgroundRelease.receive()
                    }
                    Result.success(ArtistItem(id, ARTIST_NAME, null, shuffleEndpoint = null, radioEndpoint = null))
                } },
                main = { id, requested -> remote("main:$id") {
                    check(requested.hl == "en")
                    mainCalls.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                    mainStarted.incrementAndGet()
                    val active = mainActive.incrementAndGet()
                    mainPeak.updateAndGet { maxOf(it, active) }
                    firstMainStarted.complete(Unit)
                    if (active >= 2) parallelMainStarted.complete(Unit)
                    try {
                        if (holdMain) mainRelease.await()
                        val title = englishSongs.single { it.id == id }.title
                        Result.success(ArtTrackOriginalMetadata(id, title, "$ARTIST_NAME - Topic", ARTIST_ID,
                            "Provided to YouTube by Efficiency Records\n\n$title · $ARTIST_NAME\n\n$ALBUM_TITLE\n\n" +
                                "℗ 2026 Efficiency Records\n\nAuto-generated by YouTube."))
                    } finally { mainActive.decrementAndGet() }
                } },
                playlistReferences = { playlistId, _ -> remote("playlist") {
                    check(playlistId == PLAYLIST_ID)
                    playlistCalls.incrementAndGet()
                    Result.success(PlaylistSongReferences(playlistId,
                        ids.mapIndexed { index, id -> PlaylistSongReference(playlistId, "entry-$index", id, id) },
                        englishSongs, englishSongs))
                } },
                albumSongSources = { _, _ -> remote("source-search") { Result.success(emptyList()) } },
                mainSongReference = { _, _ -> remote("song-reference") { Result.success(null) } },
            ))
            repository.start()
            withTimeout(15_000) { repository.initialized.first { it } }
        }

        fun openAlbum() {
            repository.setForegroundAlbum(ALBUM_ID, true)
            observer(listOf(album("ja")) + japaneseSongs, locale, "album")
        }

        private fun album(language: String) = AlbumItem(ALBUM_ID, PLAYLIST_ID,
            title = if (language == "en") ALBUM_TITLE else "日本語のアルバム",
            artists = listOf(Artist(ARTIST_NAME, ARTIST_ID)), thumbnail = "")

        private suspend fun <T> remote(label: String, block: suspend () -> T): T {
            val active = remoteActive.incrementAndGet()
            remotePeak.updateAndGet { maxOf(it, active) }
            remoteStarts.add(label)
            try { return block() } finally { remoteActive.decrementAndGet() }
        }

        fun mainCount(id: String) = mainCalls[id]?.get() ?: 0

        suspend fun awaitAlbumSuccess() = await {
            database.metadataFetch("ALBUM", ALBUM_ID, "und", albumOriginalContextKey(CONTEXT_KEY))?.status == MetadataFetchEntity.SUCCESS
        }

        suspend fun awaitIdle() {
            withTimeout(15_000) {
                var quietChecks = 0
                while (quietChecks < 10) {
                    quietChecks = if (repository.pendingRequestCount == 0 && remoteActive.get() == 0) quietChecks + 1 else 0
                    delay(10)
                }
            }
        }

        suspend fun await(predicate: () -> Boolean) {
            withTimeout(15_000) { while (!predicate()) delay(10) }
        }

        suspend fun close() { job.cancelAndJoin(); database.close(); backgroundRelease.close() }
    }

    companion object {
        private const val ALBUM_ID = "MPRE-fetch-efficiency"
        private const val PLAYLIST_ID = "OLAK5uy-fetch-efficiency"
        private const val ARTIST_ID = "UCfetch-efficiency"
        private const val ARTIST_NAME = "Efficiency Artist"
        private const val ALBUM_TITLE = "A Collection Of Songs We Will Always Remember"
        private const val CONTEXT_KEY = "JP:fetch-efficiency"
        private const val NOW = 1_800_000_000_000L
    }
}
