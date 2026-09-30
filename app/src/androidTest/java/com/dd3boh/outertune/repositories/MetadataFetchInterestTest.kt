package com.dd3boh.outertune.repositories

import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.ContentCountryKey
import com.dd3boh.outertune.constants.ContentLanguageKey
import com.dd3boh.outertune.constants.PreferEnglishOriginalKey
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.AlbumPage
import java.io.IOException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room scheduling: observing a card is different from requesting its remote details. */
class MetadataFetchInterestTest {
    @Test
    fun unselectedSearchAndRecommendationPagesPublishNamesWithoutFetchingTheirContents() = runBlocking {
        withFixture { fixture ->
            fixture.start()
            val songs = (0 until 120).map { fixture.song("unselected-$it", "ja") }
            songs.chunked(40).zip(listOf("search", "next", "home")).forEach { (page, source) ->
                fixture.observe(page, source)
            }
            fixture.awaitNames(songs)
            fixture.awaitIdle()

            assertEquals(360, fixture.database.allMetadataTargets().size)
            assertTrue("Observed cards must not create detail requests: ${fixture.calls}", fixture.calls.isEmpty())
            songs.forEach { song ->
                assertEquals(song.title, fixture.database.metadataNames("SONG", song.id).single().name)
                assertTrue(fixture.database.metadataFetchStates("SONG", song.id).isEmpty())
            }

            // A refresh event must not promote the retained name cache into saved interests.
            fixture.repository.refreshTargets()
            fixture.awaitIdle()
            assertTrue(fixture.calls.isEmpty())
        }
    }

    @Test
    fun aSavedSongStillResolvesItsDirectAlbumAndArtistInBothLanguages() = runBlocking {
        withFixture { fixture ->
            val saved = fixture.song("saved", "ja")
            fixture.database.insert(SongEntity(saved.id, "Stored title", localPath = null, inLibrary = SAVED_AT))
            fixture.start()
            val unrelated = fixture.song("recommendation", "ja")
            fixture.observe(listOf(saved, unrelated), "search")
            fixture.awaitDetails(saved)
            fixture.awaitIdle()

            val allowed = setOf(saved.id, saved.album!!.id, saved.artists.single().id!!)
            assertTrue("Only the saved root and its direct identities may be fetched: ${fixture.calls}",
                fixture.calls.all { it.id in allowed })
            for (language in listOf("en", "ja")) {
                assertTrue(fixture.calls.contains(Call("queue", saved.id, language)))
                assertTrue(fixture.calls.contains(Call("album", saved.album!!.id, language)))
                assertTrue(fixture.calls.contains(Call("artist", saved.artists.single().id!!, language)))
            }
            assertNull(fixture.database.albumById(saved.album!!.id))
            assertNull(fixture.database.artistById(saved.artists.single().id!!))
            assertEquals("Stored title", fixture.database.songForArtistCredit(saved.id)!!.title)
            assertTrue(fixture.database.metadataFetchStates("SONG", unrelated.id).isEmpty())
            assertEquals(unrelated.title, fixture.database.metadataNames("SONG", unrelated.id).single().name)
        }
    }

    @Test
    fun savingAnObservedSongStartsDetailsWithoutAnotherObservationOrRetryDelay() = runBlocking {
        withFixture { fixture ->
            fixture.start()
            val song = fixture.song("newly-saved", "ja")
            fixture.observe(listOf(song), "searchSuggestions")
            fixture.awaitNames(listOf(song))
            fixture.awaitIdle()
            assertTrue(fixture.calls.isEmpty())
            assertTrue("Skipping an unselected card is not a failed or empty provider response",
                fixture.database.metadataFetchStates("SONG", song.id).isEmpty())

            fixture.database.insert(SongEntity(song.id, song.title, localPath = null, inLibrary = SAVED_AT))
            fixture.awaitDetails(song)
            fixture.awaitIdle()
            for (language in listOf("en", "ja")) {
                assertEquals(MetadataFetchEntity.SUCCESS,
                    fixture.database.metadataFetch("SONG", song.id, language, CONTEXT)?.status)
            }
            assertTrue(fixture.calls.any { it.operation == "queue" && it.id == song.id })
        }
    }

