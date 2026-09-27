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
import com.dd3boh.outertune.db.entities.AlbumArtistMap
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongArtistMap
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalName
import com.dd3boh.outertune.models.metadata.ArtTrackOriginalNameCodec
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.MainSongReference
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.IOException
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Main music card proves a relation between two IDs; a shared title or album never does. */
class AlbumProviderSongReferenceTest {
    @Test
    fun cachedAudioOriginalsNameTheExistingVideoRowsAndSurviveRestartWithoutChangingPlaybackIdentity(): Unit = runBlocking {
        withFixture { f ->
            f.seedKnownOriginals()
            val rawBefore = f.rawSongs()
            val albumBefore = f.database.albumById(ALBUM_ID)
            f.start()
            f.awaitNames(ENGLISH_TITLES)
            f.awaitIdle()
            f.assertStoredIdentity(rawBefore, albumBefore)
            assertTrue(f.referenceCalls.get() > 0)
            assertEquals(MV_IDS.toSet(), f.referenceRows().map { it.targetId }.toSet())
            assertTrue("A provider relation must not masquerade as an original from the MV itself",
                f.database.metadataNameSnapshot().none { it.targetId in MV_IDS && originalCandidate(it) != null })

            f.settings.value = preferences(false)
            f.awaitNames(JAPANESE_TITLES)
            f.settings.value = preferences(true)
            f.awaitNames(ENGLISH_TITLES)
            f.assertStoredIdentity(rawBefore, albumBefore)

            val referencesBefore = f.referenceRows()
            f.restart()
            f.awaitNames(ENGLISH_TITLES)
            f.awaitIdle()
            assertEquals(referencesBefore.toSet(), f.referenceRows().toSet())
            f.assertStoredIdentity(rawBefore, albumBefore)
        }
    }

    @Test
    fun persistedVideoRowsRecoverWithoutOpeningOrFetchingTheUnbookmarkedAlbumsDifferentTrackList(): Unit = runBlocking {
        withFixture { f ->
            f.seedKnownOriginals()
            f.database.update(requireNotNull(f.database.albumById(ALBUM_ID)).copy(bookmarkedAt = null))
            f.saveRowsInPlaylist(MV_IDS)
            f.albumReturnsAudio = true
            f.database.recordMetadataNames(MV_IDS.flatMapIndexed { index, id -> listOf(
                MetadataNameEntity("SONG", id, "en", ENGLISH_TITLES[index] + " (Official Music Video)",
                    "detail", 100, f.clock.get()),
                MetadataNameEntity("SONG", id, "en", ENGLISH_TITLES[index],
                    "album-original-context", 50, f.clock.get() - 1),
            ) })
            assertFalse(f.database.isAlbumOriginalContextEligible(ALBUM_ID))
            val rawBefore = f.rawSongs()
            val albumBefore = f.database.albumById(ALBUM_ID)

            // No album observer event is sent. The higher-priority detail title has a suffix;
            // only the stored album alias matches the direct original. That match discovers
            // a candidate; Main's explicit card still has to prove which video it names.
            f.start()
            f.awaitNames(ENGLISH_TITLES)
            f.awaitIdle()
            assertTrue(f.referenceCalls.get() >= MV_IDS.size)
            assertEquals(0, f.albumContextCalls.get())
            assertEquals(0, f.discoveryCalls.get())
            assertEquals(MV_IDS.toSet(), f.referenceRows().map { it.targetId }.toSet())
            f.assertStoredIdentity(rawBefore, albumBefore)
        }
    }

