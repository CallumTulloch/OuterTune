package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.utils.MetadataNames
import com.dd3boh.outertune.utils.displayAlbumTitle
import com.dd3boh.outertune.utils.displayTitle
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.YTItem
import java.io.IOException
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** User edits and local tags retain their authority throughout actual repository acquisition. */
class MetadataManualProtectionTest {
    @Test fun manuallyEditedOnlineNameSurvivesRefetchCompletedOriginalSettingsAndDatabaseReopen(): Unit = runBlocking {
        withFixture(local = false) { f ->
            f.start()
            f.awaitVerdict(ORIGINAL)
            f.awaitPublished(ORIGINAL)
            f.editTitle(MANUAL)
            f.awaitPublished(MANUAL)
            f.frames.clear()
            f.assertViews(MANUAL)

            val gate = f.pauseEvaluation()
            f.refreshOriginal(CHANGED)
            withTimeout(15_000) { gate.entered.await() }
            f.publicationBarrier()
            f.assertViews(MANUAL)
            assertTrue(f.mainCalls.get() >= 2)
            gate.release.complete(Unit)
            f.awaitVerdict(CHANGED)
            f.publicationBarrier()
            f.assertViews(MANUAL)
            assertTrue("A completed fresh original must not erase an edit",
                f.frames.all { it[SONG] == MANUAL })

            f.gate = null
            for (language in listOf("ja", "fr", "en")) for (enabled in listOf(false, true)) {
                f.locales.value = YouTubeLocale("JP", language)
                f.preferEnglish(enabled)
                f.publicationBarrier()
                f.assertViews(MANUAL)
            }
            f.restart()
            f.awaitPublished(MANUAL)
            f.publicationBarrier()
            f.assertViews(MANUAL)
            assertTrue(f.frames.all { it[SONG] == MANUAL })
            val saved = f.database.song(SONG.id).first()!!.song
            assertEquals(MANUAL, saved.title)
            assertFalse(saved.isLocal)
            assertNull(saved.localPath)
            assertTrue(f.database.metadataNames("SONG", SONG.id).any { it.source == "manual" && it.name == MANUAL })
            assertTrue(f.database.metadataNames("SONG", SONG.id).any { it.language == "en" && it.name == CHANGED })
        }
    }

    @Test fun editedLocalTagsIgnoreAnExistingSameIdNameCacheAcrossRefetchSettingsAndDatabaseReopen(): Unit = runBlocking {
        withFixture(local = true) { f ->
            f.start()
            f.awaitVerdict(ORIGINAL)
            f.awaitPublished(ORIGINAL)
            // Exercise the adverse boundary: the online cache really contains a conflicting
            // same-ID display value, while both library and player must use the local tag.
            f.assertViews(LOCAL_TAG)
            f.editTitle(LOCAL_EDIT)
            f.assertViews(LOCAL_EDIT)
            val protectedRow = f.database.song(SONG.id).first()!!.song
            assertFalse(f.database.metadataNames("SONG", SONG.id).any { it.source == "manual" })

            val gate = f.pauseEvaluation()
            f.refreshOriginal(CHANGED)
            withTimeout(15_000) { gate.entered.await() }
            f.publicationBarrier()
            f.assertViews(LOCAL_EDIT)
            gate.release.complete(Unit)
            f.awaitVerdict(CHANGED)
            f.awaitPublished(CHANGED)
            f.assertViews(LOCAL_EDIT)
            f.gate = null

            for (language in listOf("fr", "en", "ja")) for (enabled in listOf(false, true)) {
                f.locales.value = YouTubeLocale("JP", language)
                f.preferEnglish(enabled)
                f.publicationBarrier()
                f.assertViews(LOCAL_EDIT)
            }
            f.restart()
            f.awaitPublished(CHANGED)
            f.publicationBarrier()
            f.assertViews(LOCAL_EDIT)
            assertEquals(protectedRow, f.database.song(SONG.id).first()!!.song)
            assertTrue(f.mainCalls.get() >= 2)
        }
    }

