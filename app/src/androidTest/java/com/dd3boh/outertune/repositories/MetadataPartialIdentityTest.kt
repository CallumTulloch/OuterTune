package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.metadata.*
import com.zionhuang.innertube.models.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** Real Room plus controlled arrival ordering for a complete detail and an artistless album row. */
class MetadataPartialIdentityTest {
    @Test(timeout = 45_000)
    fun detailBeforeArtistlessAlbumAddsContextWithoutLosingPreviouslyVerifiedArtistIds() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.observeDetails()
            f.await { f.idle() && f.originals().count { it.target.kind == OriginalNameKind.SONG } == 3 }
            assertTrue(f.originals().filter { it.target.kind == OriginalNameKind.SONG }.all { it.albumId == null })
            assertTrue(f.originals().any { it.target.kind == OriginalNameKind.ARTIST })

            f.openAlbum()
            f.await { f.idle() && f.originals().filter { it.target.kind == OriginalNameKind.SONG }
                .let { it.size == 3 && it.all { song -> song.albumId == ALBUM } } && f.allEnglish() }
            assertTrue(f.originals().filter { it.target.kind == OriginalNameKind.ARTIST }.all {
                it.target.id == ARTIST && it.albumId == ALBUM
            })
            assertEquals(f.titles, f.english.map { it.title })
            assertNull("Opening an album must not bookmark or register it", f.database.albumById(ALBUM))
        } finally { f.close() }
    }

    @Test(timeout = 45_000)
    fun artistlessAlbumBeforeDetailEnrichesImmediatelyOnceWithoutWaitingForTheSuccessTtl() = runBlocking {
        val f = Fixture(holdQueue = true)
        try {
            f.start()
            f.openAlbum()
            f.await { f.originals().filter { it.target.kind == OriginalNameKind.SONG }
                .let { it.size == 3 && it.all { song -> song.albumId == ALBUM } } }
            assertFalse(f.originals().any { it.target.kind == OriginalNameKind.ARTIST })
            f.queueRelease.complete(Unit)
            f.await { f.idle() && f.originals().any {
                it.target.kind == OriginalNameKind.ARTIST && it.sourceVideoId == f.english.first().id
            } && f.allEnglish() }
            val source = f.originals().filter { it.sourceVideoId == f.english.first().id }
            assertEquals(setOf(OriginalNameKind.SONG, OriginalNameKind.ARTIST), source.map { it.target.kind }.toSet())
            assertTrue(source.all { it.albumId == ALBUM })
            assertEquals(ARTIST, source.single { it.target.kind == OriginalNameKind.ARTIST }.target.id)
            val calls = f.mainCalls.get()
            assertEquals("One source observation plus one later identity enrichment per track", 6, calls)
            f.repeatObservation()
            assertEquals("Repeated observations must not renew the successful source again", calls, f.mainCalls.get())
        } finally { f.close() }
    }

    @Test(timeout = 45_000)
    fun failedIdentityEnrichmentKeepsTheValidSongAndObeysTheFailureRetryWindow() = runBlocking {
        val f = Fixture(holdQueue = true)
        try {
            f.start()
            f.openAlbum()
            f.await { f.originals().count { it.target.kind == OriginalNameKind.SONG } == 3 && f.allEnglish() }
            f.failMain = true
            f.queueRelease.complete(Unit)
            f.await { f.idle() && f.mainCalls.get() == 6 }
            assertTrue(f.allEnglish())
            assertFalse(f.originals().any { it.target.kind == OriginalNameKind.ARTIST })
            f.repeatObservation()
            assertEquals("A failing enrichment must not retry on every foreground/metadata refresh", 6, f.mainCalls.get())
            f.failMain = false
            f.clock.addAndGet(metadataRetryDelay(com.dd3boh.outertune.db.entities.MetadataFetchEntity.FAILED) + 1)
            f.repository.refreshTargets()
            f.await { f.idle() && f.originals().count { it.target.kind == OriginalNameKind.ARTIST } == 3 }
            assertEquals("Normal source recovery must run after the failure window, not the former seven-day success TTL",
                9, f.mainCalls.get())
        } finally { f.close() }
    }

    @Test(timeout = 60_000)
    fun queueCompletingDuringAlbumMainFillsMissingCreditsButCannotReplaceConflictingKnownIds() = runBlocking {
        val cases = listOf(
            emptyList<Artist>() to listOf(ARTIST),
            listOf(Artist(ARTIST_NAME, null)) to listOf(ARTIST),
            listOf(Artist(ARTIST_NAME, UPDATED_ARTIST), Artist("Unidentified credit", null)) to emptyList(),
        )
        for ((byline, expectedArtists) in cases) {
            val f = Fixture(holdQueue = true, holdFirstMain = true, albumArtists = byline)
            try {
                f.start()
                f.openAlbum()
                withTimeout(15_000) { f.firstMainEntered.await() }
                f.queueRelease.complete(Unit)
                f.await { f.database.metadataNames("SONG", f.english.first().id).any {
                    it.language == "en" && it.source == "detail"
                } }
                // Keep Main held while the source worker finishes caching its committed detail.
                delay(100)
                f.firstMainRelease.complete(Unit)
                f.await { f.idle() && f.originals().any {
                    it.target.kind == OriginalNameKind.SONG && it.sourceVideoId == f.english.first().id
                } }
                val artists = f.originals().filter {
                    it.target.kind == OriginalNameKind.ARTIST && it.sourceVideoId == f.english.first().id
                }
                assertEquals(expectedArtists, artists.map { it.target.id })
                assertEquals("The in-flight album Main already corroborates this exact fresh queue credit", 1,
                    f.mainCount(f.english.first().id))
            } finally { f.close() }
        }
    }

    @Test(timeout = 45_000)
    fun aDistinctDetailArrivingDuringEnrichmentIsNotDiscardedByTheFirstWorkersCleanup() = runBlocking {
        val f = Fixture(holdQueue = true, holdFirstEnrichment = true)
        try {
            f.start()
            f.openAlbum()
            f.await { f.originals().count { it.target.kind == OriginalNameKind.SONG } == 3 }
            f.queueRelease.complete(Unit)
            withTimeout(15_000) { f.enrichmentEntered.await() }
            // A later complete Music response supplies a changed provider identity. Hold the old
            // Main response so that both payloads overlap in the same source/session queue slot.
            f.detailArtists = listOf(Artist(ARTIST_NAME, UPDATED_ARTIST))
            f.clock.addAndGet(metadataRetryDelay(com.dd3boh.outertune.db.entities.MetadataFetchEntity.SUCCESS) + 1)
            f.repository.refreshTargets()
            f.await { f.database.metadataNames("ARTIST", UPDATED_ARTIST).any { it.source == "detail" } }
            delay(100)
            f.enrichmentRelease.complete(Unit)
            f.await { f.idle() && f.originals().any { it.sourceVideoId == f.english.first().id &&
                it.target.kind == OriginalNameKind.ARTIST && it.target.id == UPDATED_ARTIST } }
            val artists = f.originals().filter {
                it.sourceVideoId == f.english.first().id && it.target.kind == OriginalNameKind.ARTIST
            }
            assertEquals(listOf(UPDATED_ARTIST), artists.map { it.target.id })
            assertEquals(3, f.mainCount(f.english.first().id))
            val calls = f.mainCalls.get()
            f.repeatObservation()
            assertEquals("Both semantic payloads are consumed once, without a refresh loop", calls, f.mainCalls.get())
        } finally { f.close() }
    }

    @Test(timeout = 45_000)
    fun anOldAuthenticationResponseCannotJoinCurrentAlbumMembership() = runBlocking {
        val f = Fixture(holdOldMain = true)
        try {
            f.start()
            f.openAlbum()
            withTimeout(15_000) { f.oldMainEntered.await() }
            f.auth.value = 1L
            f.mainRelease.complete(Unit)
            f.await { f.idle() && f.originals().count { it.target.kind == OriginalNameKind.SONG } == 3 && f.allEnglish() }
            assertFalse(f.database.metadataNameSnapshot().any { it.name == STALE_TITLE })
            assertFalse(f.frames.any { names -> names.values.any { it == STALE_TITLE } })
            assertTrue(f.originals().filter { it.target.kind == OriginalNameKind.SONG }.all { it.albumId == ALBUM })
        } finally { f.close() }
    }

    private class Fixture(holdQueue: Boolean = false, private val holdOldMain: Boolean = false,
        private val holdFirstMain: Boolean = false, private val holdFirstEnrichment: Boolean = false,
        private val albumArtists: List<Artist> = emptyList()) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
        private val job = SupervisorJob()
        val clock = AtomicLong(1_850_000_000_000L)
        val mainCalls = AtomicInteger()
        private val mainCallsById = ConcurrentHashMap<String, AtomicInteger>()
        @Volatile var failMain = false
        @Volatile var detailArtists = listOf(Artist(ARTIST_NAME, ARTIST))
        val auth = MutableStateFlow(0L)
        val queueRelease = CompletableDeferred<Unit>().also { if (!holdQueue) it.complete(Unit) }
        val oldMainEntered = CompletableDeferred<Unit>()
        val mainRelease = CompletableDeferred<Unit>()
        val firstMainEntered = CompletableDeferred<Unit>()
        val firstMainRelease = CompletableDeferred<Unit>()
        val enrichmentEntered = CompletableDeferred<Unit>()
        val enrichmentRelease = CompletableDeferred<Unit>()
        val titles = listOf("You Are In Love", "All You Had To Do Was Stay", "I Know Places")
        val english = titles.mapIndexed { index, title ->
            val id = "partial${index.toString().padStart(4, '0')}"
            SongItem(id, title, listOf(Artist(ARTIST_NAME, ARTIST)), Album(ALBUM_NAME, ALBUM), thumbnail = "",
                endpoint = WatchEndpoint(videoId = id, watchEndpointMusicSupportedConfigs =
                    WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                        WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_ATV"))))
        }
        private val locale = YouTubeLocale("JP", "ja")
        private lateinit var observer: (List<YTItem>, YouTubeLocale, String) -> Unit
        val selected = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val frames = CopyOnWriteArrayList<Map<OriginalNameTarget, String>>()
        lateinit var repository: MetadataNameRepository
        fun start() {
            val resolver = OriginalAlbumLanguageResolver(OriginalTextLanguageDetector { text ->
                listOf(OriginalTextLanguageScore("en", if ('\n' in text) 0.99f else 0.1f))
            })
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { auth.value }, authUpdates = auth, now = clock::get, contextKey = { "JP:partial-identity" },
                preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true)),
                observeMetadata = { observer = it }, publishNames = { names, _ -> selected.value = names; frames += names },
                queue = { ids, request ->
                    queueRelease.await()
                    Result.success(english.filter { it.id in ids }.map {
                        it.copy(title = if (request.hl == "en") it.title else "日本語 ${it.id}", artists = detailArtists)
                    })
                },
                album = { id, _ -> Result.success(AlbumItem(id, null, title = ALBUM_NAME,
                    artists = listOf(Artist(ARTIST_NAME, ARTIST)), thumbnail = "")) },
                albumContext = { _, _ -> Result.success(english.map { it.copy(artists = albumArtists) }) },
                artist = { id, _ -> Result.success(ArtistItem(id, ARTIST_NAME, null,
                    shuffleEndpoint = null, radioEndpoint = null)) },
                main = { id, _ ->
                    mainCalls.incrementAndGet()
                    val sourceCall = mainCallsById.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                    if (id == english.first().id && sourceCall == 1 && holdFirstMain) {
                        firstMainEntered.complete(Unit); firstMainRelease.await()
                    }
                    if (id == english.first().id && sourceCall == 2 && holdFirstEnrichment) {
                        enrichmentEntered.complete(Unit); enrichmentRelease.await()
                    }
                    val revision = auth.value
                    if (holdOldMain && revision == 0L) { oldMainEntered.complete(Unit); mainRelease.await() }
                    val title = if (holdOldMain && revision == 0L) STALE_TITLE else english.single { it.id == id }.title
                    if (failMain) Result.failure(java.io.IOException("Held enrichment failure")) else
                        Result.success(ArtTrackOriginalMetadata(id, title, "$ARTIST_NAME - Topic", ARTIST,
                            "Provided to YouTube by Fixture\n\n$title · $ARTIST_NAME\n\nOriginal Edition\n\nAuto-generated by YouTube."))
                },
                mainSongReference = { _, _ -> Result.success(null) },
                albumSongSources = { _, _ -> Result.success(emptyList()) },
                assessOriginals = resolver::assess,
            )).also { it.start() }
        }
        fun observeDetails() = observer(english.map { it.copy(title = "日本語 ${it.id}") }, locale, "queue")
        fun openAlbum() {
            repository.setForegroundAlbum(ALBUM, true)
            val album = AlbumItem(ALBUM, null, title = ALBUM_NAME,
                artists = listOf(Artist(ARTIST_NAME, ARTIST)), thumbnail = "")
            observer(listOf(album) + english.map { it.copy(title = "日本語 ${it.id}", artists = albumArtists) }, locale, "album")
        }
        fun mainCount(id: String) = mainCallsById[id]?.get() ?: 0
        fun originals() = latestOriginalRows(database.metadataNameSnapshot()).mapNotNull(::originalCandidate)
        fun idle() = repository.initialized.value && repository.pendingRequestCount == 0
        fun allEnglish() = english.all { selected.value[OriginalNameTarget(OriginalNameKind.SONG, it.id)] == it.title }
        suspend fun repeatObservation() {
            val previousObservation = database.metadataNames("SONG", english.first().id)
                .filter { it.source == "queue" }.maxOfOrNull { it.observedAt } ?: Long.MIN_VALUE
            clock.incrementAndGet()
            delay(5)
            observeDetails()
            await { database.metadataNames("SONG", english.first().id).any {
                it.source == "queue" && it.observedAt > previousObservation
            } }
            repository.refreshTargets()
            delay(100)
            await { idle() }
        }
        suspend fun await(condition: () -> Boolean) = withTimeout(20_000) { while (!condition()) delay(10) }
        suspend fun close() {
            queueRelease.complete(Unit); mainRelease.complete(Unit); firstMainRelease.complete(Unit)
            enrichmentRelease.complete(Unit); job.cancelAndJoin(); database.close()
        }
    }

    companion object {
        private const val ALBUM = "MPREpartialIdentityDeluxe"
        private const val ALBUM_NAME = "Original Edition (Deluxe)"
        private const val ARTIST = "UCpartialIdentityArtist"
        private const val ARTIST_NAME = "Fixture Artist"
        private const val UPDATED_ARTIST = "UCupdatedIdentityArtist"
        private const val STALE_TITLE = "Stale Authentication Title"
    }
}
