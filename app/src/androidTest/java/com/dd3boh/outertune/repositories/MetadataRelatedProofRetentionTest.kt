package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.metadata.OriginalAlbumLanguageResolver
import com.dd3boh.outertune.models.metadata.OriginalNameAssessment
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameLanguage
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.IOException
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class MetadataRelatedProofRetentionTest {
    @Test fun completeMainWithMissingMusicIdentitiesKeepsRelatedPublicationsUntilIdentityRecovery(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.awaitNames(ORIGINAL_TITLE)
            f.awaitIdle()
            val before = latestOriginalRows(f.database.metadataNameSnapshot()).toSet()
            f.providerTitle = CHANGED_TITLE
            val missingIdentities: List<(Fixture) -> Unit> = listOf(
                { it.musicArtists = emptyList() },
                { it.musicArtists = listOf(Artist(ARTIST_NAME, null)) },
                { it.musicArtists = listOf(Artist("", ARTIST.id)) },
                { it.omitMusicAlbum = true },
                { it.invalidMusicAlbum = true },
            )
            missingIdentities.forEachIndexed { index, makeIncomplete ->
                f.musicArtists = null
                f.omitMusicAlbum = false
                f.invalidMusicAlbum = false
                makeIncomplete(f)
                f.frames.clear()
                val previousCalls = f.mainCalls.get()
                val previousEnglishQueueCalls = f.englishQueueCalls.get()
                val refreshedAt = f.clock.addAndGet(if (index == 0) 7 * 24 * 60 * 60_000L + 1 else 5 * 60_000L + 1)
                f.repository.refreshTargets()
                f.awaitCondition("missing Music identity case $index") {
                    f.mainCalls.get() > previousCalls && f.sourceFetch()?.let {
                        it.status == MetadataFetchEntity.EMPTY && it.updatedAt == refreshedAt
                    } == true
                }
                f.awaitIdle()
                assertTrue("Each incomplete Music response must be re-fetched after the five-minute retry: case $index",
                    f.englishQueueCalls.get() > previousEnglishQueueCalls)
                f.publicationBarrier()
                assertEquals(before, latestOriginalRows(f.database.metadataNameSnapshot()).toSet())
                assertTrue("Missing Music identities must not withdraw a complete prior source snapshot",
                    f.frames.isNotEmpty() && f.frames.all { it[SONG] == ORIGINAL_TITLE &&
                        it[ARTIST] == ARTIST_NAME && it[ALBUM] == ALBUM_NAME })
            }

            f.musicArtists = null
            f.omitMusicAlbum = false
            f.invalidMusicAlbum = false
            val previousEnglishQueueCalls = f.englishQueueCalls.get()
            f.clock.addAndGet(5 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitNames(CHANGED_TITLE)
            f.awaitIdle()
            assertTrue("Recovery must fetch Music identities again instead of waiting for their seven-day detail TTL",
                f.englishQueueCalls.get() > previousEnglishQueueCalls)
            assertEquals(CHANGED_TITLE, latestOriginalRows(f.database.metadataNameSnapshot()).single { it.kind == "SONG" }.name)
            assertEquals(ORIGINAL_TITLE, f.database.song(SONG.id).first()!!.song.title)
        } finally { f.close() }
    }

    @Test fun normalQueueRefreshWithIncompleteMainDescriptionKeepsRelatedProofUntilACompleteReplacement(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.awaitNames(ORIGINAL_TITLE)
            f.awaitIdle()
            val before = latestOriginalRows(f.database.metadataNameSnapshot()).toSet()
            assertEquals(setOf("SONG", "ARTIST", "ALBUM"), before.map { it.kind }.toSet())
            assertEquals(0, f.albumContextCalls.get())

            // Exercise the ordinary queue -> Main parser, which accepts a song title even when
            // the optional distributor fields are absent. They cannot withdraw old related proof.
            f.providerTitle = CHANGED_TITLE
            f.completeDescription = false
            val incomplete = listOf(null, "", "An ordinary unstructured description",
                "Provided to YouTube by Fixture Records\n\n$CHANGED_TITLE · $ARTIST_NAME\n\n$ALBUM_NAME")
            incomplete.forEachIndexed { index, description ->
                f.description = description
                f.frames.clear()
                val previousCalls = f.mainCalls.get()
                val refreshedAt = f.clock.addAndGet(if (index == 0) 7 * 24 * 60 * 60_000L + 1 else 5 * 60_000L + 1)
                f.repository.refreshTargets()
                f.awaitCondition {
                    f.mainCalls.get() > previousCalls && f.sourceFetch()?.let {
                        it.status == MetadataFetchEntity.EMPTY && it.updatedAt == refreshedAt
                    } == true
                }
                f.awaitIdle()
                f.publicationBarrier()
                assertEquals(before, latestOriginalRows(f.database.metadataNameSnapshot()).toSet())
                assertTrue("Incomplete Main response must preserve song, artist and album names in every frame",
                    f.frames.isNotEmpty() && f.frames.all { it[SONG] == ORIGINAL_TITLE &&
                        it[ARTIST] == ARTIST_NAME && it[ALBUM] == ALBUM_NAME })
                assertEquals(0, f.albumContextCalls.get())
            }

            // A genuinely complete new distributor original must still replace the held title.
            f.completeDescription = true
            val completedAt = f.clock.addAndGet(5 * 60_000L + 1)
            f.repository.refreshTargets()
            f.awaitNames(CHANGED_TITLE)
            f.awaitCondition { f.sourceFetch()?.let {
                it.status == MetadataFetchEntity.SUCCESS && it.updatedAt == completedAt
            } == true }
            val after = latestOriginalRows(f.database.metadataNameSnapshot())
            assertEquals(setOf("SONG", "ARTIST", "ALBUM"), after.map { it.kind }.toSet())
            assertEquals(CHANGED_TITLE, after.single { it.kind == "SONG" }.name)
            assertEquals(ORIGINAL_TITLE, f.database.song(SONG.id).first()!!.song.title)
            assertTrue(f.database.metadataNames("SONG", SONG.id).any { it.name == ORIGINAL_TITLE })
        } finally { f.close() }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "metadata-related-proof-${UUID.randomUUID()}.db"
        val database = MusicDatabase(Room.databaseBuilder(context, InternalDatabase::class.java, name).build())
        private val job = SupervisorJob()
        private val locale = YouTubeLocale("JP", "ja")
        val clock = AtomicLong(1_840_000_000_000L)
        val mainCalls = AtomicInteger()
        val englishQueueCalls = AtomicInteger()
        val albumContextCalls = AtomicInteger()
        val published = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val frames = CopyOnWriteArrayList<Map<OriginalNameTarget, String>>()
        private val barriers = AtomicInteger()
        @Volatile var providerTitle = ORIGINAL_TITLE
        @Volatile var completeDescription = true
        @Volatile var description: String? = null
        @Volatile var musicArtists: List<Artist>? = null
        @Volatile var omitMusicAlbum = false
        @Volatile var invalidMusicAlbum = false
        lateinit var repository: MetadataNameRepository
            private set

        init {
            // This regression renews a saved song after its TTL; browsing history is no longer
            // periodically fetched merely because a playback row happens to exist.
            database.insert(SongEntity(SONG.id, ORIGINAL_TITLE, duration = 220, localPath = null,
                inLibrary = LocalDateTime.of(2026, 9, 27, 0, 0)))
            database.recordMetadataNames(listOf(SONG to ORIGINAL_TITLE, ARTIST to ARTIST_NAME, ALBUM to ALBUM_NAME).flatMap { (target, title) ->
                listOf(MetadataNameEntity(target.kind.name, target.id, "en", title, "detail", 100, clock.get()),
                    MetadataNameEntity(target.kind.name, target.id, "ja", "日本語の${target.kind.name}", "detail", 100, clock.get()))
            })
        }

        fun start() {
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO),
                locale = { locale }, localeUpdates = MutableStateFlow(locale),
                authRevision = { 0L }, authUpdates = MutableStateFlow(0L),
                preferences = MutableStateFlow(preferencesOf(PreferEnglishOriginalKey to true)),
                now = clock::get, contextKey = { "JP:related-proof" }, observeMetadata = {},
                publishNames = { names, _ -> frames += names.toMap(); published.value = names },
                queue = { ids, request ->
                    if (SONG.id in ids && request.hl == "en") englishQueueCalls.incrementAndGet()
                    Result.success(ids.filter { it == SONG.id }.map {
                        SongItem(it, if (request.hl == "en") providerTitle else "日本語のSONG",
                            musicArtists ?: listOf(Artist(if (request.hl == "en") ARTIST_NAME else "日本語のARTIST", ARTIST.id)),
                            if (omitMusicAlbum) null else Album(if (request.hl == "en") ALBUM_NAME else "日本語のALBUM",
                                if (invalidMusicAlbum) "" else ALBUM.id),
                            duration = 220, thumbnail = "", endpoint = WatchEndpoint(videoId = it,
                                watchEndpointMusicSupportedConfigs = WatchEndpoint.WatchEndpointMusicSupportedConfigs(
                                    WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig("MUSIC_VIDEO_TYPE_ATV"))))
                    })
                },
                album = { id, request -> Result.success(AlbumItem(id, null,
                    title = if (request.hl == "en") ALBUM_NAME else "日本語のALBUM", artists = emptyList(), thumbnail = "")) },
                artist = { id, request -> Result.success(ArtistItem(id,
                    if (request.hl == "en") ARTIST_NAME else "日本語のARTIST", null, shuffleEndpoint = null, radioEndpoint = null)) },
                albumContext = { _, _ ->
                    albumContextCalls.incrementAndGet()
                    Result.failure(IOException("This regression must use normal queue acquisition"))
                },
                mainSongReference = { _, _ -> Result.success(null) },
                albumSongSources = { _, _ -> Result.success(emptyList()) },
                main = { id, _ ->
                    mainCalls.incrementAndGet()
                    val text = if (completeDescription)
                        "Provided to YouTube by Fixture Records\n\n$providerTitle · $ARTIST_NAME\n\n$ALBUM_NAME\n\n" +
                            "℗ Fixture Records\n\nAuto-generated by YouTube." else description
                    Result.success(ArtTrackOriginalMetadata(id, providerTitle, "$ARTIST_NAME - Topic", ARTIST.id, text))
                },
                assessOriginals = { originals, at -> originals.map { candidate ->
                    OriginalNameAssessment(candidate.target, candidate.name, candidate.sourceVideoId,
                        "https://www.youtube.com/watch?v=${candidate.sourceVideoId}", OriginalNameLanguage.ENGLISH, 0.99f,
                        OriginalAlbumLanguageResolver.METHOD_VERSION + "/related-proof-fixture", "fixture-${candidate.name}", at)
                } },
            )).also { it.start() }
        }

        fun sourceFetch() = database.metadataFetch("SONG", SONG.id, "und", originalMetadataContextKey(locale))
        suspend fun awaitNames(title: String) = awaitCondition("published title: $title") {
            published.value.let { it[SONG] == title && it[ARTIST] == ARTIST_NAME && it[ALBUM] == ALBUM_NAME }
        }
        suspend fun awaitIdle() = awaitCondition("all requests completed") { repository.pendingRequestCount == 0 }
        suspend fun awaitCondition(stage: String = "source observation", predicate: () -> Boolean) {
            val completed = withTimeoutOrNull(20_000) {
                while (!predicate()) delay(10)
                true
            } == true
            assertTrue("Timed out at $stage; mainCalls=${mainCalls.get()}, englishQueueCalls=${englishQueueCalls.get()}, " +
                "sourceFetch=${sourceFetch()}, pending=${repository.pendingRequestCount}, published=${published.value}", completed)
        }
        suspend fun publicationBarrier() {
            val ordinal = barriers.incrementAndGet()
            val target = OriginalNameTarget(OriginalNameKind.SONG, "related-proof-barrier-$ordinal")
            database.recordMetadataNames(listOf(MetadataNameEntity("SONG", target.id, "ja", "Barrier $ordinal",
                "manual", 100, clock.get())))
            withTimeout(20_000) { published.first { it[target] == "Barrier $ordinal" } }
        }
        suspend fun close() {
            job.cancelAndJoin()
            database.close()
            context.deleteDatabase(name)
        }
    }

    companion object {
        private val SONG = OriginalNameTarget(OriginalNameKind.SONG, "related0001")
        private val ARTIST = OriginalNameTarget(OriginalNameKind.ARTIST, "UCrelatedProofFixture")
        private val ALBUM = OriginalNameTarget(OriginalNameKind.ALBUM, "MPRErelatedProofFixture")
        private const val ORIGINAL_TITLE = "Original Song Name"
        private const val CHANGED_TITLE = "Changed Song Name"
        private const val ARTIST_NAME = "Fixture Artist"
        private const val ALBUM_NAME = "Fixture Album"
    }
}
