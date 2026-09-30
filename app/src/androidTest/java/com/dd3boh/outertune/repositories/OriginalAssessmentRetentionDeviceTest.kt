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
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameAssessmentCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.models.metadata.OriginalTextLanguageDetector
import com.dd3boh.outertune.models.metadata.OriginalTextLanguageScore
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room invalidation/publication with an instrumented classification boundary. */
class OriginalAssessmentRetentionDeviceTest {
    @Test(timeout = 15_000)
    fun roomSnapshotsShareExactProofButPreserveSameTimestampChangesAndWithdrawals() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
        val target = OriginalNameTarget(OriginalNameKind.SONG, "canonproof1")
        val original = ArtTrackOriginalName(target, "Snapshot proof", target.id, "MPREAa")
        val row = MetadataNameEntity("SONG", target.id, "und", original.name,
            ORIGINAL_NAME_SOURCE_PREFIX + target.id, observedAt = 100L,
            originEvidenceJson = ArtTrackOriginalNameCodec.encode(original))
        try {
            database.recordMetadataNames(listOf(row))
            val first = database.metadataOriginalEvaluationSnapshot().single()
            val second = database.metadataOriginalNameSnapshot().single()
            assertSame("Concurrent full reads should not retain equal proof copies",
                first.originEvidenceJson, second.originEvidenceJson)

            val changed = original.copy(albumId = "MPREBB")
            val changedProof = ArtTrackOriginalNameCodec.encode(changed)
            assertEquals(row.originEvidenceJson!!.hashCode(), changedProof.hashCode())
            database.recordMetadataNames(listOf(row.copy(originEvidenceJson = changedProof)))
            val afterChange = database.metadataOriginalEvaluationSnapshot().single()
            assertEquals(100L, afterChange.observedAt)
            assertNotSame(first.originEvidenceJson, afterChange.originEvidenceJson)
            assertEquals(changed, originalCandidate(afterChange))
            assertEquals(original, originalCandidate(first))

            database.recordMetadataNames(listOf(row.copy(originEvidenceJson = "{}")))
            assertNull(originalCandidate(database.metadataOriginalNameSnapshot().single()))
            database.recordMetadataNames(listOf(row))
            assertSame(first.originEvidenceJson,
                database.metadataOriginalEvaluationSnapshot().single().originEvidenceJson)
        } finally { database.close() }
    }

    @Test(timeout = 45_000)
    fun unchangedOriginalRemainsEnglishDuringRefreshWithoutReclassification() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(room)
        val job = SupervisorJob()
        val target = OriginalNameTarget(OriginalNameKind.SONG, "abcdefghijk")
        val originalTitle = "Something In The Way"
        val localizedTitle = "サムシング・イン・ザ・ウェイ"
        val locale = YouTubeLocale("JP", "ja")
        val clock = AtomicLong(1_790_000_000_000L)
        val mainRequests = AtomicInteger()
        val evaluations = AtomicInteger()
        val published = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val recordPublications = AtomicBoolean(false)
        val namesDuringRefresh = CopyOnWriteArrayList<String?>()
        val resolver = OriginalAlbumLanguageResolver()
        try {
            database.insert(SongEntity(target.id, "Stored provider title", localPath = null,
                inLibrary = LocalDateTime.of(2026, 9, 27, 12, 0)))
            val repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L),
                preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true)),
                observeMetadata = {},
                now = clock::get,
                contextKey = { "JP:retention-device" },
                publishNames = { selected, _ ->
                    if (recordPublications.get()) namesDuringRefresh += selected[target]
                    published.value = selected
                },
                queue = { ids, requestLocale ->
                    Result.success(ids.map { id ->
                        SongItem(id, if (requestLocale.hl == "ja") localizedTitle else originalTitle,
                            artists = emptyList(), thumbnail = "", endpoint = WatchEndpoint(
                                videoId = id,
                                watchEndpointMusicSupportedConfigs = WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_ATV")),
                            ))
                    })
                },
                artist = { _, _ -> error("This fixture has no artist target") },
                album = { _, _ -> error("This fixture has no album target") },
                albumContext = { _, _ -> error("This fixture has no album target") },
                main = { id, _ ->
                    assertEquals(target.id, id)
                    mainRequests.incrementAndGet()
                    Result.success(ArtTrackOriginalMetadata(id, originalTitle, null, null, null))
                },
                assessOriginals = { originals, evaluatedAt ->
                    evaluations.incrementAndGet()
                    resolver.assess(originals, evaluatedAt)
                },
            ))
            repository.start()
            withTimeout(15_000) {
                published.first { it[target] == originalTitle }
                while (true) {
                    val rows = database.metadataNameSnapshot()
                    val original = latestOriginalRows(rows).singleOrNull()
                    if (original != null && hasCurrentOriginalAssessmentInputs(original, rows) &&
                        rows.any { it.targetId == target.id && it.language == "ja" && it.name == localizedTitle } &&
                        repository.pendingRequestCount == 0) break
                    delay(10)
                }
            }
            val firstOriginal = latestOriginalRows(database.metadataNameSnapshot()).single()
            assertEquals(OriginalNameLanguage.ENGLISH,
                originalAssessmentsByTarget(database.metadataNameSnapshot()).getValue(target).single().language)
            assertEquals(1, mainRequests.get())
            assertEquals(1, evaluations.get())

            recordPublications.set(true)
            val refreshedAt = clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            repository.refreshTargets()
            withTimeout(10_000) {
                while (mainRequests.get() != 2 || repository.pendingRequestCount != 0 ||
                    latestOriginalRows(database.metadataNameSnapshot()).single().observedAt != refreshedAt) delay(10)
            }
            assertEquals(2, mainRequests.get())
            val refreshed = latestOriginalRows(database.metadataNameSnapshot()).single()
            assertEquals(refreshedAt, refreshed.observedAt)
            assertEquals(firstOriginal.originEvidenceJson, refreshed.originEvidenceJson)

            // This new name can be published only from a Room snapshot taken after the refresh
            // committed. It gives a deterministic barrier for the independent display collector,
            // while no unnecessary language model call has taken place.
            val sentinel = OriginalNameTarget(OriginalNameKind.SONG, "retention-publication-barrier")
            database.recordMetadataNames(listOf(MetadataNameEntity(
                kind = sentinel.kind.name, targetId = sentinel.id, language = "ja",
                name = "Refresh publication observed", source = "manual", sourcePriority = 100,
                observedAt = refreshedAt,
            )))
            withTimeout(5_000) { published.first { it[sentinel] == "Refresh publication observed" } }
            assertEquals(originalTitle, published.value[target])
            assertTrue("No publication may temporarily restore the localized title: $namesDuringRefresh",
                namesDuringRefresh.isNotEmpty() && namesDuringRefresh.all { it == originalTitle })
            assertEquals("Stored provider title", database.songForArtistCredit(target.id)!!.title)

            assertEquals("Unchanged originals must reuse their saved language result", 1, evaluations.get())
            assertEquals(originalTitle, published.value[target])
        } finally {
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test(timeout = 45_000)
    fun unrelatedAlbumOnlyClassifiesNewAlbumAndRetainsPreviousAssessment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
        val job = SupervisorJob()
        val locale = YouTubeLocale("JP", "ja")
        val now = 1_790_000_000_000L
        val published = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val evaluated = CopyOnWriteArrayList<List<ArtTrackOriginalName>>()
        val resolver = OriginalAlbumLanguageResolver(OriginalTextLanguageDetector { text ->
            listOf(OriginalTextLanguageScore("en", if ('\n' in text) 0.99f else 0.1f))
        })
        fun songs(album: String, firstIndex: Int) = (firstIndex until firstIndex + 3).map { index ->
            val id = "scope${index.toString().padStart(6, '0')}"
            ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id), "Short $index", id, album)
        }
        fun rows(values: List<ArtTrackOriginalName>) = values.map { candidate -> MetadataNameEntity(
            kind = "SONG", targetId = candidate.target.id, language = "und", name = candidate.name,
            source = ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, sourcePriority = 10,
            observedAt = now, originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate),
        ) }
        val oldAlbum = songs("old-album", 0)
        val newAlbum = songs("new-album", 3)
        try {
            database.recordMetadataNames(rows(oldAlbum))
            val repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L),
                preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true)),
                observeMetadata = {}, now = { now }, contextKey = { "JP:incremental-device" },
                publishNames = { names, _ -> published.value = names },
                queue = { _, _ -> Result.success(emptyList()) },
                artist = { _, _ -> error("No artist fetch is required") },
                album = { _, _ -> error("No album fetch is required") },
                albumContext = { _, _ -> error("No album fetch is required") },
                main = { _, _ -> Result.failure(IllegalStateException("Offline fixture")) },
                assessOriginals = { candidates, evaluatedAt ->
                    evaluated += candidates
                    resolver.assess(candidates, evaluatedAt)
                },
            ))
            repository.start()
            withTimeout(15_000) { published.first { names -> oldAlbum.all { names[it.target] == it.name } } }
            val originalEvidence = latestOriginalRows(database.metadataNameSnapshot())
                .associate { it.targetId to it.originEvidenceJson }
            assertEquals(1, evaluated.size)

            database.recordMetadataNames(rows(newAlbum))
            withTimeout(15_000) { published.first { names -> newAlbum.all { names[it.target] == it.name } } }
            assertEquals(2, evaluated.size)
            assertEquals(newAlbum.toSet(), evaluated.last().toSet())
            assertEquals(originalEvidence, latestOriginalRows(database.metadataNameSnapshot())
                .filter { it.targetId in originalEvidence }.associate { it.targetId to it.originEvidenceJson })
            assertTrue(oldAlbum.all { published.value[it.target] == it.name })
        } finally {
            job.cancelAndJoin()
            database.close()
        }
    }

    @Test(timeout = 45_000)
    fun unrelatedAlbumArrivingDuringClassificationCannotDelayCompletedPublication() = runBlocking {
        verifyDelayedAssessment(sameAlbumChange = false)
    }

    @Test(timeout = 45_000)
    fun albumContextChangedDuringClassificationRejectsStaleEnglishAndReevaluatesDependencies() = runBlocking {
        verifyDelayedAssessment(sameAlbumChange = true)
    }

    private suspend fun verifyDelayedAssessment(sameAlbumChange: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = MusicDatabase(Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build())
        val job = SupervisorJob()
        val locale = YouTubeLocale("JP", "ja")
        val now = 1_790_000_000_000L
        val published = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val publicationHistory = CopyOnWriteArrayList<Map<OriginalNameTarget, String>>()
        val evaluations = CopyOnWriteArrayList<List<ArtTrackOriginalName>>()
        val firstResult = CompletableDeferred<List<OriginalNameAssessment>>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<List<ArtTrackOriginalName>>()
        val releaseSecond = CompletableDeferred<Unit>()
        val resolver = OriginalAlbumLanguageResolver(OriginalTextLanguageDetector { text ->
            listOf(OriginalTextLanguageScore("en", if ('\n' in text) 0.99f else 0.1f))
        })
        fun song(index: Int, albumId: String, title: String = "Short $index"): ArtTrackOriginalName {
            val id = "delay${index.toString().padStart(6, '0')}"
            return ArtTrackOriginalName(OriginalNameTarget(OriginalNameKind.SONG, id), title, id, albumId)
        }
        val firstAlbum = (0..2).map { song(it, "first-album") }
        val additions = if (sameAlbumChange) listOf(song(3, "first-album", "夜に駆ける"))
            else (3..5).map { song(it, "second-album") }
        val all = firstAlbum + additions
        fun localized(candidate: ArtTrackOriginalName) = "保存された曲 ${candidate.target.id}"
        fun names(candidates: List<ArtTrackOriginalName>) = candidates.flatMap { candidate -> listOf(
            MetadataNameEntity("SONG", candidate.target.id, "und", candidate.name,
                ORIGINAL_NAME_SOURCE_PREFIX + candidate.sourceVideoId, sourcePriority = 10, observedAt = now,
                originEvidenceJson = ArtTrackOriginalNameCodec.encode(candidate)),
            MetadataNameEntity("SONG", candidate.target.id, "ja", localized(candidate), "detail", 80, now),
        ) }
        try {
            database.recordMetadataNames(names(firstAlbum))
            val repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L),
                preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true)),
                observeMetadata = {}, now = { now }, contextKey = { "JP:delayed-assessment" },
                publishNames = { selected, _ ->
                    publicationHistory += selected
                    published.value = selected
                },
                queue = { _, _ -> Result.success(emptyList()) },
                artist = { _, _ -> error("No artist fetch is required") },
                album = { _, _ -> error("No album fetch is required") },
                albumContext = { _, _ -> error("No album fetch is required") },
                main = { _, _ -> Result.failure(IllegalStateException("Offline fixture")) },
                assessOriginals = { candidates, evaluatedAt ->
                    evaluations += candidates
                    val results = resolver.assess(candidates, evaluatedAt)
                    when (evaluations.size) {
                        1 -> { firstResult.complete(results); releaseFirst.await() }
                        2 -> { secondEntered.complete(candidates); releaseSecond.await() }
                    }
                    results
                },
            ))
            repository.start()
            val completedFirst = withTimeout(10_000) { firstResult.await() }
            assertEquals(firstAlbum.toSet(), evaluations.single().toSet())
            assertTrue(completedFirst.all { it.language == OriginalNameLanguage.ENGLISH })

            // Change the DB after A's result has been computed, before it can commit. The display
            // barrier proves the added rows are already visible while classification stays blocked.
            database.recordMetadataNames(names(additions))
            withTimeout(5_000) { published.first { it[additions.first().target] == localized(additions.first()) } }
            releaseFirst.complete(Unit)
            val secondInput = withTimeout(10_000) { secondEntered.await() }
            val currentNames = database.metadataNameSnapshot()
            val currentInputs = originalAssessmentInputs(currentNames)
            val firstRows = latestOriginalRows(currentNames).filter { row -> firstAlbum.any { it.target.id == row.targetId } }
            assertEquals(firstAlbum.size, firstRows.size)
            if (sameAlbumChange) {
                assertEquals(all.toSet(), secondInput.toSet())
                firstRows.forEach { row ->
                    val candidate = originalCandidate(row)!!
                    assertNull("Changed album context must reject the already-computed English result",
                        OriginalNameAssessmentCodec.decode(row.originEvidenceJson, candidate.target, candidate.name))
                }
            } else {
                assertEquals(additions.toSet(), secondInput.toSet())
                assertTrue("Independent completed assessments must already be persisted",
                    firstRows.all { hasCurrentOriginalAssessmentInputs(it, currentInputs) })
            }
            if (sameAlbumChange) {
                assertTrue("Changed album context must still wait for its current assessment",
                    database.metadataOriginalPublicationSnapshot().none { it.englishName != null })
                assertTrue(publicationHistory.all { selected -> firstAlbum.none { selected[it.target] == it.name } })
            } else {
                withTimeout(5_000) { published.first { selected -> firstAlbum.all { selected[it.target] == it.name } } }
                assertTrue("A completed album must publish while unrelated classification is held",
                    firstAlbum.all { candidate -> database.metadataOriginalPublicationSnapshot()
                        .any { it.targetId == candidate.target.id && it.englishName == candidate.name } })
                assertTrue(additions.all { published.value[it.target] == localized(it) })
            }

            releaseSecond.complete(Unit)
            withTimeout(10_000) {
                while (true) {
                    val current = database.metadataNameSnapshot()
                    val originals = latestOriginalRows(current)
                    val inputs = originalAssessmentInputs(originals)
                    val publications = database.metadataOriginalPublicationSnapshot()
                    if (originals.size == all.size && originals.all { hasCurrentOriginalAssessmentInputs(it, inputs) } &&
                        publications.size == all.size && publications.all { publication ->
                            if (sameAlbumChange) publication.englishName == null
                            else all.single { it.target.id == publication.targetId }.name == publication.englishName
                        }) break
                    delay(10)
                }
            }
            withTimeout(5_000) { published.first { selected -> all.all {
                selected[it.target] == if (sameAlbumChange) localized(it) else it.name
            } } }
            assertEquals("Two snapshots need only two classifier batches", 2, evaluations.size)
            if (sameAlbumChange) {
                assertTrue("An obsolete English verdict must never be visible",
                    publicationHistory.all { selected -> firstAlbum.none { selected[it.target] == it.name } })
                assertTrue(originalAssessmentsByTarget(database.metadataNameSnapshot()).values.flatten()
                    .none { it.language == OriginalNameLanguage.ENGLISH })
            } else {
                firstAlbum.forEach { candidate ->
                    assertEquals("Unrelated updates must not classify the completed album again", 1,
                        evaluations.count { candidate in it })
                }
                assertTrue("Independent A must have become visible before B completed",
                    publicationHistory.any { selected -> firstAlbum.all { selected[it.target] == it.name } &&
                        additions.all { selected[it.target] == localized(it) } })
            }
        } finally {
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
            job.cancelAndJoin()
            database.close()
        }
    }
}
