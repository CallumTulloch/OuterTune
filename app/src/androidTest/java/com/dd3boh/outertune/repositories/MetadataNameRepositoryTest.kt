package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.YTItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real Room and repository workers, with isolated settings and deterministic provider responses. */
class MetadataNameRepositoryTest {
    @Test fun sameAccountLoginRoundTripRejectsDelayedDetailAndStartsNewRequest() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstReply = CompletableDeferred<Result<List<SongItem>>>()
        val secondReply = CompletableDeferred<Result<List<SongItem>>>()
        val calls = AtomicInteger()
        val fixture = Fixture("en")
        try {
            fixture.database.insert(SongEntity("track", "Raw", localPath = null))
            val repository = fixture.start { _, _ ->
                if (calls.incrementAndGet() == 1) { firstStarted.complete(Unit); firstReply.await() }
                else { secondStarted.complete(Unit); secondReply.await() }
            }
            withTimeout(10_000) { firstStarted.await() }
            fixture.authUpdates.value = 2L
            withTimeout(10_000) { secondStarted.await() }
            firstReply.complete(Result.success(listOf(SongItem("track", "Obsolete login", emptyList(), thumbnail = ""))))
            withTimeout(10_000) { while (repository.pendingRequestCount != 1) delay(10) }
            assertTrue(fixture.database.metadataNames("SONG", "track").isEmpty())
            secondReply.complete(Result.success(listOf(SongItem("track", "Current login", emptyList(), thumbnail = ""))))
            withTimeout(10_000) { fixture.names.first { it[fixture.song] == "Current login" } }
            assertEquals(2, calls.get())
            assertFalse(fixture.database.metadataNames("SONG", "track").any { it.name == "Obsolete login" })
        } finally { fixture.close() }
    }

    @Test fun newLoginRetriesPriorFailureWithoutDiscardingSuccessfulNameCache() = runBlocking {
        val calls = AtomicInteger()
        val fixture = Fixture("en")
        try {
            fixture.database.insert(SongEntity("track", "Raw", localPath = null))
            val repository = fixture.start { ids, _ ->
                if (calls.incrementAndGet() == 1) Result.failure(java.io.IOException("previous login failed"))
                else Result.success(ids.map { SongItem(it, "Recovered", emptyList(), thumbnail = "") })
            }
            withTimeout(10_000) {
                while (fixture.database.metadataFetch("SONG", "track", "en", "JP:test")?.status != MetadataFetchEntity.FAILED) delay(10)
                while (repository.pendingRequestCount != 0) delay(10)
            }
            fixture.authUpdates.value = 2L
            withTimeout(10_000) { fixture.names.first { it[fixture.song] == "Recovered" } }
            assertEquals(2, calls.get())
            fixture.authUpdates.value = 4L
            repository.refreshTargets()
            withTimeout(10_000) { while (repository.pendingRequestCount != 0) delay(10) }
            assertEquals(2, calls.get())
            assertEquals("Recovered", fixture.names.value[fixture.song])
        } finally { fixture.close() }
    }

    @Test fun detailDiscoveredArtistsAndAlbumsAcquireBothLanguagesWithoutWaitingForPeriodicRefresh() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.database.insert(SongEntity("track", "Raw title", localPath = null))
            fixture.start()
            withTimeout(10_000) {
                fixture.names.first { it[fixture.artist] == "人物の正式名" && it[fixture.album] == "アルバムの正式名" }
                while (fixture.database.metadataNames("ARTIST", "UC-person").none { it.language == "en" && it.sourcePriority == 100 } ||
                    fixture.database.metadataNames("ALBUM", "MPRE-album").none { it.language == "en" && it.sourcePriority == 100 }) delay(20)
            }
            assertEquals("Raw title", fixture.database.songForArtistCredit("track")!!.title)
            assertNull(fixture.database.albumById("MPRE-album"))
            assertNull(fixture.database.artistById("UC-person"))
            assertEquals(0, fixture.albumExpansions.get())
            assertEquals(0, fixture.mainRequests.get())
        } finally { fixture.close() }
    }

    @Test fun languageSwitchFetchesSavedTargetsEvenBeforeTheirFirstNameRequestCompletes() = runBlocking {
        val enStarted = CompletableDeferred<Unit>()
        val releaseEn = CompletableDeferred<Unit>()
        val fixture = Fixture("en")
        try {
            fixture.database.insert(SongEntity("track", "日本語の元表記", localPath = null))
            fixture.start { ids, locale ->
                if (locale.hl == "en") { enStarted.complete(Unit); releaseEn.await() }
                Result.success(ids.map { SongItem(it, if (locale.hl == "ja") "日本語の取得名" else "English", emptyList(), thumbnail = "") })
            }
            withTimeout(10_000) { enStarted.await() }
            assertNull(fixture.database.metadataTarget("SONG", "track"))
            fixture.locales.value = YouTubeLocale("JP", "ja")
            // The English response is still suspended: metadata_target does not exist yet.
            withTimeout(10_000) { fixture.names.first { it[fixture.song] == "日本語の取得名" } }
            releaseEn.complete(Unit)
            withTimeout(10_000) {
                while (fixture.database.metadataNames("SONG", "track").none { it.language == "en" }) delay(20)
            }
            assertEquals("日本語の取得名", fixture.names.value[fixture.song])
            assertEquals("日本語の元表記", fixture.database.songForArtistCredit("track")!!.title)
        } finally { fixture.close() }
    }

    @Test fun failedConfiguredLanguageNeverPublishesAnEnglishFallbackOverRawText(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            fixture.database.insert(SongEntity("track", "日本語の元表記", localPath = null))
            fixture.start { ids, locale ->
                if (locale.hl == "ja") Result.failure(java.io.IOException("offline"))
                else Result.success(ids.map { SongItem(it, "Unverified English", emptyList(), thumbnail = "") })
            }
            withTimeout(10_000) {
                while (fixture.database.metadataFetch("SONG", "track", "ja", "JP:test")?.status != MetadataFetchEntity.FAILED) delay(20)
                fixture.aliases.first { it[fixture.song]?.contains("Unverified English") == true }
            }
            assertFalse(fixture.names.value.containsKey(fixture.song))
            fixture.locales.value = YouTubeLocale("JP", "en")
            withTimeout(10_000) { fixture.names.first { it[fixture.song] == "Unverified English" } }
            fixture.locales.value = YouTubeLocale("JP", "ja")
            withTimeout(10_000) { fixture.names.first { !it.containsKey(fixture.song) } }
        } finally { fixture.close() }
    }

    @Test fun nextDayLibraryReplyKeepsBothLanguagesAndTheCachedJapaneseDetailAfterRestart(): Unit = runBlocking {
        val fixture = RestartingNameFixture()
        try {
            fixture.seedDetails()
            fixture.clock.addAndGet(RestartingNameFixture.DAY)
            fixture.start()
            fixture.awaitPublished { it.selected[fixture.artist] == "椎名林檎" }

            // An English response remains a separate observation even while Japanese is selected.
            fixture.emit("English comparison spelling", "en")
            val afterEnglish = fixture.awaitPublished { it.aliases[fixture.artist]?.contains("English comparison spelling") == true }
            assertEquals("椎名林檎", afterEnglish.selected[fixture.artist])

            // A Japanese request can still receive an English spelling from a list route.
            fixture.emit("Ringo Sheena", "ja")
            val afterJapanese = fixture.awaitPublished { it.aliases[fixture.artist]?.contains("Ringo Sheena") == true }
            assertEquals("椎名林檎", afterJapanese.selected[fixture.artist])
            fixture.assertHistoryAndFreshDetails(expectedNames = 4)

            fixture.settings.value = preferences("ja")
            val disabled = fixture.awaitPublished { !it.preferEnglishOriginal }
            assertEquals("椎名林檎", disabled.selected[fixture.artist])

            fixture.restart()
            val restarted = fixture.awaitPublished { it.aliases[fixture.artist]?.contains("Ringo Sheena") == true }
            assertEquals("椎名林檎", restarted.selected[fixture.artist])
            fixture.assertHistoryAndFreshDetails(expectedNames = 4)
        } finally { fixture.close() }
    }

    @Test fun persistedPriorityHundredLibraryRowsRecoverWithoutDeletingNamesOrWaitingSevenDays(): Unit = runBlocking {
        val fixture = RestartingNameFixture()
        try {
            fixture.seedDetails()
            fixture.clock.addAndGet(RestartingNameFixture.DAY)
            fixture.database.recordMetadataNames(listOf(MetadataNameEntity(
                "ARTIST", fixture.artist.id, "ja", "Ringo Sheena", "library", 100, fixture.clock.get(),
            )))
            fixture.start()
            val first = fixture.awaitPublished { it.aliases[fixture.artist]?.contains("Ringo Sheena") == true }
            assertEquals("椎名林檎", first.selected[fixture.artist])
            fixture.assertHistoryAndFreshDetails(expectedNames = 3)

            fixture.restart()
            val restarted = fixture.awaitPublished { it.aliases[fixture.artist]?.contains("Ringo Sheena") == true }
            assertEquals("椎名林檎", restarted.selected[fixture.artist])
            fixture.assertHistoryAndFreshDetails(expectedNames = 3)
            // Existing cache evidence is retained; its old numeric priority cannot control display.
            assertEquals(100, fixture.database.metadataNames("ARTIST", fixture.artist.id).single { it.source == "library" }.sourcePriority)
        } finally { fixture.close() }
    }

    private data class PublishedNames(
        val selected: Map<OriginalNameTarget, String> = emptyMap(),
        val aliases: Map<OriginalNameTarget, List<String>> = emptyMap(),
        val preferEnglishOriginal: Boolean = true,
    )

    /** Reopens the actual Room file and creates fresh workers, as after a process restart. */
    private class RestartingNameFixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val databaseName = "metadata-name-idle-${System.nanoTime()}.db"
        private fun openDatabase() = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build())
        var database = openDatabase()
            private set
        private var job = SupervisorJob()
        private val detailTime = System.currentTimeMillis() - DAY
        val clock = AtomicLong(detailTime)
        val artist = OriginalNameTarget(OriginalNameKind.ARTIST, "UC-idle-artist")
        val settings = MutableStateFlow<Preferences>(preferencesOf(
            ContentCountryKey to "JP", ContentLanguageKey to "ja", PreferEnglishOriginalKey to true,
        ))
        private val locales = MutableStateFlow(YouTubeLocale("JP", "ja"))
        private val published = MutableStateFlow(PublishedNames())
        private val detailRequests = AtomicInteger()
        private lateinit var observer: (List<YTItem>, YouTubeLocale, String) -> Unit

        fun seedDetails() {
            for ((language, text) in listOf("ja" to "椎名林檎", "en" to "Sheena Ringo")) {
                database.recordMetadataNames(listOf(MetadataNameEntity(
                    "ARTIST", artist.id, language, text, "detail", 100, detailTime,
                )), MetadataFetchEntity("ARTIST", artist.id, language, MetadataFetchEntity.SUCCESS, detailTime, "JP:idle-test"))
            }
        }

        fun start() {
            MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), preferences = settings,
                locale = { locales.value }, localeUpdates = locales,
                now = clock::get, contextKey = { "JP:idle-test" },
                observeMetadata = { observer = it },
                publishNames = { selected, aliases ->
                    published.value = PublishedNames(selected, aliases, settings.value[PreferEnglishOriginalKey] ?: false)
                },
                artist = { _, _ ->
                    detailRequests.incrementAndGet()
                    Result.failure(java.io.IOException("Offline: cached successful details must remain usable"))
                },
                queue = { _, _ -> error("This fixture contains only an artist") },
                album = { _, _ -> error("This fixture contains only an artist") },
                albumContext = { _, _ -> error("This fixture contains only an artist") },
                main = { _, _ -> error("This fixture contains only an artist") },
            )).start()
        }

        fun emit(name: String, language: String) {
            observer(listOf(ArtistItem(artist.id, name, null, shuffleEndpoint = null, radioEndpoint = null)),
                YouTubeLocale("JP", language), "library")
        }

        suspend fun awaitPublished(predicate: (PublishedNames) -> Boolean): PublishedNames =
            withTimeout(10_000) { published.first(predicate) }

        suspend fun assertHistoryAndFreshDetails(expectedNames: Int) {
            // Give the repository's 25 ms collection window time to dispatch the scheduled checks.
            delay(100)
            assertEquals(0, detailRequests.get())
            val rows = database.metadataNames("ARTIST", artist.id)
            assertEquals(expectedNames, rows.size)
            assertTrue(rows.any { it.language == "ja" && it.name == "椎名林檎" && it.source == "detail" })
            assertTrue(rows.any { it.language == "en" && it.name == "Sheena Ringo" && it.source == "detail" })
            for (language in listOf("en", "ja")) {
                val fetch = database.metadataFetch("ARTIST", artist.id, language, "JP:idle-test")!!
                assertEquals(MetadataFetchEntity.SUCCESS, fetch.status)
                assertEquals(detailTime, fetch.updatedAt)
                assertEquals(6 * DAY, fetch.updatedAt + metadataRetryDelay(fetch.status) - clock.get())
            }
        }

        suspend fun restart() {
            job.cancelAndJoin()
            database.close()
            database = openDatabase()
            job = SupervisorJob()
            published.value = PublishedNames()
            start()
        }

        suspend fun close() {
            job.cancelAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }

        companion object { const val DAY = 24 * 60 * 60_000L }
    }

    private class Fixture(language: String = "ja") {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(internal)
        private val job = SupervisorJob()
        val settings = MutableStateFlow(preferences(language))
        val locales = MutableStateFlow(YouTubeLocale("JP", language))
        val authUpdates = MutableStateFlow(0L)
        val names = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val aliases = MutableStateFlow<Map<OriginalNameTarget, List<String>>>(emptyMap())
        val albumExpansions = AtomicInteger()
        val mainRequests = AtomicInteger()
        val song = OriginalNameTarget(OriginalNameKind.SONG, "track")
        val artist = OriginalNameTarget(OriginalNameKind.ARTIST, "UC-person")
        val album = OriginalNameTarget(OriginalNameKind.ALBUM, "MPRE-album")

        fun start(queue: suspend (List<String>, YouTubeLocale) -> Result<List<SongItem>> = { ids, locale ->
            Result.success(ids.map { SongItem(it, if (locale.hl == "ja") "曲名" else "Title",
                listOf(Artist("Embedded artist", "UC-person")), album = Album("Embedded album", "MPRE-album"), thumbnail = "") })
        }): MetadataNameRepository {
            return MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), preferences = settings,
                locale = { locales.value }, localeUpdates = locales,
                authRevision = { authUpdates.value }, authUpdates = authUpdates,
                observeMetadata = {}, publishNames = { selected, all -> names.value = selected; aliases.value = all },
                contextKey = { "${it.gl}:test" }, queue = queue,
                artist = { id, locale -> Result.success(ArtistItem(id, if (locale.hl == "ja") "人物の正式名" else "Artist header",
                    null, shuffleEndpoint = null, radioEndpoint = null)) },
                album = { id, locale -> Result.success(AlbumItem(id, "playlist", title = if (locale.hl == "ja") "アルバムの正式名" else "Album header",
                    artists = emptyList(), thumbnail = "")) },
                albumContext = { _, _ -> albumExpansions.incrementAndGet(); error("Unsaved album must not expand tracks") },
                main = { _, _ -> mainRequests.incrementAndGet(); error("No Art Track endpoint was returned") },
            )).also { it.start() }
        }

        suspend fun close() { job.cancelAndJoin(); database.close() }
    }

    companion object {
        private fun preferences(language: String): Preferences = preferencesOf(
            ContentCountryKey to "JP", ContentLanguageKey to language, PreferEnglishOriginalKey to false,
        )
    }
}
