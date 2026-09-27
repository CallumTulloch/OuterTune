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
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.SongAlbumMap
import com.dd3boh.outertune.db.entities.SongEntity
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
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.IOException
import java.time.LocalDateTime
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
import org.junit.Assert.*
import org.junit.Test

/** Real Room, repository workers and language model; every remote boundary is a local fixture. */
class AlbumOriginalRecoveryTest {
    @Test
    fun openingAnUnsavedAlbumRecoversAllOriginalsWhenQueueIsEmptyAndSwitchesBackToJapanese(): Unit = runBlocking {
        withFixture { f ->
            f.start()
            f.emit("album")
            f.awaitNames(f.englishTitles)
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.assertEnglishAssessments()
            f.awaitIdle()

            assertTrue("This response deliberately has no per-track ATV endpoint", f.englishSongs.all { it.endpoint == null })
            assertTrue("The ordinary detail path must have received EMPTY", f.queueCalls.get() > 0)
            assertEquals(1, f.albumCalls.get())
            assertEquals(listOf(1, 1, 1), f.ids.map(f::mainCount))
            assertNull("Viewing an album must not bookmark or create its library row", f.database.albumById(ALBUM_ID))
            assertEquals(0, f.songRowCount())
            assertEquals(f.japaneseTitles, f.japaneseSongs.map { it.title })
            assertEquals(f.ids, f.japaneseSongs.map { it.id })

            f.settings.value = preferences(preferOriginal = false)
            f.awaitNames(f.japaneseTitles)
            f.ids.forEachIndexed { index, id ->
                val aliases = f.aliases.value[target(id)].orEmpty()
                assertTrue(aliases.contains(f.englishTitles[index]))
                assertTrue(aliases.contains(f.japaneseTitles[index]))
            }
            f.assertEnglishAssessments() // OFF changes selection, not the acquired evidence.
        }
    }

    @Test
    fun savedAlbumRetriesPastLegacySuccessAndIndividualEmptyCachesWithoutChangingRawSongs(): Unit = runBlocking {
        withFixture { f ->
            f.seedSavedAlbum()
            val rawBefore = f.rawSongs()
            val oldContext = "album-original-context:$CONTEXT_KEY"
            f.database.recordMetadataFetch(MetadataFetchEntity("ALBUM", ALBUM_ID, "und",
                MetadataFetchEntity.SUCCESS, f.clock.get(), oldContext))
            f.ids.forEach { id ->
                // A previous build's 24-hour EMPTY is now eligible after five minutes.
                f.database.recordMetadataFetch(MetadataFetchEntity("SONG", id, "und",
                    MetadataFetchEntity.EMPTY, f.clock.get() - 6 * 60_000L, originalMetadataContextKey(f.locale)))
            }

            f.start()
            f.awaitNames(f.englishTitles)
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.assertEnglishAssessments()

            assertNotEquals("The changed acquisition route needs a new cache key", oldContext, albumOriginalContextKey(CONTEXT_KEY))
            assertEquals(MetadataFetchEntity.SUCCESS,
                f.database.metadataFetch("ALBUM", ALBUM_ID, "und", oldContext)!!.status)
            assertEquals(1, f.albumCalls.get())
            assertEquals(listOf(1, 1, 1), f.ids.map(f::mainCount))
            assertEquals(rawBefore, f.rawSongs())
            assertEquals(f.ids, f.database.albumSongs(ALBUM_ID).first().map { it.id })
            assertNotNull(f.database.albumById(ALBUM_ID)!!.bookmarkedAt)
        }
    }