    @Test
    fun expiredReferenceReturningNoCardWithdrawsEnglishDisplayAndCannotReviveAfterRestart(): Unit = runBlocking {
        withFixture { f ->
            f.seedKnownOriginals()
            f.sourceDetailsAvailable = true
            val rawBefore = f.rawSongs()
            val albumBefore = f.database.albumById(ALBUM_ID)
            f.start()
            f.awaitNames(ENGLISH_TITLES)
            f.awaitIdle()
            assertEquals(MV_IDS.toSet(), f.referenceRows().mapNotNull(ProviderSongReferenceCodec::decode)
                .map { it.targetVideoId }.toSet())

            f.referenceCardMissing = true
            val refreshedAt = f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitCondition {
                AUDIO_IDS.all { id ->
                    f.database.metadataFetch("SONG", id, "und", "main-song-card:JP:v1")?.let {
                        it.status == MetadataFetchEntity.EMPTY && it.updatedAt == refreshedAt
                    } == true
                }
            }
            f.awaitIdle()
            f.awaitCondition {
                val assessments = originalAssessmentsByTarget(f.database.metadataNameSnapshot())
                AUDIO_IDS.all { id -> assessments[target(id)].orEmpty().any {
                    it.language == OriginalNameLanguage.ENGLISH
                } }
            }
            f.awaitNames(JAPANESE_TITLES)
            // Source originals still qualify as English. It is specifically the missing card
            // that removes their authority over the existing MV rows, while aliases remain.
            assertEquals(MV_IDS.toSet(), f.referenceRows().map { it.targetId }.toSet())
            assertTrue(f.referenceRows().all { it.observedAt == refreshedAt })
            assertTrue(f.referenceRows().mapNotNull(ProviderSongReferenceCodec::decode).isEmpty())
            f.assertStoredIdentity(rawBefore, albumBefore)

            f.restart()
            f.awaitNames(JAPANESE_TITLES)
            f.awaitIdle()
            assertTrue(f.referenceRows().mapNotNull(ProviderSongReferenceCodec::decode).isEmpty())
            assertTrue(associatedOriginalAssessments(f.database.metadataNameSnapshot()).none {
                it.target.id in MV_IDS
            })
            f.assertStoredIdentity(rawBefore, albumBefore)
        }
    }

    @Test
    fun anEarlierSameNameVideoCannotPoisonOrWithdrawTheCardsActualTarget(): Unit = runBlocking {
        withFixture { f ->
            val unrelatedId = "aaaaaaaaaaa"
            val unrelatedJapanese = "同名の別動画"
            f.seedKnownOriginals()
            f.sourceDetailsAvailable = true
            f.database.update(requireNotNull(f.database.albumById(ALBUM_ID)).copy(bookmarkedAt = null))
            f.database.insert(SongEntity(unrelatedId, unrelatedJapanese, duration = 180, localPath = null,
                albumId = ALBUM_ID, albumName = JAPANESE_ALBUM))
            f.saveRowsInPlaylist(listOf(unrelatedId) + MV_IDS)
            // Persist the competing English candidate first; it has the same album and title,
            // but Main's card points only to hTWKbfoikeg, never to this earlier candidate.
            f.database.recordMetadataNames(listOf(
                MetadataNameEntity("SONG", unrelatedId, "en", ENGLISH_TITLES.first(), "detail", 100, f.clock.get()),
                MetadataNameEntity("SONG", unrelatedId, "ja", unrelatedJapanese, "detail", 100, f.clock.get()),
            ))
            f.database.recordMetadataNames(MV_IDS.mapIndexed { index, id ->
                MetadataNameEntity("SONG", id, "en", ENGLISH_TITLES[index], "detail", 100, f.clock.get())
            })
            val englishOrder = f.database.metadataNameSnapshot().filter { it.language == "en" }.map { it.targetId }
            assertTrue(englishOrder.indexOf(unrelatedId) < englishOrder.indexOf(MV_IDS.first()))
            assertFalse(f.database.isAlbumOriginalContextEligible(ALBUM_ID))
            val rawBefore = f.rawSongs()
            val unrelatedBefore = f.database.song(unrelatedId).first()!!.song
            val albumBefore = f.database.albumById(ALBUM_ID)

            f.start()
            f.awaitNames(ENGLISH_TITLES)
            f.awaitIdle()
            assertEquals(unrelatedJapanese, f.names.value[target(unrelatedId)])
            assertTrue(f.database.metadataNameSnapshot().mapNotNull(ProviderSongReferenceCodec::decode)
                .none { it.targetVideoId == unrelatedId })
            f.assertStoredIdentity(rawBefore, albumBefore, setOf(unrelatedId))

            val refreshedAt = f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitCondition {
                f.database.metadataFetch("SONG", AUDIO_IDS.first(), "und", "main-song-card:JP:v1")?.let {
                    it.status == MetadataFetchEntity.SUCCESS && it.updatedAt == refreshedAt
                } == true
            }
            f.awaitIdle()
            f.awaitNames(ENGLISH_TITLES)
            assertEquals(unrelatedJapanese, f.names.value[target(unrelatedId)])
            val links = f.database.metadataNameSnapshot().mapNotNull(ProviderSongReferenceCodec::decode)
            assertTrue(links.any { it.sourceVideoId == AUDIO_IDS.first() && it.targetVideoId == MV_IDS.first() })
            assertTrue(links.none { it.targetVideoId == unrelatedId })
            assertEquals(unrelatedBefore, f.database.song(unrelatedId).first()!!.song)
            assertEquals(0, f.albumContextCalls.get())
            f.assertStoredIdentity(rawBefore, albumBefore, setOf(unrelatedId))
        }
    }

