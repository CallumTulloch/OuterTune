package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.MainSongReference
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.IOException
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room and repository collectors, with deterministic pauses at the classifier boundary. */
class MetadataPublicationLifecycleTest {
    @Test(timeout = 45_000)
    fun firstUnconfirmedOriginalUsesConfiguredNameUntilItsFirstCompletedAssessment(): Unit = runBlocking {
        withFixture { f ->
            f.seedDirect()
            val gate = f.pauseEvaluation()
            f.start()
            gate.awaitEntered()
            f.publicationBarrier()
            f.assertEveryFrame(SONG, JAPANESE)
            assertTrue(f.database.metadataOriginalPublicationSnapshot().isEmpty())

            gate.release.complete(Unit)
            f.awaitName(SONG, ENGLISH)
            f.awaitPersisted(SONG, ENGLISH)
            val displayed = f.frames.mapNotNull { it[SONG] }.distinctUntilChanged()
            assertEquals(listOf(JAPANESE, ENGLISH), displayed)
            f.assertRawSongUnchanged()
        }
    }

    @Test(timeout = 45_000)
    fun changedCandidateKeepsLastEnglishUntilUnknownCompletesAndCannotReviveAfterRestart(): Unit = runBlocking {
        withFixture { f ->
            f.seedDirect()
            f.start()
            f.awaitPersisted(SONG, ENGLISH)
            f.awaitName(SONG, ENGLISH)
            f.frames.clear()

            val gate = f.pauseEvaluation()
            f.replaceOriginal(SONG, "123")
            gate.awaitEntered()
            f.publicationBarrier()
            f.assertEveryFrame(SONG, ENGLISH)
            assertEquals(ENGLISH, f.persistedName(SONG))

            gate.release.complete(Unit)
            f.awaitName(SONG, JAPANESE)
            f.awaitPersisted(SONG, null)
            val committed = f.database.metadataOriginalPublicationSnapshot().single { it.targetId == SONG.id }
            assertTrue("A completed non-English decision must retain evidence", committed.evidenceJson.isNotBlank())
            f.gate = null
            f.restart()
            f.awaitName(SONG, JAPANESE)
            f.publicationBarrier()
            f.assertEveryFrame(SONG, JAPANESE)
            assertNull(f.persistedName(SONG))
            f.assertRawSongUnchanged()
        }
    }

    @Test(timeout = 45_000)
    fun unrelatedAlbumArrivalKeepsAssociatedEnglishAcrossPendingEvaluationAndColdRestart(): Unit = runBlocking {
        withFixture { f ->
            f.seedReference()
            f.start()
            f.awaitPersisted(REFERENCED, ENGLISH)
            f.awaitName(REFERENCED, ENGLISH)
            f.frames.clear()

            val gate = f.pauseEvaluation()
            f.replaceOriginal(OTHER, "夜に駆ける", "MPREother")
            gate.awaitEntered()
            f.publicationBarrier()
            f.assertEveryFrame(REFERENCED, ENGLISH)
            assertFalse("A name reference must not become another classifier input",
                f.assessedIds.any { REFERENCED.id in it })

            val restartedGate = Gate()
            f.gate = restartedGate
            f.restart()
            restartedGate.awaitEntered()
            f.awaitName(REFERENCED, ENGLISH)
            f.publicationBarrier()
            f.assertEveryFrame(REFERENCED, ENGLISH)
            restartedGate.release.complete(Unit)
            restartedGate.awaitFinished()
            f.awaitPersisted(REFERENCED, ENGLISH)
            f.publicationBarrier()
            f.assertEveryFrame(REFERENCED, ENGLISH)
            assertFalse(f.assessedIds.any { REFERENCED.id in it })
        }
    }