    @Test
    fun partialMainFailureRetriesAfterFiveMinutesAndKeepsAlreadyAcquiredOriginals(): Unit = runBlocking {
        withFixture { f ->
            f.seedSavedAlbum()
            val failingId = f.ids.last()
            f.mainResponse = { id, attempt ->
                if (id == failingId && attempt == 1) Result.failure(IOException("One temporary Main failure"))
                else Result.success(f.mainMetadata(id))
            }
            f.start()
            f.awaitAlbumState(MetadataFetchEntity.FAILED)
            f.awaitIdle()
            val originalsBefore = f.originalSongRows().associateBy { it.targetId }
            assertEquals(f.ids.dropLast(1).toSet(), originalsBefore.keys)
            assertEquals(listOf(1, 1, 1), f.ids.map(f::mainCount))

            f.clock.addAndGet(5 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.awaitNames(f.englishTitles)
            f.awaitIdle()
            f.assertEnglishAssessments()

            assertEquals(2, f.albumCalls.get())
            assertEquals(listOf(1, 1, 2), f.ids.map(f::mainCount))
            val originalsAfter = f.originalSongRows().associateBy { it.targetId }
            originalsBefore.forEach { (id, before) ->
                val after = originalsAfter.getValue(id)
                assertEquals(before.observedAt, after.observedAt)
                assertEquals(originalCandidate(before), originalCandidate(after))
            }
            assertEquals(f.japaneseTitles, f.rawSongs().map { it!!.title })
        }
    }

    @Test
    fun anotherVideoAndUnstructuredMainMetadataAreRejectedWithoutChangingJapaneseDisplay(): Unit = runBlocking {
        withFixture { f ->
            f.mainResponse = { id, _ -> Result.success(when (id) {
                f.ids.first() -> f.mainMetadata(id).copy(videoId = "another0001")
                else -> f.mainMetadata(id).copy(shortDescription = "An unofficial upload of ${f.mainMetadata(id).title}")
            }) }
            f.start()
            f.emit("album")
            f.awaitAlbumState(MetadataFetchEntity.FAILED)
            f.awaitNames(f.japaneseTitles)
            f.awaitIdle()

            assertTrue(f.originalSongRows().isEmpty())
            assertTrue(originalAssessmentsByTarget(f.database.metadataNameSnapshot())
                .keys.none { it.kind == OriginalNameKind.SONG })
            assertEquals(listOf(1, 1, 1), f.ids.map(f::mainCount))
            assertEquals(0, f.songRowCount())
            assertEquals(f.japaneseTitles, f.japaneseSongs.map { it.title })
        }
    }

    @Test
    fun anotherEditionInMainRecoversSongNamesWithoutInventingAnAlbumName(): Unit = runBlocking {
        withFixture { f ->
            val differentEdition = "$ENGLISH_ALBUM (Original Edition)"
            f.mainResponse = { id, _ -> Result.success(f.mainMetadata(id, differentEdition)) }
            f.start()
            f.emit("album")
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.awaitNames(f.englishTitles)
            f.assertEnglishAssessments()
            f.awaitIdle()

            assertTrue(f.originalSongRows().all { originalCandidate(it)?.albumId == ALBUM_ID })
            val albumNames = f.database.metadataNames("ALBUM", ALBUM_ID)
            assertTrue("Main's edition cannot establish the page's album name", albumNames.none { originalCandidate(it) != null })
            assertTrue(albumNames.none { it.name == differentEdition })
            assertEquals(JAPANESE_ALBUM, f.names.value[OriginalNameTarget(OriginalNameKind.ALBUM, ALBUM_ID)])
            assertEquals(f.ids, f.englishSongs.map { it.id })
        }
    }

    @Test
    fun openingAnotherEditionWithTheSameVideosRetainsTheFirstAlbumsVerifiedOriginals(): Unit = runBlocking {
        withFixture { f ->
            f.start()
            f.emit("album")
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.awaitNames(f.englishTitles)
            f.assertEnglishAssessments()
            val firstAlbum = OriginalNameTarget(OriginalNameKind.ALBUM, ALBUM_ID)
            f.awaitCondition { f.names.value[firstAlbum] == ENGLISH_ALBUM }
            f.awaitIdle()
            val originalsBefore = latestOriginalRows(f.database.metadataNameSnapshot())
                .filter { it.kind in setOf("SONG", "ALBUM") }
            assertEquals(f.ids.size, originalsBefore.count { it.kind == "ALBUM" && it.targetId == ALBUM_ID })
            assertTrue(originalsBefore.all { originalCandidate(it)?.albumId == ALBUM_ID })

            // The same videos are also listed by a second edition, but Main names the first one.
            f.clock.incrementAndGet()
            f.emit("album", SECOND_ALBUM_ID)
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS, SECOND_ALBUM_ID)
            f.awaitIdle()
            f.awaitNames(f.englishTitles)

            assertEquals(2, f.albumCalls.get())
            assertEquals("A second edition must reuse the verified Main snapshot", listOf(1, 1, 1), f.ids.map(f::mainCount))
            assertEquals(originalsBefore, latestOriginalRows(f.database.metadataNameSnapshot())
                .filter { it.kind in setOf("SONG", "ALBUM") })
            assertEquals(ENGLISH_ALBUM, f.names.value[firstAlbum])
            assertTrue(originalAssessmentsByTarget(f.database.metadataNameSnapshot()).getValue(firstAlbum)
                .all { it.language == OriginalNameLanguage.ENGLISH })
            val secondAlbum = OriginalNameTarget(OriginalNameKind.ALBUM, SECOND_ALBUM_ID)
            f.awaitCondition { f.names.value[secondAlbum] == JAPANESE_SECOND_ALBUM }
            assertTrue(f.database.metadataNames("ALBUM", SECOND_ALBUM_ID).none { originalCandidate(it) != null })
            assertEquals(0, f.songRowCount())
        }
    }