    @Test
    fun observingASavedSongRetriesExpiredRelatedHeadersWhileItsOwnDetailsAreStillFresh() = runBlocking {
        withFixture { fixture ->
            val saved = fixture.song("retry-related", "ja")
            fixture.database.insert(SongEntity(saved.id, saved.title, localPath = null, inLibrary = SAVED_AT))
            fixture.failRelatedHeaders = true
            fixture.start()
            val related = listOf("ALBUM" to saved.album!!.id, "ARTIST" to saved.artists.single().id!!)
            fixture.await {
                related.all { (kind, id) -> listOf("en", "ja").all { language ->
                    fixture.database.metadataFetch(kind, id, language, CONTEXT)?.status == MetadataFetchEntity.FAILED
                } }
            }
            fixture.awaitIdle()
            val queueCalls = fixture.calls.count { it.operation == "queue" }
            val initialCalls = fixture.calls.size
            fixture.clock.addAndGet(metadataRetryDelay(MetadataFetchEntity.FAILED) + 1)
            fixture.failRelatedHeaders = false

            // The song's seven-day success cannot discover/retry these unpersisted child rows.
            fixture.repository.refreshTargets()
            fixture.awaitIdle()
            assertEquals(initialCalls, fixture.calls.size)
            fixture.observe(listOf(saved, fixture.song("unrelated-retry", "ja")), "search")
            fixture.awaitDetails(saved)
            fixture.awaitIdle()

            assertEquals("Fresh parent details must retain their existing success TTL",
                queueCalls, fixture.calls.count { it.operation == "queue" })
            related.forEach { (kind, id) ->
                for (language in listOf("en", "ja")) {
                    assertEquals(MetadataFetchEntity.SUCCESS,
                        fixture.database.metadataFetch(kind, id, language, CONTEXT)?.status)
                }
            }
            assertTrue(fixture.calls.none { it.id.contains("unrelated-retry") })
            assertNull(fixture.database.albumById(saved.album!!.id))
            assertNull(fixture.database.artistById(saved.artists.single().id!!))
        }
    }

    @Test
    fun playbackAndAnExplicitForegroundAlbumActivatePreviouslyUnselectedCards() = runBlocking {
        withFixture { fixture ->
            fixture.start()
            val playing = fixture.song("playing", "ja")
            val albumId = "MPRE-foreground"
            val albumTrack = fixture.albumSong(albumId, "ja")
            // Programmatic album responses alone must not act as an explicit screen visit.
            fixture.observe(listOf(fixture.album(albumId, "ja"), albumTrack), "album")
            fixture.observe(listOf(playing), "next")
            fixture.awaitNames(listOf(playing, albumTrack))
            fixture.awaitIdle()
            assertTrue(fixture.calls.isEmpty())

            fixture.repository.setPlayingSong(playing.id)
            fixture.awaitDetails(playing)
            fixture.awaitIdle()
            assertTrue(fixture.calls.none { it.operation == "page" || it.id == albumId })

            fixture.repository.setForegroundAlbum(albumId, true)
            fixture.await {
                fixture.calls.contains(Call("page", albumId, "en")) &&
                    fixture.calls.contains(Call("main", albumTrack.id, "en"))
            }
            fixture.awaitIdle()
            assertTrue(fixture.database.metadataNames("SONG", albumTrack.id)
                .any { it.language == "en" && it.source == "album-original-context" })
            assertNull(fixture.database.songForArtistCredit(playing.id))
            assertNull(fixture.database.albumById(albumId))
        }
    }

