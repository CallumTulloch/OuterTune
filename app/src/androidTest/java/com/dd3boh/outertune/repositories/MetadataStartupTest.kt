package com.dd3boh.outertune.repositories

import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.MetadataOriginalPublicationEntity
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.IOException
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cold Room startup must publish saved names before network or model work can delay the UI. */
class MetadataStartupTest {
    @Test(timeout = 90_000)
    fun largeSavedCachePublishesBeforeAnyNetworkWorkWithoutLoadingProofPayloads(): Unit = runBlocking {
        withFixture { f ->
            val count = 2_000
            val evidence = "{\"irrelevantDisplayEvidence\":\"" + "x".repeat(4_096) + "\"}"
            val artist = OriginalNameTarget(OriginalNameKind.ARTIST, "UCstartup")
            f.database.awaitTransaction {
                val names = (0 until count).flatMap { index ->
                    val id = "s${index.toString().padStart(10, '0')}"
                    insert(SongEntity(id, "保存時の曲名 $index", inLibrary = ADDED, localPath = null))
                    listOf(
                        MetadataNameEntity("SONG", id, "ja", "設定名 $index", "detail", observedAt = NOW, originEvidenceJson = evidence),
                        MetadataNameEntity("SONG", id, "en", "English Title $index", "detail", observedAt = NOW, originEvidenceJson = evidence),
                    )
                }
                insert(ArtistEntity(artist.id, "保存時のアーティスト名", bookmarkedAt = ADDED))
                recordMetadataNames(names + listOf(
                    MetadataNameEntity("ARTIST", artist.id, "ja", "設定アーティスト名", "detail", observedAt = NOW, originEvidenceJson = evidence),
                    MetadataNameEntity("ARTIST", artist.id, "en", "English Artist", "detail", observedAt = NOW, originEvidenceJson = evidence),
                ))
                recordMetadataOriginalPublications((0 until count).map { index ->
                    MetadataOriginalPublicationEntity("SONG", "s${index.toString().padStart(10, '0')}",
                        "English Title $index", evidence, NOW)
                } + MetadataOriginalPublicationEntity("ARTIST", artist.id, "English Artist", evidence, NOW))
            }
            f.reopen()
            // Exercise the production SQL projection directly, including the joined saved name.
            val displayRows = withTimeout(WAIT_MS) { f.database.metadataDisplayNames().first() }
            assertEquals((count + 1) * 2, displayRows.size)
            assertTrue(displayRows.all { it.name.originEvidenceJson == null && !it.englishName.isNullOrBlank() })

            f.reopen()
            f.start()
            val first = withTimeout(WAIT_MS) { f.firstFrame.await() }
            assertEquals(count + 1, first.names.size)
            (0 until count).forEach { index ->
                assertEquals("English Title $index", first.names[OriginalNameTarget(OriginalNameKind.SONG,
                    "s${index.toString().padStart(10, '0')}")])
            }
            assertEquals("English Artist", first.names[artist])
            withTimeout(WAIT_MS) { f.repository.initialized.first { it } }
            val firstNetworkAt = withTimeout(WAIT_MS) { f.networkEntered.await() }
            assertEquals("Background requests must start after the initial saved display", 0, f.earlyNetworkCalls.get())
            assertTrue(first.publishedAt <= firstNetworkAt)
            assertFalse("No network response was needed to initialize", f.networkRelease.isCompleted)
            val saved = withTimeout(WAIT_MS) { f.database.savedSongsByCreateDateAsc().first() }
            assertEquals(count, saved.size)
            Log.i(TAG, "largeCache songs=$count metadataRows=${displayRows.size} firstDisplayMs=${first.publishedAt - f.startedAt}")
        }
    }

    @Test(timeout = 45_000)
    fun retainedAssessmentsSurviveReopenWithoutRunningClassifierAgain(): Unit = runBlocking {
        withFixture { f ->
            val before = f.seedCompletedOriginal()
            f.reopen()
            f.assessor = { _, _ ->
                throw IllegalStateException("A valid persisted input fingerprint must reuse its completed assessment")
            }
            f.start()
            withTimeout(WAIT_MS) { f.repository.initialized.first { it } }
            assertEquals(ENGLISH, f.published.value[SONG])

            // This new target changes the publication input generation without changing any
            // classifier input. Its completed row proves the evaluator actually crossed it.
            val barrier = OriginalNameTarget(OriginalNameKind.SONG, "startup-publication-barrier")
            f.database.recordMetadataNames(listOf(MetadataNameEntity("SONG", barrier.id,
                "en", "New observed alias", "detail", observedAt = NOW + 1)))
            withTimeout(WAIT_MS) {
                f.database.allMetadataOriginalPublications().first { rows -> rows.any { it.targetId == barrier.id } }
            }
            assertEquals(0, f.assessmentCalls.get())
            assertEquals(before, f.database.metadataOriginalPublicationSnapshot().single { it.targetId == SONG.id })
            assertEquals(ENGLISH, f.published.value[SONG])
        }
    }

