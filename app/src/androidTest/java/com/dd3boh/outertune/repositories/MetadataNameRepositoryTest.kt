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
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Real Room and repository workers, with isolated settings and deterministic provider responses. */
class MetadataNameRepositoryTest {
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

    private class Fixture(language: String = "ja") {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(internal)
        private val job = SupervisorJob()
        val settings = MutableStateFlow(preferences(language))
        val locales = MutableStateFlow(YouTubeLocale("JP", language))
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
        }) {
            MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), preferences = settings,
                locale = { locales.value }, localeUpdates = locales,
                observeMetadata = {}, publishNames = { selected, all -> names.value = selected; aliases.value = all },
                contextKey = { "${it.gl}:test" }, queue = queue,
                artist = { id, locale -> Result.success(ArtistItem(id, if (locale.hl == "ja") "人物の正式名" else "Artist header",
                    null, shuffleEndpoint = null, radioEndpoint = null)) },
                album = { id, locale -> Result.success(AlbumItem(id, "playlist", title = if (locale.hl == "ja") "アルバムの正式名" else "Album header",
                    artists = emptyList(), thumbnail = "")) },
                albumContext = { _, _ -> albumExpansions.incrementAndGet(); error("Unsaved album must not expand tracks") },
                main = { _, _ -> mainRequests.incrementAndGet(); error("No Art Track endpoint was returned") },
            )).start()
        }

        suspend fun close() { job.cancelAndJoin(); database.close() }
    }

    companion object {
        private fun preferences(language: String): Preferences = preferencesOf(
            ContentCountryKey to "JP", ContentLanguageKey to language, PreferEnglishOriginalKey to false,
        )
    }
}