    @Test
    fun anAlbumResponseBeforeForegroundRestoresTrackInterestEvenWhenTheAlbumFetchesAreFresh() = runBlocking {
        withFixture { fixture ->
            val albumId = "MPRE-before-foreground"
            val track = fixture.albumSong(albumId, "ja")
            for (language in listOf("en", "ja")) {
                fixture.database.recordMetadataFetch(MetadataFetchEntity("ALBUM", albumId, language,
                    MetadataFetchEntity.SUCCESS, NOW, CONTEXT))
                fixture.database.recordMetadataFetch(MetadataFetchEntity("SONG", track.id, language,
                    MetadataFetchEntity.FAILED, NOW - metadataRetryDelay(MetadataFetchEntity.FAILED) - 1, CONTEXT))
            }
            fixture.database.recordMetadataFetch(MetadataFetchEntity("ALBUM", albumId, "und",
                MetadataFetchEntity.SUCCESS, NOW, albumOriginalContextKey(CONTEXT)))
            fixture.start()
            fixture.observe(listOf(fixture.album(albumId, "ja"), track), "album")
            fixture.awaitNames(listOf(track))
            fixture.awaitIdle()
            assertTrue("A response alone must remain passive", fixture.calls.isEmpty())

            // No second provider-observer packet arrives after the screen becomes foreground.
            fixture.repository.setForegroundAlbum(albumId, true)
            fixture.awaitDetails(track)
            fixture.awaitIdle()

            for (language in listOf("en", "ja")) {
                assertTrue(fixture.calls.contains(Call("queue", track.id, language)))
            }
            assertTrue("Restoring known track interest must preserve the album success TTL",
                fixture.calls.none { it.operation == "page" || (it.operation == "album" && it.id == albumId) })
            assertEquals(NOW, fixture.database.metadataFetch("ALBUM", albumId, "und",
                albumOriginalContextKey(CONTEXT))!!.updatedAt)
            assertNull(fixture.database.albumById(albumId))
            assertNull(fixture.database.songForArtistCredit(track.id))
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }

    private data class Call(val operation: String, val id: String, val language: String)

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val room = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(room)
        private val job = SupervisorJob()
        private val locale = YouTubeLocale("JP", "ja")
        private val locales = MutableStateFlow(locale)
        private val auth = MutableStateFlow(0L)
        private val albumTrackParents = ConcurrentHashMap<String, String>()
        private val preferences = MutableStateFlow(preferencesOf(ContentCountryKey to "JP",
            ContentLanguageKey to "ja", PreferEnglishOriginalKey to true))
        val names = MutableStateFlow<Map<OriginalNameTarget, String>>(emptyMap())
        val calls = CopyOnWriteArrayList<Call>()
        val clock = AtomicLong(NOW)
        @Volatile var failRelatedHeaders = false
        private lateinit var observer: (List<YTItem>, YouTubeLocale, String) -> Unit
        lateinit var repository: MetadataNameRepository
            private set

        suspend fun start() {
            repository = MetadataNameRepository(database, context, MetadataNameRepository.Runtime(
                scope = CoroutineScope(job + Dispatchers.IO), preferences = preferences,
                locale = { locale }, localeUpdates = locales, authRevision = { auth.value }, authUpdates = auth,
                now = clock::get, contextKey = { CONTEXT }, observeMetadata = { observer = it },
                publishNames = { selected, _ -> names.value = selected },
                queue = { ids, requested ->
                    ids.forEach { calls += Call("queue", it, requested.hl) }
                    Result.success(ids.map { id ->
                        val item = song(id, requested.hl)
                        albumTrackParents[id]?.let { parent ->
                            item.copy(album = Album(albumName(parent, requested.hl), parent))
                        } ?: item
                    })
                },
                album = { id, requested ->
                    calls += Call("album", id, requested.hl)
                    if (failRelatedHeaders) Result.failure(IOException("Related header temporarily unavailable"))
                    else Result.success(album(id, requested.hl))
                },
                artist = { id, requested ->
                    calls += Call("artist", id, requested.hl)
                    if (failRelatedHeaders) Result.failure(IOException("Related header temporarily unavailable"))
                    else Result.success(ArtistItem(id, artistName(id, requested.hl), null,
                        shuffleEndpoint = null, radioEndpoint = null))
                },
                albumPage = { id, requested ->
                    calls += Call("page", id, requested.hl)
                    Result.success(AlbumPage(album(id, requested.hl), listOf(albumSong(id, requested.hl)), emptyList()))
                },
                albumContext = { _, _ -> error("The complete album-page fixture must be used") },
                main = { id, requested ->
                    calls += Call("main", id, requested.hl)
                    Result.success(ArtTrackOriginalMetadata(id, "A Song We Remember", "Fixture Artist - Topic", "UCfixture",
                        "Provided to YouTube by Fixture Records\n\nA Song We Remember · Fixture Artist\n\n" +
                            "The Album We Remember\n\n℗ 2026 Fixture Records\n\nAuto-generated by YouTube."))
                },
                albumSongSources = { _, _ -> Result.success(emptyList()) },
                mainSongReference = { _, _ -> Result.success(null) },
                assessOriginals = { _, _ -> emptyList() },
            ))
            repository.start()
            withTimeout(WAIT_MS) { repository.initialized.first { it } }
        }

        fun song(id: String, language: String) = SongItem(id, if (language == "ja") "曲名 $id" else "Song $id",
            listOf(Artist(artistName("UC-$id", language), "UC-$id")),
            Album(albumName("MPRE-$id", language), "MPRE-$id"), duration = 180, thumbnail = "")

        fun album(id: String, language: String) = AlbumItem(id, "playlist-$id", title = albumName(id, language),
            artists = emptyList(), thumbnail = "")

        fun albumSong(albumId: String, language: String): SongItem {
            val id = "foreground-track"
            albumTrackParents[id] = albumId
            return song(id, language).copy(album = Album(albumName(albumId, language), albumId))
        }

        fun observe(items: List<YTItem>, source: String) = observer(items, locale, source)

        suspend fun awaitNames(songs: List<SongItem>) {
            val expected = buildMap {
                songs.forEach { song ->
                    put(OriginalNameTarget(OriginalNameKind.SONG, song.id), song.title)
                    song.album?.let { put(OriginalNameTarget(OriginalNameKind.ALBUM, it.id), it.name) }
                    song.artists.forEach { artist ->
                        artist.id?.let { put(OriginalNameTarget(OriginalNameKind.ARTIST, it), artist.name) }
                    }
                }
            }
            withTimeout(WAIT_MS) { names.first { actual -> expected.all { actual[it.key] == it.value } } }
        }

        suspend fun awaitDetails(song: SongItem) = await {
            val targets = listOf("SONG" to song.id, "ALBUM" to song.album!!.id, "ARTIST" to song.artists.single().id!!)
            targets.all { (kind, id) -> listOf("en", "ja").all { language ->
                database.metadataFetch(kind, id, language, CONTEXT)?.status == MetadataFetchEntity.SUCCESS
            } }
        }

        suspend fun await(predicate: () -> Boolean) {
            withTimeout(WAIT_MS) { while (!predicate()) delay(10) }
        }

        suspend fun awaitIdle() {
            withTimeout(WAIT_MS) {
                var quietChecks = 0
                while (quietChecks < 10) {
                    quietChecks = if (repository.pendingRequestCount == 0) quietChecks + 1 else 0
                    delay(10)
                }
            }
        }

        suspend fun close() { job.cancelAndJoin(); database.close() }

        private fun albumName(id: String, language: String) = if (language == "ja") "アルバム $id" else "Album $id"
        private fun artistName(id: String, language: String) = if (language == "ja") "アーティスト $id" else "Artist $id"
    }

    companion object {
        private const val CONTEXT = "JP:interest-test"
        private const val NOW = 1_790_700_000_000L
        private const val WAIT_MS = 15_000L
        private val SAVED_AT = LocalDateTime.of(2026, 9, 30, 12, 0)
    }
}