    private suspend fun withFixture(local: Boolean, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(local)
        try { block(fixture) } finally { fixture.close() }
    }

    private class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class Fixture(private val local: Boolean) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val databaseName = "metadata-manual-protection-${UUID.randomUUID()}.db"
        private fun openDatabase() = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build())
        var database = openDatabase()
            private set
        private var job = SupervisorJob()
        private lateinit var repository: MetadataNameRepository
        private lateinit var observer: (List<YTItem>, YouTubeLocale, String) -> Unit
        val clock = AtomicLong(1_820_000_000_000L)
        val locales = MutableStateFlow(YouTubeLocale("JP", "ja"))
        private val preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true))
        val published = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val frames = CopyOnWriteArrayList<Map<OriginalNameTarget, String>>()
        val mainCalls = AtomicInteger()
        private val barriers = AtomicInteger()
        @Volatile private var providerTitle = ORIGINAL
        @Volatile var gate: Gate? = null

        init {
            database.insert(SongEntity(SONG.id, if (local) LOCAL_TAG else RAW,
                duration = 245, isLocal = local, localPath = if (local) "/fixture/local-song.flac" else null,
                inLibrary = LocalDateTime.of(2026, 9, 27, 0, 0),
                albumName = if (local) LOCAL_ALBUM else "Provider Album"))
            database.recordMetadataNames(listOf(
                MetadataNameEntity("SONG", SONG.id, "en", ORIGINAL, "detail", 100, clock.get()),
                MetadataNameEntity("SONG", SONG.id, "ja", "配信元の日本語名", "detail", 100, clock.get()),
                MetadataNameEntity("SONG", SONG.id, "fr", "Titre du fournisseur", "detail", 100, clock.get()),
            ))
        }

        /** Match SongMenu's transaction: only online edits create a metadata manual override. */
        suspend fun editTitle(title: String) {
            val saved = database.song(SONG.id).first()!!.song
            database.awaitTransaction {
                update(saved.copy(title = title))
                if (!saved.isLocal) recordMetadataNames(listOf(
                    MetadataNameEntity("SONG", SONG.id, "und", title, "manual", 1000, clock.incrementAndGet()),
                ))
            }
        }

        fun start() {
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                locale = { locales.value }, localeUpdates = locales,
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L),
                preferences = preferences, now = clock::get,
                contextKey = { "JP:manual-protection" }, observeMetadata = { observer = it },
                publishNames = { selected, _ -> frames += selected.toMap(); published.value = selected },
                queue = { ids, locale -> Result.success(ids.filter { it == SONG.id }.map {
                    song(if (locale.hl == "en") providerTitle else if (locale.hl == "fr") "Titre du fournisseur" else "配信元の日本語名")
                }) },
                main = { id, _ ->
                    mainCalls.incrementAndGet()
                    Result.success(ArtTrackOriginalMetadata(id, providerTitle, "Fixture - Topic", null,
                        "Provided to YouTube by Fixture Records\n\n$providerTitle · Fixture Performer\n\nProvider Album\n\n" +
                            "℗ Fixture Records\n\nAuto-generated by YouTube."))
                },
                album = { _, _ -> Result.failure(IOException("No fixture album lookup")) },
                albumContext = { _, _ -> Result.failure(IOException("No fixture album lookup")) },
                artist = { _, _ -> Result.failure(IOException("No fixture artist lookup")) },
                mainSongReference = { _, _ -> Result.success(null) },
                albumSongSources = { _, _ -> Result.success(emptyList()) },
                assessOriginals = { candidates, at ->
                    val currentGate = gate
                    currentGate?.entered?.complete(Unit)
                    currentGate?.release?.await()
                    candidates.map { candidate -> OriginalNameAssessment(candidate.target, candidate.name,
                        candidate.sourceVideoId, "https://www.youtube.com/watch?v=${candidate.sourceVideoId}",
                        OriginalNameLanguage.ENGLISH, 0.99f,
                        OriginalAlbumLanguageResolver.METHOD_VERSION + "/manual-protection-fixture",
                        "fixture-${candidate.name}", at) }
                },
            )).also { it.start() }
            if (local) {
                // Explicitly select the remote identity in this adverse same-ID fixture.
                // Merely seeing a search card must no longer start its detail acquisition.
                repository.setPlayingSong(SONG.id)
                observeRemoteCollision()
            }
        }

        // A local row never creates a remote refresh interest. The fixture's explicit remote
        // selection above makes this same-ID online cache real without changing the local row.
        private fun observeRemoteCollision() {
            observer(listOf(song(providerTitle)), locales.value.copy(hl = "en"), "search")
        }

        fun pauseEvaluation() = Gate().also { gate = it }
        suspend fun refreshOriginal(title: String) {
            providerTitle = title
            clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            repository.refreshTargets()
            if (local) observeRemoteCollision()
        }
        fun preferEnglish(enabled: Boolean) { preferences.value = preferencesOf(PreferEnglishOriginalKey to enabled) }
        suspend fun awaitPublished(name: String) = withTimeout(15_000) { published.first { it[SONG] == name } }
        suspend fun awaitVerdict(name: String) = withTimeout(15_000) {
            while (database.metadataOriginalPublicationSnapshot().none { it.targetId == SONG.id && it.englishName == name }) delay(10)
        }
        suspend fun publicationBarrier() {
            val ordinal = barriers.incrementAndGet()
            val id = "manual-protection-barrier-$ordinal"
            database.recordMetadataNames(listOf(MetadataNameEntity("SONG", id, "ja", "Barrier $ordinal",
                "manual", 100, clock.incrementAndGet())))
            withTimeout(15_000) { published.first { it[OriginalNameTarget(OriginalNameKind.SONG, id)] == "Barrier $ordinal" } }
        }

        suspend fun assertViews(expected: String) {
            val song = requireNotNull(database.song(SONG.id).first())
            val media = song.toMediaMetadata()
            withContext(Dispatchers.Main) {
                try {
                    MetadataNames.publish(published.value)
                    assertEquals(expected, song.song.displayTitle)
                    assertEquals(expected, media.displayTitle)
                    if (local) {
                        assertEquals(LOCAL_ALBUM, song.song.displayAlbumTitle)
                        assertEquals("/fixture/local-song.flac", media.localPath)
                        assertTrue(media.isLocal)
                    }
                    assertEquals(SONG.id, media.id)
                    assertEquals(245, media.duration)
                } finally {
                    MetadataNames.publish(emptyMap())
                }
            }
        }

        suspend fun restart() {
            job.cancelAndJoin()
            database.close()
            database = openDatabase()
            job = SupervisorJob()
            frames.clear()
            published.value = emptyMap()
            start()
        }
        suspend fun close() {
            gate?.release?.complete(Unit)
            job.cancelAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }

        private fun song(title: String) = SongItem(SONG.id, title, emptyList(), Album("Provider Album", "MPREmanualFixture"),
            duration = 245, thumbnail = "", endpoint = WatchEndpoint(videoId = SONG.id,
                watchEndpointMusicSupportedConfigs = WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_ATV"))))
    }

    companion object {
        private val SONG = OriginalNameTarget(OriginalNameKind.SONG, "editname001")
        private const val ORIGINAL = "Something In The Way"
        private const val CHANGED = "A New English Original"
        private const val RAW = "Raw provider title"
        private const val MANUAL = "自分で付けた曲名"
        private const val LOCAL_TAG = "端末タグの曲名"
        private const val LOCAL_EDIT = "編集後の端末タグ名"
        private const val LOCAL_ALBUM = "端末タグのアルバム名"
    }
}