    @Test(timeout = 45_000)
    fun changedInputsPausedAtClassifierCannotBlockSavedSongsOrDisplayInitialization(): Unit = runBlocking {
        withFixture { f ->
            val before = f.seedCompletedOriginal()
            val changed = ArtTrackOriginalName(SONG, "A Changed Original Title", SONG.id)
            f.database.recordMetadataNames(listOf(
                MetadataNameEntity("SONG", SONG.id, "en", changed.name, "detail", observedAt = NOW + 1),
                raw(changed, NOW + 1),
            ))
            f.reopen()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.assessor = { _, _ ->
                entered.complete(Unit)
                release.await()
                emptyList()
            }
            f.start()
            withTimeout(WAIT_MS) { entered.await() }
            withTimeout(WAIT_MS) { f.repository.initialized.first { it } }
            assertEquals(ENGLISH, f.published.value[SONG])
            val startedReadAt = SystemClock.elapsedRealtime()
            val saved = withTimeout(WAIT_MS) { f.database.savedSongsByCreateDateAsc().first() }
            assertEquals(listOf(SONG.id), saved.map { it.song.id })
            assertEquals("保存された元の曲名", saved.single().song.title)

            // A fresh UI-only write must also be observable while classification is suspended.
            val barrier = OriginalNameTarget(OriginalNameKind.SONG, "startup-display-barrier")
            f.database.recordMetadataNames(listOf(MetadataNameEntity("SONG", barrier.id,
                "ja", "表示確認", "manual", observedAt = NOW + 2)))
            withTimeout(WAIT_MS) { f.published.first { it[barrier] == "表示確認" } }
            assertFalse("Library reads and display must not depend on classifier completion", release.isCompleted)
            assertEquals(before, f.database.metadataOriginalPublicationSnapshot().single { it.targetId == SONG.id })
            assertEquals(ENGLISH, f.published.value[SONG])
            Log.i(TAG, "pausedClassifier savedSongsReadAndDisplayMs=${SystemClock.elapsedRealtime() - startedReadAt}")
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private data class Frame(val publishedAt: Long, val names: Map<OriginalNameTarget, String>)

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val databaseName = "metadata-startup-${UUID.randomUUID()}.db"
        private fun openDatabase() = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build())
        var database = openDatabase()
            private set
        private val job = SupervisorJob()
        val firstFrame = CompletableDeferred<Frame>()
        val published = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val networkEntered = CompletableDeferred<Long>()
        val networkRelease = CompletableDeferred<Unit>()
        val earlyNetworkCalls = AtomicInteger()
        val assessmentCalls = AtomicInteger()
        var assessor: suspend (List<ArtTrackOriginalName>, Long) -> List<OriginalNameAssessment> = { _, _ -> emptyList() }
        lateinit var repository: MetadataNameRepository
            private set
        var startedAt = 0L
            private set

        suspend fun seedCompletedOriginal(): MetadataOriginalPublicationEntity {
            val candidate = ArtTrackOriginalName(SONG, ENGLISH, SONG.id)
            val original = raw(candidate)
            val assessment = OriginalNameAssessment(SONG, ENGLISH, SONG.id,
                "https://www.youtube.com/watch?v=${SONG.id}", OriginalNameLanguage.ENGLISH, 0.99f,
                OriginalAlbumLanguageResolver.METHOD_VERSION + "/individual-english", "startup-fixture", NOW)
            val names = listOf(
                MetadataNameEntity("SONG", SONG.id, "ja", "設定時の曲名", "detail", observedAt = NOW),
                MetadataNameEntity("SONG", SONG.id, "en", ENGLISH, "detail", observedAt = NOW),
                original.copy(originEvidenceJson = encodeOriginalAssessment(candidate, assessment, listOf(original))),
            )
            val publications = requireNotNull(prepareOriginalPublications(names, emptyList(), NOW))
            database.awaitTransaction {
                insert(SongEntity(SONG.id, "保存された元の曲名", inLibrary = ADDED, localPath = null))
                recordMetadataNames(names)
                recordMetadataOriginalPublications(publications)
            }
            return publications.single()
        }

        fun reopen() {
            check(!::repository.isInitialized)
            database.close()
            database = openDatabase()
        }

        fun start() {
            val locale = YouTubeLocale("JP", "ja")
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L),
                preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true)),
                observeMetadata = {}, now = { NOW + 10 }, contextKey = { "JP:startup-test" },
                publishNames = { names, _ ->
                    firstFrame.complete(Frame(SystemClock.elapsedRealtime(), names.toMap()))
                    published.value = names
                },
                queue = { _, _ -> pausedNetwork() },
                artist = { _, _ -> pausedNetwork() },
                album = { _, _ -> pausedNetwork() },
                albumContext = { _, _ -> pausedNetwork() },
                main = { _, _ -> pausedNetwork() },
                mainSongReference = { _, _ -> pausedNetwork() },
                albumSongSources = { _, _ -> pausedNetwork() },
                assessOriginals = { originals, at ->
                    assessmentCalls.incrementAndGet()
                    assessor(originals, at)
                },
            ))
            startedAt = SystemClock.elapsedRealtime()
            repository.start()
        }

        private suspend fun <T> pausedNetwork(): Result<T> {
            if (!repository.initialized.value) earlyNetworkCalls.incrementAndGet()
            networkEntered.complete(SystemClock.elapsedRealtime())
            networkRelease.await()
            return Result.failure(IOException("Isolated startup test has no network"))
        }

        suspend fun close() {
            job.cancelAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    companion object {
        private const val TAG = "MetadataStartupTest"
        private const val WAIT_MS = 20_000L
        private const val NOW = 1_790_000_000_000L
        private val ADDED = LocalDateTime.of(2026, 9, 27, 12, 0)
        private val SONG = OriginalNameTarget(OriginalNameKind.SONG, "abcdefghijk")
        private const val ENGLISH = "Something In The Way"
        private fun raw(candidate: ArtTrackOriginalName, at: Long = NOW) = MetadataNameEntity(
            candidate.target.kind.name, candidate.target.id, "und", candidate.name,
            ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, observedAt = at,
            originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate),
        )
    }
}