    @Test(timeout = 45_000)
    fun failedReevaluationKeepsCommittedNameAndRestartCanFinishTheRealChangedName(): Unit = runBlocking {
        // This case has no album context. Verify the replacement is individually English before
        // waiting for its publication; an ambiguous title would correctly complete as UNKNOWN.
        val changedAssessment = OriginalAlbumLanguageResolver().assess(
            listOf(ArtTrackOriginalName(SONG, CHANGED_ENGLISH, SONG.id)), 1L,
        ).single()
        assertEquals("The changed-name fixture must be English without album context",
            OriginalNameLanguage.ENGLISH, changedAssessment.language)
        withFixture { f ->
            f.seedDirect()
            f.start()
            f.awaitPersisted(SONG, ENGLISH)
            f.awaitName(SONG, ENGLISH)
            f.frames.clear()

            val failedGate = f.pauseEvaluation(fail = true)
            f.replaceOriginal(SONG, CHANGED_ENGLISH)
            failedGate.awaitEntered()
            f.publicationBarrier()
            f.assertEveryFrame(SONG, ENGLISH)
            failedGate.release.complete(Unit)
            failedGate.awaitFinished()
            f.publicationBarrier()
            f.assertEveryFrame(SONG, ENGLISH)
            assertEquals(ENGLISH, f.persistedName(SONG))

            val retryGate = Gate()
            f.gate = retryGate
            f.restart()
            retryGate.awaitEntered()
            f.awaitName(SONG, ENGLISH)
            f.publicationBarrier()
            f.assertEveryFrame(SONG, ENGLISH)
            retryGate.release.complete(Unit)
            f.awaitName(SONG, CHANGED_ENGLISH)
            f.awaitPersisted(SONG, CHANGED_ENGLISH)
            f.assertRawSongUnchanged()
        }
    }

    @Test(timeout = 45_000)
    fun preferenceAndConfiguredLanguageChangeImmediatelyWhileNewEvidenceIsStillPending(): Unit = runBlocking {
        withFixture { f ->
            f.seedDirect()
            f.start()
            f.awaitPersisted(SONG, ENGLISH)
            f.awaitName(SONG, ENGLISH)

            val gate = f.pauseEvaluation()
            f.replaceOriginal(SONG, CHANGED_ENGLISH)
            gate.awaitEntered()
            f.publicationBarrier()
            assertEquals(ENGLISH, f.published.value[SONG])

            f.preferEnglish(false)
            f.awaitName(SONG, JAPANESE)
            f.preferEnglish(true)
            f.awaitName(SONG, ENGLISH)
            f.locales.value = YouTubeLocale("JP", "fr")
            f.publicationBarrier()
            assertEquals(ENGLISH, f.published.value[SONG])
            f.preferEnglish(false)
            f.awaitName(SONG, FRENCH)
            f.locales.value = YouTubeLocale("JP", "en")
            f.awaitName(SONG, CHANGED_ENGLISH)
            f.locales.value = YouTubeLocale("JP", "ja")
            f.awaitName(SONG, JAPANESE)
            f.preferEnglish(true)
            f.awaitName(SONG, ENGLISH)
            assertFalse("Settings changes cannot depend on classifier completion", gate.release.isCompleted)
            f.assertRawSongUnchanged()
        }
    }

