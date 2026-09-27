package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.OriginalTextLanguageDetector
import com.dd3boh.outertune.models.metadata.OriginalTextLanguageScore
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Observe actual persisted publication progress, rather than the private batching counter. */
class OriginalAssessmentBatchingDeviceTest {
    @Test(timeout = 45_000)
    fun manyOrphanSourcesPublishInBoundedGroupsWithoutReevaluatingCompletedCandidates() = runBlocking {
        val fixture = Fixture()
        try {
            val sources = (0 until 17).map(::backgroundSong)
            fixture.database.recordMetadataNames(names(sources))
            val progressBeforeCalls = CopyOnWriteArrayList<Int>()
            val calls = CopyOnWriteArrayList<String>()
            fixture.start { candidates, at ->
                calls += candidates.single().sourceVideoId
                progressBeforeCalls += fixture.database.metadataOriginalPublicationSnapshot().count { it.englishName != null }
                fixture.resolver.assess(candidates, at)
            }
            withTimeout(15_000) { fixture.published.first { selected -> sources.all { selected[it.target] == it.name } } }

            assertEquals(sources.map { it.sourceVideoId }, calls.toList())
            assertEquals("The first several independent sources share one publication preparation",
                List(8) { 0 }, progressBeforeCalls.take(8))
            assertEquals("Completed background work is visible before starting a ninth group", 8, progressBeforeCalls[8])
            assertTrue("A legacy cache must not produce one full publication pass per orphan source",
                progressBeforeCalls.distinct().size <= 3)
        } finally { fixture.close() }
    }

    @Test(timeout = 45_000)
    fun aNewForegroundAlbumPassesTheRemainingBackgroundBatchEvenWhenItsFirstGroupFailed() = runBlocking {
        verifyForegroundArrival(alreadyForeground = false)
    }

    @Test(timeout = 45_000)
    fun anAlreadyForegroundAlbumsLateOriginalsInterruptTheRemainingBackgroundBatch() = runBlocking {
        verifyForegroundArrival(alreadyForeground = true)
    }

    private suspend fun verifyForegroundArrival(alreadyForeground: Boolean) {
        val fixture = Fixture()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val followingBackgroundEntered = CompletableDeferred<Unit>()
        val releaseFollowingBackground = CompletableDeferred<Unit>()
        val foreground = (0 until 3).map { index ->
            val id = "fgtrack${index.toString().padStart(4, '0')}"
            ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id),
                "The Foreground Song We Have Been Waiting For $index", id, FOREGROUND_ALBUM)
        }
        val calls = CopyOnWriteArrayList<List<ArtTrackOriginalName>>()
        try {
            fixture.database.recordMetadataNames(names((0 until 17).map(::backgroundSong)))
            fixture.start(if (alreadyForeground) FOREGROUND_ALBUM else null) { candidates, at ->
                calls += candidates
                when {
                    candidates.singleOrNull()?.sourceVideoId == backgroundSong(0).sourceVideoId -> {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                        throw IOException("One independent model group failed")
                    }
                    candidates.any { it.albumId == FOREGROUND_ALBUM } -> fixture.resolver.assess(candidates, at)
                    else -> {
                        followingBackgroundEntered.complete(Unit)
                        releaseFollowingBackground.await()
                        fixture.resolver.assess(candidates, at)
                    }
                }
            }
            withTimeout(10_000) { firstEntered.await() }
            if (!alreadyForeground) fixture.repository.setForegroundAlbum(FOREGROUND_ALBUM, true)
            fixture.database.recordMetadataNames(names(foreground))
            // The real display collector observes the newly arrived rows while classification
            // is held; no navigation/priority change accompanies the already-foreground case.
            withTimeout(5_000) { fixture.published.first { selected -> foreground.all {
                selected[it.target] == "保存された曲 ${it.target.id}"
            } } }
            releaseFirst.complete(Unit)
            withTimeout(10_000) { followingBackgroundEntered.await() }
            withTimeout(5_000) { fixture.published.first { selected -> foreground.all { selected[it.target] == it.name } } }

            assertEquals("Navigation interrupts the background batch after its current model call",
                foreground.toSet(), calls[1].toSet())
            assertEquals("The failed unchanged group is not retried in a tight loop", 1,
                calls.count { candidates -> candidates.any { it.sourceVideoId == backgroundSong(0).sourceVideoId } })
            assertTrue("The foreground publication is committed while later background work is still held",
                foreground.all { candidate -> fixture.database.metadataOriginalPublicationSnapshot()
                    .any { it.targetId == candidate.target.id && it.englishName == candidate.name } })
            assertTrue(fixture.database.metadataOriginalPublicationSnapshot().none {
                it.targetId == backgroundSong(0).target.id && it.englishName != null
            })
        } finally {
            releaseFirst.complete(Unit)
            releaseFollowingBackground.complete(Unit)
            fixture.close()
        }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
        private val job = SupervisorJob()
        val published = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        // Detector output is fixed to isolate scheduling and transaction boundaries from model cost.
        val resolver = OriginalAlbumLanguageResolver(OriginalTextLanguageDetector {
            listOf(OriginalTextLanguageScore("en", 0.99f))
        })
        lateinit var repository: MetadataNameRepository
            private set

        fun start(foregroundAlbum: String? = null,
            assess: suspend (List<ArtTrackOriginalName>, Long) -> List<OriginalNameAssessment>) {
            val locale = YouTubeLocale("JP", "ja")
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true)),
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                contextKey = { "JP:assessment-batching" }, now = { 1_800_000_000_000L },
                observeMetadata = {}, publishNames = { selected, _ -> published.value = selected },
                queue = { _, _ -> Result.success(emptyList()) },
                album = { _, _ -> Result.failure(IOException("Offline name scheduling fixture")) },
                albumContext = { _, _ -> Result.failure(IOException("Offline name scheduling fixture")) },
                artist = { _, _ -> Result.failure(IOException("Offline name scheduling fixture")) },
                main = { _, _ -> Result.failure(IOException("Offline name scheduling fixture")) },
                assessOriginals = assess,
            ))
            foregroundAlbum?.let { repository.setForegroundAlbum(it, true) }
            repository.start()
        }

        suspend fun close() { job.cancelAndJoin(); database.close() }
    }

    companion object {
        private const val FOREGROUND_ALBUM = "MPRE-foreground-batching"
        private fun backgroundSong(index: Int): ArtTrackOriginalName {
            val id = "bgtrack${index.toString().padStart(4, '0')}"
            return ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id),
                "The Background Song We Will Always Remember $index", id)
        }
        private fun names(sources: List<ArtTrackOriginalName>) = sources.flatMap { candidate -> listOf(
            MetadataNameEntity("SONG", candidate.target.id, "ja", "保存された曲 ${candidate.target.id}", "detail", 100, 100),
            MetadataNameEntity("SONG", candidate.target.id, "en", candidate.name, "detail", 100, 100),
            MetadataNameEntity("SONG", candidate.target.id, "und", candidate.name,
                ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, 10, 100, ArtTrackOriginalNameCodec.encode(candidate)),
        ) }
    }
}