    @Test
    fun refreshingExpiredSharedSourcesFromAnotherEditionKeepsTheFirstAlbumPublication(): Unit = runBlocking {
        withFixture { f ->
            val firstAlbum = OriginalNameTarget(OriginalNameKind.ALBUM, ALBUM_ID)
            f.start()
            f.emit("album")
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.awaitNames(f.englishTitles)
            f.awaitCondition { f.names.value[firstAlbum] == ENGLISH_ALBUM }
            f.awaitIdle()
            val before = latestOriginalRows(f.database.metadataNameSnapshot()).mapNotNull(::originalCandidate).toSet()

            // Unlike the still-fresh edition test, force Main to be read through B while its
            // complete description still names A. No A refresh may conceal a withdrawal bug.
            val renewedAt = f.clock.addAndGet(7 * 24 * 60 * 60_000L + 1)
            f.database.recordMetadataFetch(MetadataFetchEntity("ALBUM", ALBUM_ID, "und",
                MetadataFetchEntity.SUCCESS, renewedAt, albumOriginalContextKey(CONTEXT_KEY)))
            f.emit("album", SECOND_ALBUM_ID)
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS, SECOND_ALBUM_ID)
            f.awaitIdle()
            f.awaitCondition {
                f.database.metadataOriginalPublicationSnapshot().singleOrNull {
                    it.kind == "ALBUM" && it.targetId == ALBUM_ID
                }?.let { it.evaluatedAt >= renewedAt && it.englishName == ENGLISH_ALBUM } == true
            }

            assertEquals(listOf(2, 2, 2), f.ids.map(f::mainCount))
            val renewed = latestOriginalRows(f.database.metadataNameSnapshot())
            assertEquals(before, renewed.mapNotNull(::originalCandidate).toSet())
            assertTrue(renewed.all { it.observedAt >= renewedAt })
            assertEquals(ENGLISH_ALBUM, f.names.value[firstAlbum])
            assertTrue(originalAssessmentsByTarget(f.database.metadataNameSnapshot()).getValue(firstAlbum)
                .all { it.language == OriginalNameLanguage.ENGLISH })
            assertTrue(f.database.metadataNames("ALBUM", SECOND_ALBUM_ID).none { originalCandidate(it) != null })
            assertEquals(0, f.songRowCount())
        }
    }

    @Test
    fun contextlessMainSuccessDoesNotSuppressShortTitleAlbumRecoveryForSevenDays(): Unit = runBlocking {
        withFixture(includeShortTitles = true) { f ->
            f.seedContextlessOriginals()
            assertTrue(f.originalSongRows().all { originalCandidate(it)?.albumId == null })
            f.start()
            f.emit("album")
            f.awaitAlbumState(MetadataFetchEntity.SUCCESS)
            f.awaitNames(f.englishTitles)
            f.assertEnglishAssessments()
            f.awaitIdle()

            assertEquals(1, f.albumCalls.get())
            assertEquals(List(f.ids.size) { 1 }, f.ids.map(f::mainCount))
            assertTrue(f.originalSongRows().all { originalCandidate(it)?.albumId == ALBUM_ID })
            val assessments = originalAssessmentsByTarget(f.database.metadataNameSnapshot())
            assertTrue("At least one short title must be resolved by the acquired album context",
                f.ids.takeLast(3).any { id -> assessments.getValue(target(id)).any { it.method.contains("album-context:") } })
            assertEquals(0, f.songRowCount())
        }
    }

    @Test
    fun aSearchResultAndUnsavedStubDoNotExpandTheAlbumsTracks(): Unit = runBlocking {
        withFixture { f ->
            f.database.insert(AlbumEntity(ALBUM_ID, title = JAPANESE_ALBUM, songCount = 0, duration = 0))
            assertFalse(f.database.isAlbumOriginalContextEligible(ALBUM_ID))
            f.start()
            f.emit("searchSummary")
            f.awaitNames(f.japaneseTitles)
            f.awaitCondition {
                f.ids.all { id -> listOf("en", "ja").all { language ->
                    f.database.metadataFetch("SONG", id, language, CONTEXT_KEY)?.status == MetadataFetchEntity.EMPTY
                } }
            }
            f.awaitIdle()

            assertEquals(0, f.albumCalls.get())
            assertEquals(listOf(0, 0, 0), f.ids.map(f::mainCount))
            assertTrue(f.originalSongRows().isEmpty())
            assertEquals(0, f.songRowCount())
            assertFalse(f.database.isAlbumOriginalContextEligible(ALBUM_ID))
        }
    }

    private suspend fun withFixture(includeShortTitles: Boolean = false, block: suspend (Fixture) -> Unit) {
        val fixture = Fixture(includeShortTitles)
        try { block(fixture) } finally { fixture.close() }
    }

    private class Fixture(includeShortTitles: Boolean) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(internal)
        private val job = SupervisorJob()
        val locale = YouTubeLocale("JP", "ja")
        private val locales = MutableStateFlow(locale)
        private val auth = MutableStateFlow(0L)
        val settings = MutableStateFlow(preferences(preferOriginal = true))
        val clock = AtomicLong(1_800_000_000_000L)
        val names = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val aliases = MutableStateFlow<Map<OriginalNameTarget, List<String>>>(emptyMap())
        val englishTitles = listOf("I Will Always Remember You", "There Is A Light That Never Goes Out",
            "We Are Never Ever Getting Back Together") + if (includeShortTitles) listOf("Breed", "Lithium", "Polly") else emptyList()
        val japaneseTitles = listOf("アイ・ウィル・オールウェイズ・リメンバー・ユー", "ゼア・イズ・ア・ライト・ザット・ネヴァー・ゴーズ・アウト",
            "ウィー・アー・ネヴァー・エヴァー・ゲッティング・バック・トゥゲザー") + if (includeShortTitles) listOf("ブリード", "リチウム", "ポーリー") else emptyList()
        val ids = englishTitles.indices.map { "recovery${(it + 1).toString().padStart(3, '0')}" }
        val englishSongs = ids.mapIndexed { index, id -> SongItem(id, englishTitles[index],
            listOf(Artist(ARTIST_NAME, ARTIST_ID)), album = Album(ENGLISH_ALBUM, ALBUM_ID),
            duration = 180, thumbnail = "", endpoint = null) }
        val japaneseSongs = englishSongs.mapIndexed { index, song -> song.copy(title = japaneseTitles[index],
            album = Album(JAPANESE_ALBUM, ALBUM_ID)) }
        val albumCalls = AtomicInteger()
        val queueCalls = AtomicInteger()
        private val mainCalls = ConcurrentHashMap<String, AtomicInteger>()
        var mainResponse: (String, Int) -> Result<ArtTrackOriginalMetadata> = { id, _ -> Result.success(mainMetadata(id)) }
        private lateinit var observer: (List<YTItem>, YouTubeLocale, String) -> Unit
        lateinit var repository: MetadataNameRepository
            private set

        fun start() {
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), preferences = settings,
                locale = { locale }, localeUpdates = locales, authRevision = { auth.value }, authUpdates = auth,
                now = clock::get, contextKey = { CONTEXT_KEY }, observeMetadata = { observer = it },
                publishNames = { selected, all -> aliases.value = all; names.value = selected },
                queue = { _, _ -> queueCalls.incrementAndGet(); Result.success(emptyList()) },
                album = { id, requested -> Result.success(albumItem(requested.hl, id)) },
                artist = { id, requested -> Result.success(ArtistItem(id,
                    if (requested.hl == "en") ARTIST_NAME else "リカバリー・アーティスト",
                    null, shuffleEndpoint = null, radioEndpoint = null)) },
                albumContext = { id, requested ->
                    check(requested.hl == "en")
                    val album = albumItem("en", id)
                    albumCalls.incrementAndGet()
                    Result.success(englishSongs.map { it.copy(album = Album(album.title, id)) })
                },
                main = { id, requested ->
                    check(requested.hl == "en")
                    mainResponse(id, mainCalls.getOrPut(id) { AtomicInteger() }.incrementAndGet())
                },
                albumSongSources = { _, _ -> Result.success(emptyList()) },
                mainSongReference = { _, _ -> Result.success(null) },
                // assessOriginals intentionally uses the production classifier, not fixed verdicts.
            ))
            repository.start()
        }

        fun emit(source: String, albumId: String = ALBUM_ID) {
            val album = albumItem("ja", albumId)
            observer(listOf(album) + japaneseSongs.map { it.copy(album = Album(album.title, albumId)) }, locale, source)
        }

        fun seedSavedAlbum() {
            database.insert(AlbumEntity(ALBUM_ID, title = JAPANESE_ALBUM, songCount = ids.size, duration = 540,
                bookmarkedAt = LocalDateTime.of(2026, 9, 19, 0, 0)))
            ids.forEachIndexed { index, id ->
                database.insert(SongEntity(id, japaneseTitles[index], duration = 180, localPath = null,
                    albumId = ALBUM_ID, albumName = JAPANESE_ALBUM))
                database.insert(SongAlbumMap(id, ALBUM_ID, index))
                database.recordMetadataNames(listOf(MetadataNameEntity("SONG", id, "ja", japaneseTitles[index],
                    "album", 50, clock.get())))
            }
        }

        fun seedContextlessOriginals() {
            ids.forEachIndexed { index, id ->
                val candidate = ArtTrackOriginalName(target(id), englishTitles[index], id, albumId = null)
                database.recordMetadataNames(listOf(
                    MetadataNameEntity("SONG", id, "en", englishTitles[index], "detail", 100, clock.get()),
                    MetadataNameEntity("SONG", id, "ja", japaneseTitles[index], "detail", 100, clock.get()),
                    MetadataNameEntity("SONG", id, "und", englishTitles[index], ORIGINAL_NAME_SOURCE_PREFIX + id,
                        10, clock.get(), ArtTrackOriginalNameCodec.encode(candidate)),
                ), MetadataFetchEntity("SONG", id, "und", MetadataFetchEntity.SUCCESS,
                    clock.get(), originalMetadataContextKey(locale)))
            }
        }

        fun mainMetadata(id: String, albumName: String = ENGLISH_ALBUM): ArtTrackOriginalMetadata {
            val title = englishSongs.single { it.id == id }.title
            return ArtTrackOriginalMetadata(id, title, "$ARTIST_NAME - Topic", ARTIST_ID,
                "Provided to YouTube by Recovery Records\n\n$title · $ARTIST_NAME\n\n$albumName\n\n" +
                    "℗ 2026 Recovery Records\n\nAuto-generated by YouTube.")
        }

        private fun albumItem(language: String, albumId: String = ALBUM_ID): AlbumItem {
            check(albumId in setOf(ALBUM_ID, SECOND_ALBUM_ID))
            val title = if (albumId == SECOND_ALBUM_ID) {
                if (language == "en") "$ENGLISH_ALBUM (Deluxe Edition)" else JAPANESE_SECOND_ALBUM
            } else {
                if (language == "en") ENGLISH_ALBUM else JAPANESE_ALBUM
            }
            return AlbumItem(albumId, "OLAK-recovery-$albumId", title = title,
                artists = listOf(Artist(ARTIST_NAME, ARTIST_ID)), thumbnail = "")
        }

        fun mainCount(id: String): Int = mainCalls[id]?.get() ?: 0

        fun originalSongRows(): List<MetadataNameEntity> = database.metadataNameSnapshot().filter {
            it.kind == "SONG" && it.targetId in ids && it.language == "und" && originalCandidate(it) != null
        }

        suspend fun rawSongs(): List<SongEntity?> = ids.map { database.song(it).first()?.song }

        fun songRowCount(): Int = database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM song").use {
            check(it.moveToFirst()); it.getInt(0)
        }

        suspend fun awaitNames(expected: List<String>) {
            withTimeout(15_000) { names.first { selected -> ids.indices.all { selected[target(ids[it])] == expected[it] } } }
        }

        suspend fun awaitAlbumState(status: String, albumId: String = ALBUM_ID) = awaitCondition {
            database.metadataFetch("ALBUM", albumId, "und", albumOriginalContextKey(CONTEXT_KEY))?.status == status
        }

        suspend fun awaitIdle() = awaitCondition { repository.pendingRequestCount == 0 }

        suspend fun awaitCondition(predicate: () -> Boolean) {
            withTimeout(15_000) { while (!predicate()) delay(10) }
        }

        suspend fun assertEnglishAssessments() {
            awaitCondition {
                val assessments = originalAssessmentsByTarget(database.metadataNameSnapshot())
                ids.all { id -> assessments[target(id)].orEmpty().any { it.language == OriginalNameLanguage.ENGLISH } }
            }
            assertEquals(ids.toSet(), originalSongRows().map { it.targetId }.toSet())
            ids.forEachIndexed { index, id ->
                val assessment = originalAssessmentsByTarget(database.metadataNameSnapshot()).getValue(target(id)).single()
                assertEquals(id, assessment.sourceVideoId)
                assertEquals(englishTitles[index], assessment.originalName)
            }
        }

        suspend fun close() { job.cancelAndJoin(); database.close() }
    }

    companion object {
        private const val ALBUM_ID = "MPRE-album-original-recovery"
        private const val SECOND_ALBUM_ID = "MPRE-album-original-recovery-deluxe"
        private const val ARTIST_ID = "UCalbum-original-recovery"
        private const val ARTIST_NAME = "Recovery Artist"
        private const val ENGLISH_ALBUM = "A Collection Of Songs We Will Always Remember"
        private const val JAPANESE_ALBUM = "ア・コレクション・オブ・ソングス"
        private const val JAPANESE_SECOND_ALBUM = "ア・コレクション・オブ・ソングス（デラックス版）"
        private const val CONTEXT_KEY = "JP:album-original-recovery"
        private fun target(id: String) = OriginalNameTarget(OriginalNameKind.SONG, id)
        private fun preferences(preferOriginal: Boolean): Preferences = preferencesOf(
            ContentCountryKey to "JP", ContentLanguageKey to "ja", PreferEnglishOriginalKey to preferOriginal,
        )
    }
}