    @Test(timeout = 45_000)
    fun explicitReferenceWithdrawalIsCommittedAndDoesNotRestoreAnOlderEdgeOnRestart(): Unit = runBlocking {
        withFixture { f ->
            val reference = f.seedReference()
            f.start()
            f.awaitPersisted(REFERENCED, ENGLISH)
            f.awaitName(REFERENCED, ENGLISH)

            f.database.recordMetadataNames(listOf(reference.copy(
                observedAt = f.clock.incrementAndGet(), originEvidenceJson = "{}",
            )))
            f.awaitName(REFERENCED, REFERENCED_JAPANESE)
            f.awaitPersisted(REFERENCED, null)
            f.restart()
            f.awaitName(REFERENCED, REFERENCED_JAPANESE)
            f.publicationBarrier()
            f.assertEveryFrame(REFERENCED, REFERENCED_JAPANESE)
            assertNull(f.persistedName(REFERENCED))
            assertTrue(f.database.metadataNameSnapshot().any {
                it.targetId == REFERENCED.id && it.language == "en" && it.name == ENGLISH
            })
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private class Gate(val fail: Boolean = false) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        suspend fun awaitEntered() {
            assertTrue("Classifier did not enter the paused evaluation (fail=$fail)",
                withTimeoutOrNull(10_000) { entered.await(); true } == true)
        }
        suspend fun awaitFinished() {
            assertTrue("Classifier did not finish after release (fail=$fail, released=${release.isCompleted})",
                withTimeoutOrNull(10_000) { finished.await(); true } == true)
        }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val databaseName = "metadata-publication-${UUID.randomUUID()}.db"
        private fun openDatabase() = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build())
        var database = openDatabase()
            private set
        private var job = SupervisorJob()
        private val resolver = OriginalAlbumLanguageResolver()
        val clock = AtomicLong(1_790_000_000_000L)
        val locales = MutableStateFlow(YouTubeLocale("JP", "ja"))
        private val preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true))
        val published = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val frames = CopyOnWriteArrayList<Map<OriginalNameTarget, String>>()
        val assessedIds = CopyOnWriteArrayList<Set<String>>()
        private val barriers = AtomicInteger()
        @Volatile var gate: Gate? = null

        fun seedDirect() {
            database.insert(SongEntity(SONG.id, RAW, localPath = null))
            database.recordMetadataNames(names(SONG, ENGLISH, JAPANESE) + original(SONG, ENGLISH))
        }

        fun seedReference(): MetadataNameEntity {
            seedDirect()
            val source = ArtTrackOriginalName(SONG, ENGLISH, SONG.id, ALBUM_ID)
            val row = providerSongReference(MainSongReference(SONG.id, REFERENCED.id), source,
                SongItem(REFERENCED.id, ENGLISH, emptyList(), Album("Album", ALBUM_ID), thumbnail = ""))!!
                .toMetadataName(clock.incrementAndGet())
            database.recordMetadataNames(names(REFERENCED, ENGLISH, REFERENCED_JAPANESE) +
                original(SONG, ENGLISH, ALBUM_ID) + row)
            return row
        }

        fun replaceOriginal(target: OriginalNameTarget, title: String, albumId: String? = null) {
            clock.incrementAndGet()
            database.recordMetadataNames(listOf(
                MetadataNameEntity(target.kind.name, target.id, "en", title, "detail", 100, clock.get()),
                original(target, title, albumId),
            ))
        }

        fun pauseEvaluation(fail: Boolean = false) = Gate(fail).also { gate = it }

        fun start() {
            MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                locale = { locales.value }, localeUpdates = locales,
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L),
                preferences = preferences, observeMetadata = {}, now = clock::get,
                contextKey = { "JP:publication-lifecycle" },
                publishNames = { selected, _ ->
                    frames += selected.toMap()
                    published.value = selected
                },
                queue = { _, _ -> Result.failure(IOException("Fixture is offline")) },
                artist = { _, _ -> Result.failure(IOException("Fixture is offline")) },
                album = { _, _ -> Result.failure(IOException("Fixture is offline")) },
                albumContext = { _, _ -> Result.failure(IOException("Fixture is offline")) },
                main = { _, _ -> Result.failure(IOException("Fixture is offline")) },
                mainSongReference = { _, _ -> Result.failure(IOException("Fixture is offline")) },
                albumSongSources = { _, _ -> Result.failure(IOException("Fixture is offline")) },
                assessOriginals = { originals, at ->
                    val currentGate = gate
                    assessedIds += originals.map { it.target.id }.toSet()
                    currentGate?.entered?.complete(Unit)
                    try {
                        currentGate?.release?.await()
                        if (currentGate?.fail == true) throw IOException("Classifier temporarily unavailable")
                        resolver.assess(originals, at)
                    } finally {
                        currentGate?.finished?.complete(Unit)
                    }
                },
            )).start()
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

        fun preferEnglish(enabled: Boolean) {
            preferences.value = preferencesOf(PreferEnglishOriginalKey to enabled)
        }

        suspend fun awaitName(target: OriginalNameTarget, value: String) {
            val reached = withTimeoutOrNull(10_000) { published.first { it[target] == value }; true }
            if (reached != true) throw AssertionError("Display did not become '$value': ${diagnostics(target)}")
        }

        suspend fun awaitPersisted(target: OriginalNameTarget, value: String?) {
            val reached = withTimeoutOrNull(10_000) {
                while (true) {
                    val row = database.metadataOriginalPublicationSnapshot().singleOrNull {
                        it.kind == target.kind.name && it.targetId == target.id
                    }
                    if (row != null && row.englishName == value) break
                    delay(10)
                }
                true
            }
            if (reached != true) throw AssertionError("Stored verdict did not become '$value': ${diagnostics(target)}")
        }

        private fun diagnostics(target: OriginalNameTarget): String =
            "target=$target, displayed=${published.value[target]}, " +
                "stored=${database.metadataOriginalPublicationSnapshot().filter { it.targetId == target.id }}, " +
                "assessments=${originalAssessmentsByTarget(database.metadataNameSnapshot())[target]}, " +
                "frames=${frames.map { it[target] }}"

        fun persistedName(target: OriginalNameTarget): String? = database.metadataOriginalPublicationSnapshot()
            .single { it.kind == target.kind.name && it.targetId == target.id }.englishName

        /** The displayed sentinel proves the independent Room display collector crossed this write. */
        suspend fun publicationBarrier() {
            val ordinal = barriers.incrementAndGet()
            val target = OriginalNameTarget(OriginalNameKind.SONG, "publication-barrier-$ordinal")
            val text = "Publication barrier $ordinal"
            database.recordMetadataNames(listOf(MetadataNameEntity(
                target.kind.name, target.id, "ja", text, "manual", 100, clock.incrementAndGet(),
            )))
            awaitName(target, text)
        }

        fun assertEveryFrame(target: OriginalNameTarget, expected: String) {
            val names = frames.map { it[target] }
            assertTrue("Expected only $expected, observed $names", names.isNotEmpty() && names.all { it == expected })
        }

        fun assertRawSongUnchanged() {
            val song = database.songForArtistCredit(SONG.id)!!
            assertEquals(RAW, song.title)
            assertEquals(SONG.id, song.id)
            assertNull(song.localPath)
        }

        suspend fun close() {
            gate?.release?.complete(Unit)
            job.cancelAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }

        private fun names(target: OriginalNameTarget, english: String, japanese: String) = listOf(
            MetadataNameEntity(target.kind.name, target.id, "en", english, "detail", 100, clock.get()),
            MetadataNameEntity(target.kind.name, target.id, "ja", japanese, "detail", 100, clock.get()),
            MetadataNameEntity(target.kind.name, target.id, "fr", FRENCH, "detail", 100, clock.get()),
        )

        private fun original(target: OriginalNameTarget, title: String, albumId: String? = null): MetadataNameEntity {
            val candidate = ArtTrackOriginalName(target, title, target.id, albumId)
            return MetadataNameEntity(target.kind.name, target.id, "und", title,
                ORIGINAL_NAME_SOURCE_PREFIX + target.id, observedAt = clock.get(),
                originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate))
        }
    }

    companion object {
        private val SONG = OriginalNameTarget(OriginalNameKind.SONG, "abcdefghijk")
        private val REFERENCED = OriginalNameTarget(OriginalNameKind.SONG, "lmnopqrstuv")
        private val OTHER = OriginalNameTarget(OriginalNameKind.SONG, "0123456789a")
        private const val ALBUM_ID = "MPREpublication"
        private const val ENGLISH = "Something In The Way"
        private const val CHANGED_ENGLISH = "Lithium"
        private const val JAPANESE = "サムシング・イン・ザ・ウェイ"
        private const val REFERENCED_JAPANESE = "参照先の日本語名"
        private const val FRENCH = "Quelque chose sur le chemin"
        private const val RAW = "Original raw provider name"
        private fun <T> List<T>.distinctUntilChanged(): List<T> = filterIndexed { index, item -> index == 0 || item != this[index - 1] }
    }
}
