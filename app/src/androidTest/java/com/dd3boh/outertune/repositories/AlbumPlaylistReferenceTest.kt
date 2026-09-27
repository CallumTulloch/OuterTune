package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.PlaylistSongReferences
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.UnavailablePlaylistSourceEntry
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
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
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Exercise playlist-entry identity through the real repository and an actual Room file. */
class AlbumPlaylistReferenceTest {
    @Test fun thirteenStableEntriesNameAllExistingIdsWithoutQueueSuccessOrVideoTypeAssumptions(): Unit = runBlocking {
        fixture { f ->
            // Equal titles remain separate provider entries and separate playback IDs.
            f.originalTitles = TITLES.toMutableList().also { it[1] = it[0] }
            val raw = f.rawSongs()
            f.start()
            f.awaitNames(f.originalTitles)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            assertEquals(13, f.validReferences().size)
            assertEquals(TARGET_IDS.toSet(), f.validReferences().map { it.targetVideoId }.toSet())
            assertEquals(SOURCE_IDS.toSet(), f.validReferences().map { it.sourceVideoId }.toSet())
            assertEquals(13, f.validReferences().map { it.playlistSetVideoId }.distinct().size)
            assertTrue(f.queueCalls.get() > 0)
            assertEquals(raw, f.rawSongs())
            assertEquals(TARGET_IDS, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
            assertTrue(latestOriginalRows(f.database.metadataNameSnapshot()).none { it.targetId in TARGET_IDS })
        }
    }

    @Test fun omittedNextNamesUseOnlyPreviouslyObservedAliasesForEachExactTargetId(): Unit = runBlocking {
        fixture { f ->
            f.albumReturnsSources = true
            f.omitNextNames = true
            f.seedTargetEnglishAliases()
            f.start()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            assertEquals(13, f.validReferences().size)
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun independentMusicAliasesRecoverTheObservedMainEditionTitleDespiteAnOlderAlbumSuccess(): Unit = runBlocking {
        fixture { f ->
            // The actual Eleanor Rigby response shape: Main includes the edition, source and
            // album Music aliases omit it, and next carries the official video's decorated name.
            val originalTitle = "Eleanor Rigby (Remastered 2015)"
            val musicTitle = "Eleanor Rigby"
            val configuredTitle = "エリナー・リグビー"
            f.originalTitles = listOf(originalTitle) + TITLES.drop(1)
            f.musicTitles = listOf(musicTitle) + TITLES.drop(1)
            f.nextTitles = listOf("The Beatles - Eleanor Rigby (From \"Yellow Submarine\") [Official Music Video]") + TITLES.drop(1)
            f.database.recordMetadataNames(listOf(MetadataNameEntity("SONG", TARGET_IDS.first(), "ja",
                configuredTitle, "detail", 100, f.clock.get())))
            val previousKey = "album-original-context:JP:playlist-fixture:v4"
            assertNotEquals(previousKey, albumOriginalContextKey("JP:playlist-fixture"))
            f.database.recordMetadataFetch(MetadataFetchEntity("ALBUM", ALBUM_ID, "und",
                MetadataFetchEntity.SUCCESS, f.clock.get(), previousKey))
            val raw = f.rawSongs()
            f.start()
            f.awaitNames(f.originalTitles)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            val reference = f.validReferences().single { it.targetVideoId == TARGET_IDS.first() }
            assertEquals(originalTitle, reference.originalName)
            assertEquals(musicTitle, reference.sourceMusicName)
            assertEquals(raw, f.rawSongs())
            assertEquals(TARGET_IDS, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertFalse("The original comes from Main evidence, not a fabricated Music alias",
                f.database.metadataNames("SONG", TARGET_IDS.first()).any { it.language == "en" && it.name == originalTitle })
            assertEquals(originalTitle, f.database.metadataOriginalPublicationSnapshot()
                .single { it.kind == "SONG" && it.targetId == TARGET_IDS.first() }.englishName)
            f.preferences.value = preferencesOf(ContentCountryKey to "JP", ContentLanguageKey to "ja",
                PreferEnglishOriginalKey to false)
            f.awaitNames(listOf(configuredTitle) + CONFIGURED.drop(1))
            f.preferences.value = preferencesOf(ContentCountryKey to "JP", ContentLanguageKey to "ja",
                PreferEnglishOriginalKey to true)
            f.awaitNames(f.originalTitles)
        }
    }

    @Test fun unnamedTargetWithoutItsOwnAliasRemainsIncompleteInsteadOfBorrowingSourceText(): Unit = runBlocking {
        fixture { f ->
            f.albumReturnsSources = true
            f.omitNextNames = true
            f.seedTargetEnglishAliases(except = TARGET_IDS.last())
            f.start()
            f.awaitAlbum(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.awaitCondition { f.validReferences().size == 12 }
            assertFalse(f.validReferences().any { it.targetVideoId == TARGET_IDS.last() })
            assertEquals(CONFIGURED.last(), f.names.value[target(TARGET_IDS.last())])
            assertFalse(f.database.metadataNameSnapshot().any {
                it.targetId == TARGET_IDS.last() && it.language == "en"
            })
        }
    }

    @Test fun partialSourceFailureKeepsAlbumIncompleteAndRetryFinishesRemainingEntry(): Unit = runBlocking {
        fixture { f ->
            f.failedSources = setOf(SOURCE_IDS.last())
            f.start()
            f.awaitAlbum(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.awaitNames(TITLES.dropLast(1) + CONFIGURED.last())
            assertEquals(12, f.validReferences().size)
            f.failedSources = emptySet()
            f.clock.addAndGet(5 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            assertEquals(13, f.validReferences().size)
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun delayedPlaylistObservationCannotCommitAfterAuthenticationOrCountryChanges(): Unit = runBlocking {
        for (changeAuthentication in listOf(true, false)) fixture { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.playlistResponder = { snapshot ->
                if (f.playlistCalls.get() == 1) {
                    entered.complete(Unit)
                    release.await()
                    Result.success(snapshot)
                } else Result.failure(IOException("No current-context playlist fixture"))
            }
            f.start()
            withTimeout(20_000) { entered.await() }
            if (changeAuthentication) f.auth.value = 1L
            else f.locales.value = YouTubeLocale("US", "ja")
            f.repository.refreshTargets()
            release.complete(Unit)
            f.awaitCondition { f.playlistCalls.get() >= 2 }
            f.awaitIdle()
            assertTrue(f.validReferences().isEmpty())
            assertTrue(f.database.metadataNameSnapshot().none { it.source.startsWith(PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX) })
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun aCompletedChangedEntryWithdrawsTheOldTargetWithoutChangingSavedPlaybackIds(): Unit = runBlocking {
        fixture { f ->
            f.start()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            val newTarget = "new00000000"
            f.mappingTargets = listOf(newTarget) + TARGET_IDS.drop(1)
            f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitCondition {
                f.validReferences().any { it.targetVideoId == newTarget } &&
                    f.validReferences().none { it.targetVideoId == TARGET_IDS.first() }
            }
            f.awaitNames(listOf(CONFIGURED.first()) + TITLES.drop(1))
            assertEquals(TITLES.first(), f.names.value[target(newTarget)])
            assertEquals(TARGET_IDS, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
            assertTrue(f.database.metadataNameSnapshot().any {
                it.targetId == TARGET_IDS.first() && it.source.startsWith(PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX) &&
                    PlaylistSongReferenceCodec.decode(it) == null
            })
        }
    }

    @Test fun eachReferencedTrackKeepsItsOwnCompletedLanguageDecision(): Unit = runBlocking {
        fixture { f ->
            f.originalTitles = TITLES.toMutableList().also { it[4] = "日本語の原題" }
            f.start()
            f.awaitNames(f.originalTitles.toMutableList().also { it[4] = CONFIGURED[4] })
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            assertEquals(13, f.validReferences().size)
            val verdicts = originalAssessmentsByTarget(f.database.metadataNameSnapshot())
            assertEquals(OriginalNameLanguage.OTHER, verdicts.getValue(target(TARGET_IDS[4])).single().language)
            assertEquals(OriginalNameLanguage.ENGLISH, verdicts.getValue(target(TARGET_IDS[3])).single().language)
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun aSecondAlbumEditionCanShareSourcesWithoutInvalidatingTheFirstEditionsDisplay(): Unit = runBlocking {
        fixture { f ->
            f.start()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            val originalBefore = latestOriginalRows(f.database.metadataNameSnapshot()).mapNotNull(::originalCandidate)
                .filter { it.target.kind == OriginalNameKind.SONG }.toSet()
            val firstSongsBefore = f.rawSongs()
            f.frames.clear()

            val renewedAt = f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            // Only the second edition should renew the expired shared source. A fresh first
            // album fetch would otherwise hide accidental source-context replacement by B.
            f.database.recordMetadataFetch(MetadataFetchEntity("ALBUM", ALBUM_ID, "und",
                MetadataFetchEntity.SUCCESS, renewedAt, albumOriginalContextKey("JP:playlist-fixture")))
            f.addSecondEdition()
            f.repository.refreshTargets()
            withTimeout(30_000) {
                f.names.first { selected -> SECOND_TARGET_IDS.indices.all { selected[target(SECOND_TARGET_IDS[it])] == TITLES[it] } }
            }
            f.awaitAlbum(MetadataFetchEntity.SUCCESS, SECOND_ALBUM_ID)
            f.awaitIdle()
            f.publicationBarrier()

            assertTrue(f.frames.isNotEmpty())
            assertTrue("The first edition must never lose its confirmed name while another edition arrives",
                f.frames.all { frame -> TARGET_IDS.indices.all { frame[target(TARGET_IDS[it])] == TITLES[it] } })
            val references = f.validReferences()
            assertEquals(26, references.size)
            assertEquals(TARGET_IDS.toSet() + SECOND_TARGET_IDS, references.map { it.targetVideoId }.toSet())
            assertEquals(setOf(PLAYLIST_ID, SECOND_PLAYLIST_ID), references.map { it.playlistId }.toSet())
            val renewedSources = latestOriginalRows(f.database.metadataNameSnapshot()).filter { it.kind == "SONG" }
            assertEquals(originalBefore, renewedSources.mapNotNull(::originalCandidate).toSet())
            assertTrue("The second edition must really renew the expired shared originals",
                renewedSources.all { it.observedAt >= renewedAt })
            assertEquals(firstSongsBefore, f.rawSongs())
            assertEquals(TARGET_IDS, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertEquals(SECOND_TARGET_IDS, f.database.albumSongs(SECOND_ALBUM_ID).first().map { it.id })
            assertEquals(TARGET_IDS.toSet() + SECOND_TARGET_IDS, f.storedSongIds())
        }
    }

    @Test fun anIncompleteMainRefreshKeepsTheSavedProofAndDisplayButDoesNotCompleteTheAlbum(): Unit = runBlocking {
        fixture { f ->
            f.start()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            val originalsBefore = latestOriginalRows(f.database.metadataNameSnapshot()).toSet()
            f.frames.clear()
            f.incompleteSources = SOURCE_IDS.toSet()
            val refreshedAt = f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitAlbum(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.publicationBarrier()

            assertEquals(originalsBefore, latestOriginalRows(f.database.metadataNameSnapshot()).toSet())
            assertEquals(13, f.validReferences().size)
            SOURCE_IDS.forEach { id ->
                val state = requireNotNull(f.database.metadataFetch("SONG", id, "und", originalMetadataContextKey(f.locales.value)))
                assertTrue(state.status in setOf(MetadataFetchEntity.EMPTY, MetadataFetchEntity.FAILED))
                assertEquals(refreshedAt, state.updatedAt)
            }
            assertTrue(f.frames.isNotEmpty())
            assertTrue("Missing distributor text is an incomplete response, not proof of a changed original",
                f.frames.all { frame -> TARGET_IDS.indices.all { frame[target(TARGET_IDS[it])] == TITLES[it] } })
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun nineteenTracksCompleteThroughSixteenPairedEntriesAndThreeIndependentRestrictedOriginals(): Unit = runBlocking {
        fixture(trackCount = 19, directIndices = (16 until 19).toSet()) { f ->
            f.restrictedIndices = (16 until 19).toSet()
            val raw = f.rawSongs()
            f.start()
            f.awaitNames(f.originalTitles)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            assertEquals(16, f.validReferences().size)
            assertEquals(f.targetIds.take(16).toSet(), f.validReferences().map { it.targetVideoId }.toSet())
            assertTrue(latestOriginalRows(f.database.metadataNameSnapshot())
                .filter { it.kind == "SONG" }.map { it.targetId }.containsAll(f.targetIds.takeLast(3)))
            assertEquals(MetadataFetchEntity.SUCCESS, f.database.metadataFetch("ALBUM", ALBUM_ID, "und",
                "album-playlist-reference:JP:playlist-fixture:v2")?.status)
            assertEquals(raw, f.rawSongs())
            assertEquals(f.targetIds, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertEquals(f.targetIds.toSet(), f.storedSongIds())
        }
    }

    @Test fun partialCoverageRetainsOnlyTheSameRestrictedEntryAndWithdrawsChangedAndRemovedIdentities(): Unit = runBlocking {
        fixture { f ->
            f.start()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            val restrictedBefore = f.validReferences().single { it.sourceVideoId == SOURCE_IDS.last() }
            f.restrictedIndices = setOf(12)
            f.removedIndices = setOf(11)
            val newTarget = "new00000000"
            f.mappingTargets = listOf(newTarget) + TARGET_IDS.drop(1)
            // Fresh identities remain authoritative even when source renewal fails.
            f.failedSources = setOf(SOURCE_IDS.first())
            f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitAlbum(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.awaitNames(listOf(CONFIGURED.first()) + TITLES.subList(1, 11) + CONFIGURED[11] + TITLES[12])
            val references = f.validReferences()
            assertEquals(restrictedBefore, references.single { it.sourceVideoId == SOURCE_IDS.last() })
            assertTrue(references.none { it.targetVideoId in setOf(TARGET_IDS.first(), TARGET_IDS[11]) })
            assertTrue(references.any { it.targetVideoId == newTarget })
            assertEquals(TARGET_IDS, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun restrictedOmissionCannotRetainATargetFreshlyAssignedToAnotherStableEntry(): Unit = runBlocking {
        fixture { f ->
            f.start()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            f.restrictedIndices = setOf(12)
            f.mappingTargets = listOf(TARGET_IDS.last()) + TARGET_IDS.drop(1)
            f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitAlbum(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            val latest = f.validReferences().filter { it.targetVideoId == TARGET_IDS.last() }
            assertEquals(1, latest.size)
            assertEquals(SOURCE_IDS.first(), latest.single().sourceVideoId)
            assertTrue(f.validReferences().none { it.sourceVideoId == SOURCE_IDS.last() })
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun sameEntryRetainsItsStoredProofWhenSourceEvidenceIsTemporarilyUnavailable(): Unit = runBlocking {
        fixture { f ->
            f.start()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            val oldReference = f.validReferences().single { it.sourceVideoId == SOURCE_IDS.first() }
            val oldRow = f.database.metadataNameSnapshot().single { PlaylistSongReferenceCodec.decode(it) == oldReference }
            // Simulate a missing source record followed by a failed network recovery. This must
            // neither invent a new original nor turn unchanged playlist identity into withdrawal.
            f.database.openHelper.writableDatabase.execSQL("DELETE FROM metadata_name WHERE source = ?",
                arrayOf(ORIGINAL_NAME_SOURCE_PREFIX + SOURCE_IDS.first()))
            f.failedSources = setOf(SOURCE_IDS.first())
            f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitAlbum(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            assertTrue(f.validReferences().contains(oldReference))
            assertEquals(oldRow, f.database.metadataNameSnapshot().single {
                it.targetId == oldRow.targetId && it.source == oldRow.source && it.name == oldRow.name
            })
            assertTrue(latestOriginalRows(f.database.metadataNameSnapshot()).none { it.targetId == SOURCE_IDS.first() })
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun malformedRestrictedCoverageFailsBeforeCreatingAnyReferences(): Unit = runBlocking {
        val invalid = listOf(
            listOf(UnavailablePlaylistSourceEntry("bad entry", "src00000099")),
            listOf(UnavailablePlaylistSourceEntry("new-entry", "invalid")),
            listOf(UnavailablePlaylistSourceEntry("stable-entry-0", "src00000099")),
            listOf(UnavailablePlaylistSourceEntry("new-entry", SOURCE_IDS.first())),
            List(2) { UnavailablePlaylistSourceEntry("duplicate", "src00000099") },
        )
        for (omissions in invalid) fixture { f ->
            f.playlistResponder = { Result.success(it.copy(unavailableSourceEntries = omissions)) }
            f.start()
            f.awaitAlbum(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            assertTrue(f.validReferences().isEmpty())
            assertEquals(MetadataFetchEntity.FAILED, f.database.metadataFetch("ALBUM", ALBUM_ID, "und",
                "album-playlist-reference:JP:playlist-fixture:v2")?.status)
            assertEquals(TARGET_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test fun newerExplicitWithdrawalCannotBeBypassedByAnOlderDecodedNameDuringRecovery(): Unit = runBlocking {
        fixture { f ->
            f.start()
            f.awaitNames(TITLES)
            f.awaitAlbum(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            val previous = f.database.metadataNameSnapshot().single {
                it.targetId == TARGET_IDS.first() && PlaylistSongReferenceCodec.decode(it) != null
            }
            f.database.recordMetadataNames(listOf(previous.copy(name = "Withdrawn observation",
                observedAt = f.clock.incrementAndGet(), originEvidenceJson = "{}")))
            f.playlistResponder = { Result.failure(IOException("Playlist refresh unavailable")) }
            f.fallbackTargets.clear()
            f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitAlbum(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            assertTrue("A withdrawn reference must allow the recovery path to run",
                TARGET_IDS.first() in f.fallbackTargets)
            assertTrue(f.validReferences().none { it.targetVideoId == TARGET_IDS.first() })
        }
    }

    private suspend fun fixture(trackCount: Int = 13, directIndices: Set<Int> = emptySet(), block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(trackCount, directIndices)
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture(trackCount: Int, private val directIndices: Set<Int>) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val databaseName = "album-playlist-reference-${UUID.randomUUID()}.db"
        val database = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build())
        private val job = SupervisorJob()
        val locales = MutableStateFlow(YouTubeLocale("JP", "ja"))
        val auth = MutableStateFlow(0L)
        val preferences = MutableStateFlow(preferencesOf(ContentCountryKey to "JP",
            ContentLanguageKey to "ja", PreferEnglishOriginalKey to true))
        val clock = AtomicLong(1_800_000_000_000L)
        val names = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val frames = CopyOnWriteArrayList<Map<OriginalNameTarget, String>>()
        val playlistCalls = AtomicInteger()
        val queueCalls = AtomicInteger()
        val fallbackTargets = CopyOnWriteArrayList<String>()
        val sourceIds = (0 until trackCount).map { "src%08d".format(it) }
        val targetIds = (0 until trackCount).map { if (it in directIndices) sourceIds[it] else "tgt%08d".format(it) }
        val configuredTitles = (0 until trackCount).map { "コンテンツ表記 ${it + 1}" }
        var originalTitles: List<String> = (0 until trackCount).map { "Original Song Number ${it + 1}" }
        var musicTitles: List<String>? = null
        var nextTitles: List<String>? = null
        var mappingTargets: List<String> = targetIds
        var restrictedIndices: Set<Int> = emptySet()
        var removedIndices: Set<Int> = emptySet()
        var albumReturnsSources = false
        var omitNextNames = false
        var failedSources = emptySet<String>()
        var incompleteSources = emptySet<String>()
        var playlistResponder: suspend (PlaylistSongReferences) -> Result<PlaylistSongReferences> = { Result.success(it) }
        lateinit var repository: MetadataNameRepository
            private set

        init {
            database.insert(AlbumEntity(ALBUM_ID, title = ALBUM_TITLE, songCount = trackCount, duration = 200 * trackCount,
                hasTrackList = true, bookmarkedAt = LocalDateTime.of(2026, 9, 20, 0, 0)))
            targetIds.forEachIndexed { index, id ->
                database.insert(SongEntity(id, configuredTitles[index], duration = 200, localPath = null,
                    albumId = ALBUM_ID, albumName = ALBUM_TITLE))
                database.insert(SongAlbumMap(id, ALBUM_ID, index))
                database.recordMetadataNames(listOf(MetadataNameEntity("SONG", id, "ja", configuredTitles[index],
                    "album", 50, clock.get())))
            }
        }

        fun seedTargetEnglishAliases(except: String? = null) {
            database.recordMetadataNames(targetIds.mapIndexedNotNull { index, id ->
                if (id == except) null else MetadataNameEntity("SONG", id, "en", originalTitles[index],
                    "album", 50, clock.get())
            })
        }

        fun addSecondEdition() {
            database.insert(AlbumEntity(SECOND_ALBUM_ID, title = SECOND_ALBUM_TITLE, songCount = 13, duration = 2600,
                hasTrackList = true, bookmarkedAt = LocalDateTime.of(2026, 9, 20, 0, 1)))
            SECOND_TARGET_IDS.forEachIndexed { index, id ->
                database.insert(SongEntity(id, "別版表記 ${index + 1}", duration = 200, localPath = null,
                    albumId = SECOND_ALBUM_ID, albumName = SECOND_ALBUM_TITLE))
                database.insert(SongAlbumMap(id, SECOND_ALBUM_ID, index))
                database.recordMetadataNames(listOf(MetadataNameEntity("SONG", id, "ja", "別版表記 ${index + 1}",
                    "album", 50, clock.get())))
            }
        }

        private fun sourceSongs(albumId: String = ALBUM_ID) = sourceIds.mapIndexed { index, id ->
            song(id, (musicTitles ?: originalTitles)[index], "MUSIC_VIDEO_TYPE_ATV", albumId)
        }

        private fun targetSongs(ids: List<String> = mappingTargets, albumId: String = ALBUM_ID) = ids.mapIndexed { index, id ->
            song(id, (musicTitles ?: originalTitles)[index], if (index in directIndices) "MUSIC_VIDEO_TYPE_ATV" else when (index) {
                0 -> "MUSIC_VIDEO_TYPE_UGC"
                1 -> null
                else -> "MUSIC_VIDEO_TYPE_OMV"
            }, albumId)
        }

        private fun snapshot(playlistId: String): PlaylistSongReferences {
            val secondary = playlistId == SECOND_PLAYLIST_ID
            val albumId = if (secondary) SECOND_ALBUM_ID else ALBUM_ID
            val targets = if (secondary) SECOND_TARGET_IDS else mappingTargets
            val excluded = restrictedIndices + removedIndices
            return PlaylistSongReferences(playlistId,
                sourceIds.mapIndexedNotNull { index, id -> if (index in excluded) null else
                    PlaylistSongReference(playlistId, "stable-entry-$index", id, targets[index]) },
                sourceSongs(albumId).filterIndexed { index, _ -> index !in excluded },
                if (omitNextNames) emptyList() else targetSongs(targets, albumId)
                    .mapIndexed { index, item -> nextTitles?.let { item.copy(title = it[index]) } ?: item }
                    .filterIndexed { index, _ -> index !in excluded },
                restrictedIndices.sorted().map { UnavailablePlaylistSourceEntry("stable-entry-$it", sourceIds[it]) })
        }

        fun start() {
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                locale = { locales.value }, localeUpdates = locales,
                authRevision = { auth.value }, authUpdates = auth,
                preferences = preferences,
                now = clock::get, contextKey = { "${it.gl}:playlist-fixture" }, observeMetadata = {},
                publishNames = { selected, _ -> frames += selected.toMap(); names.value = selected },
                queue = { _, _ ->
                    queueCalls.incrementAndGet()
                    Result.failure(IOException("Queue detail is intentionally unavailable"))
                },
                album = { id, _ -> Result.success(AlbumItem(id,
                    if (id == SECOND_ALBUM_ID) SECOND_PLAYLIST_ID else PLAYLIST_ID,
                    title = if (id == SECOND_ALBUM_ID) SECOND_ALBUM_TITLE else ALBUM_TITLE,
                    artists = emptyList(), thumbnail = "")) },
                artist = { _, _ -> Result.failure(IOException("Fixture has no artist lookup")) },
                albumContext = { id, _ ->
                    check(id in setOf(ALBUM_ID, SECOND_ALBUM_ID))
                    Result.success(if (albumReturnsSources) sourceSongs(id)
                        else targetSongs(if (id == SECOND_ALBUM_ID) SECOND_TARGET_IDS else targetIds, id))
                },
                playlistReferences = { id, _ ->
                    check(id in setOf(PLAYLIST_ID, SECOND_PLAYLIST_ID))
                    playlistCalls.incrementAndGet()
                    playlistResponder(snapshot(id))
                },
                albumSongSources = { item, _ -> fallbackTargets += item.id; Result.success(emptyList()) },
                mainSongReference = { _, _ -> Result.success(null) },
                main = { id, _ ->
                    val index = sourceIds.indexOf(id)
                    when {
                        id in failedSources -> Result.failure(IOException("Synthetic partial source failure"))
                        index < 0 -> Result.success(ArtTrackOriginalMetadata(id, "Ordinary video title", null, null, "Video description"))
                        id in incompleteSources -> Result.success(ArtTrackOriginalMetadata(id, originalTitles[index],
                            "Fixture - Topic", null, null))
                        else -> {
                            val title = originalTitles[index]
                            Result.success(ArtTrackOriginalMetadata(id, title, "Fixture - Topic", null,
                                "Provided to YouTube by Fixture Records\n\n$title · Fixture Performer\n\n$ALBUM_TITLE\n\n" +
                                    "℗ Fixture Records\n\nAuto-generated by YouTube."))
                        }
                    }
                },
                // These tests isolate identity/acquisition/publication from the separately tested model.
                assessOriginals = { originals, at -> originals.map { candidate ->
                    val language = if (candidate.name == "日本語の原題") OriginalNameLanguage.OTHER else OriginalNameLanguage.ENGLISH
                    OriginalNameAssessment(candidate.target, candidate.name, candidate.sourceVideoId,
                        "https://www.youtube.com/watch?v=${candidate.sourceVideoId}", language, 0.99f,
                        OriginalAlbumLanguageResolver.METHOD_VERSION + "/fixture-per-track", "fixture-${candidate.name}", at)
                } },
            )).also { it.start() }
        }

        fun validReferences() = database.metadataNameSnapshot()
            .filter { it.source.startsWith(PLAYLIST_SONG_REFERENCE_SOURCE_PREFIX) }
            .groupBy { it.targetId to it.source }.values.flatMap { rows ->
                rows.filter { it.observedAt == rows.maxOf { row -> row.observedAt } }.mapNotNull(PlaylistSongReferenceCodec::decode)
            }
        suspend fun rawSongs() = targetIds.map { database.song(it).first()!!.song }
        fun storedSongIds(): Set<String> = database.openHelper.readableDatabase.query("SELECT id FROM song").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        suspend fun awaitNames(expected: List<String>) {
            withTimeout(30_000) { names.first { selected -> targetIds.indices.all { selected[target(targetIds[it])] == expected[it] } } }
        }
        suspend fun awaitAlbum(status: String, albumId: String = ALBUM_ID) = awaitCondition {
            database.metadataFetch("ALBUM", albumId, "und", albumOriginalContextKey("${locales.value.gl}:playlist-fixture"))?.status == status
        }
        suspend fun publicationBarrier() {
            val barrier = OriginalNameTarget(OriginalNameKind.SONG, "playlist-publication-barrier")
            database.recordMetadataNames(listOf(MetadataNameEntity("SONG", barrier.id, "ja", "Publication ready",
                "manual", 100, clock.get())))
            withTimeout(30_000) { names.first { it[barrier] == "Publication ready" } }
        }
        suspend fun awaitIdle() = awaitCondition { repository.pendingRequestCount == 0 }
        suspend fun awaitCondition(predicate: () -> Boolean) {
            withTimeout(30_000) { while (!predicate()) delay(10) }
        }
        suspend fun close() {
            job.cancelAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }

        private fun song(id: String, title: String, type: String?, albumId: String = ALBUM_ID) = SongItem(id, title, emptyList(),
            Album(if (albumId == SECOND_ALBUM_ID) SECOND_ALBUM_TITLE else ALBUM_TITLE, albumId), duration = 200, thumbnail = "",
            endpoint = type?.let { WatchEndpoint(videoId = id, watchEndpointMusicSupportedConfigs =
                WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(it))) })
    }

    companion object {
        private const val ALBUM_ID = "MPREplaylistFixture"
        private const val PLAYLIST_ID = "OLAK5uy_playlist_fixture"
        private const val ALBUM_TITLE = "Fixture Album"
        private const val SECOND_ALBUM_ID = "MPREplaylistSecondEdition"
        private const val SECOND_PLAYLIST_ID = "OLAK5uy_second_edition_fixture"
        private const val SECOND_ALBUM_TITLE = "Fixture Album Second Edition"
        private val SOURCE_IDS = (0 until 13).map { "src%08d".format(it) }
        private val TARGET_IDS = (0 until 13).map { "tgt%08d".format(it) }
        private val SECOND_TARGET_IDS = (0 until 13).map { "ed2%08d".format(it) }
        private val TITLES = (0 until 13).map { "Original Song Number ${it + 1}" }
        private val CONFIGURED = (0 until 13).map { "コンテンツ表記 ${it + 1}" }
        private fun target(id: String) = OriginalNameTarget(OriginalNameKind.SONG, id)
    }
}