    @Test
    fun anAlbumPageWithoutStoredSongRowsRetriesFailedCardRefreshAndWithdrawsConfirmedMissingReferences(): Unit = runBlocking {
        withFixture { f ->
            f.seedKnownOriginals()
            f.sourceDetailsAvailable = true
            f.rawSongs().forEach { f.database.delete(it) }
            assertTrue(f.storedSongIds().isEmpty())
            assertTrue(f.database.isAlbumOriginalContextEligible(ALBUM_ID))
            val albumBefore = f.database.albumById(ALBUM_ID)
            f.start()
            f.awaitNames(ENGLISH_TITLES)
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            val albumCallsBefore = f.albumContextCalls.get()
            assertTrue(albumCallsBefore > 0)
            assertEquals(MV_IDS.toSet(), f.referenceRows().mapNotNull(ProviderSongReferenceCodec::decode)
                .map { it.targetVideoId }.toSet())
            assertTrue(f.storedSongIds().isEmpty())

            f.failReferenceFetch = true
            val failedAt = f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitCondition {
                AUDIO_IDS.all { id ->
                    f.database.metadataFetch("SONG", id, "und", "main-song-card:JP:v1")?.let {
                        it.status == MetadataFetchEntity.FAILED && it.updatedAt == failedAt
                    } == true
                }
            }
            f.awaitAlbumState(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.awaitNames(ENGLISH_TITLES)
            assertEquals(MV_IDS.toSet(), f.referenceRows().mapNotNull(ProviderSongReferenceCodec::decode)
                .map { it.targetVideoId }.toSet())
            assertTrue(f.albumContextCalls.get() > albumCallsBefore)
            val albumCallsAfterFailure = f.albumContextCalls.get()

            // A transport failure keeps existing evidence but must not grant a seven-day
            // album-success TTL. Revisit after five minutes to verify the actual missing card.
            f.failReferenceFetch = false
            f.referenceCardMissing = true
            val refreshedAt = f.clock.addAndGet(5 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitCondition {
                AUDIO_IDS.all { id ->
                    f.database.metadataFetch("SONG", id, "und", "main-song-card:JP:v1")?.let {
                        it.status == MetadataFetchEntity.EMPTY && it.updatedAt == refreshedAt
                    } == true
                }
            }
            f.awaitAlbumState(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.awaitNames(JAPANESE_TITLES)
            assertTrue(f.albumContextCalls.get() > albumCallsAfterFailure)
            assertTrue(f.referenceRows().mapNotNull(ProviderSongReferenceCodec::decode).isEmpty())
            assertTrue(associatedOriginalAssessments(f.database.metadataNameSnapshot()).none { it.target.id in MV_IDS })
            assertTrue(f.storedSongIds().isEmpty())
            assertEquals(albumBefore, f.database.albumById(ALBUM_ID))
        }
    }

    @Test
    fun sameNameAndAlbumDiscoveryCannotReplaceAnExplicitLinkToAnotherVideo(): Unit = runBlocking {
        withFixture { f ->
            f.referenceTarget = { "wrong000001" }
            f.start()
            f.awaitAlbumState(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.awaitNames(JAPANESE_TITLES)
            assertTrue(f.discoveryCalls.get() > 0)
            assertTrue(f.referenceCalls.get() > 0)
            assertTrue(f.referenceRows().isEmpty())
            assertEquals(MV_IDS, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertEquals(MV_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test
    fun aJapaneseSourceOriginalIsNotChangedToTheEnglishSearchCandidate(): Unit = runBlocking {
        withFixture { f ->
            f.sourceOriginalTitles = listOf("夜に駆ける", "群青", "怪物")
            f.start()
            f.awaitIdleAfterReference()
            f.awaitNames(JAPANESE_TITLES)
            f.awaitCondition {
                val assessments = originalAssessmentsByTarget(f.database.metadataNameSnapshot())
                AUDIO_IDS.all { id -> assessments[target(id)].orEmpty().any { it.language == OriginalNameLanguage.OTHER } }
            }
            assertEquals(JAPANESE_TITLES, MV_IDS.map { f.names.value[target(it)] })
            assertEquals(MV_IDS.toSet(), f.storedSongIds())
        }
    }

    @Test
    fun failedSourceFetchRetriesAfterFiveMinutesWithoutChangingTheAlbumsVideoIds(): Unit = runBlocking {
        withFixture { f ->
            f.failSourceFetch = true
            val before = f.rawSongs()
            f.start()
            f.awaitAlbumState(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            f.awaitNames(JAPANESE_TITLES)
            assertTrue(AUDIO_IDS.any { f.mainCount(it) > 0 })
            assertTrue(f.referenceRows().isEmpty())
            val failedCounts = AUDIO_IDS.associateWith(f::mainCount)

            f.failSourceFetch = false
            f.clock.addAndGet(5 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitNames(ENGLISH_TITLES)
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.awaitIdle()
            assertTrue(AUDIO_IDS.all { f.mainCount(it) > failedCounts.getValue(it) })
            assertEquals(before, f.rawSongs())
            assertEquals(MV_IDS, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertEquals(MV_IDS.toSet(), f.storedSongIds())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val databaseName = "provider-song-reference-${UUID.randomUUID()}.db"
        private fun openDatabase() = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, databaseName).build())
        var database = openDatabase()
            private set
        private var job = SupervisorJob()
        val locale = YouTubeLocale("JP", "ja")
        val settings = MutableStateFlow(preferences(true))
        val clock = AtomicLong(1_800_000_000_000L)
        val names = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val discoveryCalls = AtomicInteger()
        val referenceCalls = AtomicInteger()
        val albumContextCalls = AtomicInteger()
        private val mainCalls = ConcurrentHashMap<String, AtomicInteger>()
        var sourceOriginalTitles = ENGLISH_TITLES
        var failSourceFetch = false
        var albumReturnsAudio = false
        var sourceDetailsAvailable = false
        var referenceCardMissing = false
        var failReferenceFetch = false
        var referenceTarget: (String) -> String = { source -> MV_IDS[AUDIO_IDS.indexOf(source)] }
        lateinit var repository: MetadataNameRepository
            private set
        private val videoSongs = MV_IDS.mapIndexed { index, id -> song(id, ENGLISH_TITLES[index], "MUSIC_VIDEO_TYPE_OMV") }
        private val audioSongs = AUDIO_IDS.mapIndexed { index, id -> song(id, ENGLISH_TITLES[index], "MUSIC_VIDEO_TYPE_ATV") }

        init {
            database.insert(ArtistEntity(ARTIST_ID, "ニルヴァーナ"))
            database.insert(AlbumEntity(ALBUM_ID, title = JAPANESE_ALBUM, songCount = MV_IDS.size, duration = 540,
                hasTrackList = true, bookmarkedAt = LocalDateTime.of(2026, 9, 20, 0, 0)))
            database.insert(AlbumArtistMap(ALBUM_ID, ARTIST_ID, 0))
            MV_IDS.forEachIndexed { index, id ->
                database.insert(SongEntity(id, JAPANESE_TITLES[index], duration = 180, localPath = null,
                    albumId = ALBUM_ID, albumName = JAPANESE_ALBUM))
                database.insert(SongAlbumMap(id, ALBUM_ID, index))
                database.insert(SongArtistMap(id, ARTIST_ID, 0))
                database.recordMetadataNames(listOf(MetadataNameEntity("SONG", id, "ja", JAPANESE_TITLES[index],
                    "album", 50, clock.get())), MetadataFetchEntity("SONG", id, "und", MetadataFetchEntity.EMPTY,
                    clock.get(), originalMetadataContextKey(locale)))
            }
            database.saveQueueSnapshot(listOf(MultiQueueObject(id = 10, title = "Existing MV queue", index = 0,
                queuePos = 1, queue = MV_IDS.indices.map { index -> MediaMetadata(MV_IDS[index], JAPANESE_TITLES[index],
                    listOf(MediaMetadata.Artist(ARTIST_ID, "ニルヴァーナ")), duration = 180, genre = null,
                    album = MediaMetadata.Album(ALBUM_ID, JAPANESE_ALBUM), shuffleIndex = index) }.toMutableList())))
        }

        fun seedKnownOriginals() {
            AUDIO_IDS.forEachIndexed { index, id ->
                val candidate = ArtTrackOriginalName(target(id), sourceOriginalTitles[index], id, ALBUM_ID)
                database.recordMetadataNames(listOf(
                    MetadataNameEntity("SONG", id, "en", ENGLISH_TITLES[index], "detail", 100, clock.get() - 1),
                    MetadataNameEntity("SONG", id, "und", candidate.name, ORIGINAL_NAME_SOURCE_PREFIX + id,
                        10, clock.get() - 1, ArtTrackOriginalNameCodec.encode(candidate)),
                ), MetadataFetchEntity("SONG", id, "und", MetadataFetchEntity.SUCCESS,
                    clock.get() - 1, originalMetadataContextKey(locale)))
            }
        }

        suspend fun saveRowsInPlaylist(ids: List<String>) {
            // Queue/database persistence alone is not a saved interest. Saving this playlist
            // schedules the exact video rows while leaving the unbookmarked album's complete
            // track list ineligible, which is the behavior these two recovery tests exercise.
            val playlistId = "LP_provider_saved_fixture"
            database.insert(PlaylistEntity(id = playlistId, name = "Saved video choices",
                bookmarkedAt = LocalDateTime.of(2026, 9, 20, 0, 0)))
            ids.forEachIndexed { index, id ->
                database.insert(PlaylistSongMap(playlistId = playlistId, songId = id, position = index))
            }
            assertEquals(ids.toSet(), database.metadataRefreshTargets().first()
                .filter { it.kind == "SONG" }.map { it.targetId }.toSet())
            assertFalse(database.isAlbumOriginalContextEligible(ALBUM_ID))
        }

        fun start() {
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), preferences = settings,
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L), now = clock::get,
                contextKey = { CONTEXT_KEY }, observeMetadata = {},
                publishNames = { selected, _ -> names.value = selected },
                queue = { ids, _ -> Result.success(if (sourceDetailsAvailable) audioSongs.filter { it.id in ids } else emptyList()) },
                album = { id, requested -> Result.success(AlbumItem(id, "OLAK-provider-reference", title =
                    if (requested.hl == "en") ENGLISH_ALBUM else JAPANESE_ALBUM,
                    artists = listOf(Artist("Nirvana", ARTIST_ID)), thumbnail = "")) },
                artist = { id, requested -> Result.success(ArtistItem(id,
                    if (requested.hl == "en") "Nirvana" else "ニルヴァーナ", null,
                    shuffleEndpoint = null, radioEndpoint = null)) },
                albumContext = { id, requested ->
                    check(id == ALBUM_ID && requested.hl == "en")
                    albumContextCalls.incrementAndGet()
                    Result.success(if (albumReturnsAudio) audioSongs else videoSongs)
                },
                albumSongSources = { video, requested ->
                    check(requested.hl == "en")
                    discoveryCalls.incrementAndGet()
                    Result.success(listOf(audioSongs[MV_IDS.indexOf(video.id)]))
                },
                mainSongReference = { source, requested ->
                    check(requested.hl == "en" && source in AUDIO_IDS)
                    referenceCalls.incrementAndGet()
                    if (failReferenceFetch) Result.failure(IOException("Synthetic music-card request failure"))
                    else Result.success(if (referenceCardMissing) null else MainSongReference(source, referenceTarget(source)))
                },
                main = { id, requested ->
                    check(requested.hl == "en")
                    mainCalls.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                    if (id in AUDIO_IDS) {
                        if (failSourceFetch) Result.failure(IOException("Synthetic source request failure"))
                        else {
                            val title = sourceOriginalTitles[AUDIO_IDS.indexOf(id)]
                            Result.success(ArtTrackOriginalMetadata(id, title, "Nirvana - Topic", ARTIST_ID,
                                "Provided to YouTube by Fixture Records\n\n$title · Nirvana\n\n$ENGLISH_ALBUM\n\n" +
                                    "℗ Fixture Records\n\nAuto-generated by YouTube."))
                        }
                    } else {
                        check(id in MV_IDS)
                        Result.success(ArtTrackOriginalMetadata(id, ENGLISH_TITLES[MV_IDS.indexOf(id)],
                            "Nirvana", ARTIST_ID, "Official music video"))
                    }
                },
                // The production resolver assesses source originals; no fixed English verdicts.
            )).also { it.start() }
        }

        suspend fun restart() {
            job.cancelAndJoin()
            database.close()
            database = openDatabase()
            job = SupervisorJob()
            names.value = emptyMap()
            start()
        }

        suspend fun rawSongs() = MV_IDS.map { database.song(it).first()!!.song }

        fun storedSongIds(): Set<String> = database.openHelper.readableDatabase.query("SELECT id FROM song").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

        fun referenceRows() = database.metadataNameSnapshot().filter {
            it.language == "und" && it.targetId in MV_IDS && it.source.startsWith("main-song-reference:")
        }

        fun mainCount(id: String) = mainCalls[id]?.get() ?: 0

        suspend fun assertStoredIdentity(rawBefore: List<SongEntity>, albumBefore: AlbumEntity?, additionalIds: Set<String> = emptySet()) {
            assertEquals(rawBefore, rawSongs())
            assertEquals(albumBefore, database.albumById(ALBUM_ID))
            assertEquals(MV_IDS, database.albumSongs(ALBUM_ID).first().map { it.id })
            assertEquals(listOf(ARTIST_ID), database.albumArtistIdsForAlbum(ALBUM_ID))
            MV_IDS.forEach { assertEquals(listOf(ARTIST_ID), database.artistIdsForSong(it)) }
            val queue = database.readQueue().single()
            assertEquals(MV_IDS, queue.queue.map { it.id })
            assertEquals(1, queue.queuePos)
            assertEquals(MV_IDS.toSet() + additionalIds, storedSongIds())
            AUDIO_IDS.forEach { assertFalse(database.songExists(it)) }
        }

        suspend fun awaitNames(expected: List<String>) {
            withTimeout(20_000) { names.first { selected -> MV_IDS.indices.all { selected[target(MV_IDS[it])] == expected[it] } } }
        }

        suspend fun awaitAlbumState(status: String) = awaitCondition {
            database.metadataFetch("ALBUM", ALBUM_ID, "und", albumOriginalContextKey(CONTEXT_KEY))?.status == status
        }

        suspend fun awaitIdle() = awaitCondition { repository.pendingRequestCount == 0 }

        suspend fun awaitIdleAfterReference() {
            awaitCondition { referenceCalls.get() >= AUDIO_IDS.size }
            awaitIdle()
        }

        suspend fun awaitCondition(predicate: () -> Boolean) {
            withTimeout(20_000) { while (!predicate()) delay(10) }
        }

        suspend fun close() {
            job.cancelAndJoin()
            database.close()
            context.deleteDatabase(databaseName)
        }

        private fun song(id: String, title: String, type: String) = SongItem(id, title,
            listOf(Artist("Nirvana", ARTIST_ID)), album = Album(ENGLISH_ALBUM, ALBUM_ID), duration = 180, thumbnail = "",
            endpoint = WatchEndpoint(videoId = id, watchEndpointMusicSupportedConfigs =
                WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig(type))))
    }

    companion object {
        private const val ALBUM_ID = "MPREb_jPOYfjGgApr"
        private const val ARTIST_ID = "UCprovider-reference-nirvana"
        private const val ENGLISH_ALBUM = "Nevermind"
        private const val JAPANESE_ALBUM = "ネヴァーマインド"
        private const val CONTEXT_KEY = "JP:provider-reference-fixture"
        private val MV_IDS = listOf("hTWKbfoikeg", "musicvid002", "musicvid003")
        private val AUDIO_IDS = listOf("ljUtuoFt-8c", "provider002", "provider003")
        private val ENGLISH_TITLES = listOf("Smells Like Teen Spirit", "Come As You Are", "Something In The Way")
        private val JAPANESE_TITLES = listOf("スメルズ・ライク・ティーン・スピリット", "カム・アズ・ユー・アー", "サムシング・イン・ザ・ウェイ")
        private fun target(id: String) = OriginalNameTarget(OriginalNameKind.SONG, id)
        private fun preferences(preferOriginal: Boolean): Preferences = preferencesOf(
            ContentCountryKey to "JP", ContentLanguageKey to "ja", PreferEnglishOriginalKey to preferOriginal,
        )
    }
}
