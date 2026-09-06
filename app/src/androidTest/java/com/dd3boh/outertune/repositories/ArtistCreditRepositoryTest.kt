package com.dd3boh.outertune.repositories

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.models.toStoredJson
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditResolution
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.SongItem
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** No live requests: deferred replies exercise the production repository against real Room/preferences. */
class ArtistCreditRepositoryTest {
    private val videoId = "repository-credit-test-track"
    private val raw = ArtistCredit("Alpha & Beta", emptyList(), ArtistCreditStatus.RAW, "search", "ja")
    private val complete = raw.copy(status = ArtistCreditStatus.COMPLETE, source = "structured-track",
        artists = listOf(Artist("Alpha", "UC-repository-alpha"), Artist("Beta", "UC-repository-beta")))

    private fun song(credit: ArtistCredit = raw, id: String = videoId) = SongItem(
        id = id, title = "Repository test track", artists = credit.artists, duration = 180,
        thumbnail = "https://example.invalid/cover", artistCredit = credit,
    )

    @Test
    fun concurrentRequestsForTheSameSongShareOneFetch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<ArtistCredit>>()
        val calls = AtomicInteger()
        val fixture = Fixture {
            calls.incrementAndGet()
            started.complete(Unit)
            reply.await()
        }
        try {
            val repository = fixture.repository()
            repository.request(song(), priority = true)
            withTimeout(15_000) { started.await() }
            repeat(10) { repository.request(song(), priority = it % 2 == 0) }
            reply.complete(Result.success(complete))
            fixture.awaitIdle()

            assertEquals(1, calls.get())
            assertEquals(ArtistCreditStatus.COMPLETE, repository.observe(videoId).value!!.status)
            assertEquals(complete.artists.map { it.id }, repository.observe(videoId).value!!.artists.map { it.id })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun delayedThinResponseCannotUndoNewlyAdoptedPeople() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<ArtistCredit>>()
        val calls = AtomicInteger()
        val fixture = Fixture {
            calls.incrementAndGet()
            started.complete(Unit)
            reply.await()
        }
        try {
            fixture.database.insert(song().toMediaMetadata())
            val repository = fixture.repository()
            repository.request(song(), priority = true)
            withTimeout(15_000) { started.await() }
            // A second source has already supplied a complete byline while the first reply is pending.
            repository.request(song(complete), priority = true)
            assertEquals(ArtistCreditStatus.COMPLETE, repository.withCredit(song()).artistCredit!!.status)
            reply.complete(Result.success(raw.copy(source = "slow-response")))
            fixture.awaitIdle()

            assertEquals(1, calls.get())
            val shown = repository.observe(videoId).value!!
            val saved = fixture.database.artistCredit(videoId).first()!!
            assertEquals(ArtistCreditStatus.COMPLETE, shown.status)
            assertEquals(complete.artists.map { it.name to it.id }, shown.artists.map { it.name to it.id })
            assertEquals(shown, saved)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unsavedSearchResultStaysUnsavedAndPersistentWarmCacheRepairsLaterSave() = runBlocking {
        val calls = AtomicInteger()
        val fixture = Fixture { calls.incrementAndGet(); Result.success(complete) }
        try {
            val repository = fixture.repository()
            repository.request(song(), priority = true)
            fixture.awaitIdle()
            assertEquals(1, calls.get())
            assertFalse(fixture.database.songExists(videoId))
            assertTrue(fixture.database.artistsBySource(false).isEmpty())

            // The user later saves/queues a thinner result. A new repository must restore the
            // persistent cache before considering another request, without adding library flags.
            fixture.database.insert(song().toMediaMetadata())
            val restoredRepository = fixture.repository()
            restoredRepository.request(song(), priority = true)
            fixture.awaitIdle()
            assertEquals(1, calls.get())
            val restored = fixture.database.artistCredit(videoId).first()!!
            assertEquals(ArtistCreditStatus.COMPLETE, restored.status)
            assertEquals(complete.artists.map { it.id }, restored.artists.map { it.id })
            assertEquals(2, fixture.database.artistIdsForSong(videoId).size)
            val savedSong = fixture.database.songForArtistCredit(videoId)!!
            assertNull(savedSong.inLibrary)
            assertFalse(savedSong.liked)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun oldResponsesAreDiscardedAfterLanguageOrAccountChanges() = runBlocking {
        for (next in listOf(Session("test:en:guest", "en"), Session("test:ja:signed-in", "ja"))) {
            val oldStarted = CompletableDeferred<Unit>()
            val newStarted = CompletableDeferred<Unit>()
            val oldReply = CompletableDeferred<Result<ArtistCredit>>()
            val newReply = CompletableDeferred<Result<ArtistCredit>>()
            val calls = AtomicInteger()
            val fixture = Fixture {
                if (calls.incrementAndGet() == 1) {
                    oldStarted.complete(Unit)
                    oldReply.await()
                } else {
                    newStarted.complete(Unit)
                    newReply.await()
                }
            }
            try {
                val repository = fixture.repository()
                val oldState = repository.observe(videoId)
                repository.request(song(), priority = true)
                withTimeout(15_000) { oldStarted.await() }
                fixture.session.set(next)
                val newRaw = raw.copy(rawText = "New account artist", language = next.language)
                val newCredit = newRaw.copy(status = ArtistCreditStatus.COMPLETE,
                    artists = listOf(Artist("New account artist", "UC-new-context")))
                val newState = repository.observe(videoId)
                assertNull(newState.value)
                repository.request(song(newRaw), priority = true)
                withTimeout(15_000) { newStarted.await() }
                newReply.complete(Result.success(newCredit))
                withTimeout(15_000) { newState.filterNotNull().first { it.status == ArtistCreditStatus.COMPLETE } }
                oldReply.complete(Result.success(complete))
                fixture.awaitIdle()

                assertEquals(2, calls.get())
                assertEquals(listOf("UC-new-context"), repository.observe(videoId).value!!.artists.map { it.id })
                assertEquals(ArtistCreditStatus.RAW, oldState.value!!.status)
                assertTrue(oldState.value!!.artists.isEmpty())
                assertFalse(fixture.database.songExists(videoId))
                // Stale results must not quietly enter the old persistent cache either.
                fixture.session.set(Session("test:ja:guest", "ja"))
                val reopenedOldContext = fixture.repository().observe(videoId).value!!
                assertEquals(ArtistCreditStatus.RAW, reopenedOldContext.status)
                assertTrue(reopenedOldContext.artists.isEmpty())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun synchronousCacheReadPreservesRicherSourceAndStoredCanonicalReferences() = runBlocking {
        val fixture = Fixture { error("Synchronous rendering must not fetch") }
        try {
            fixture.cache(videoId, raw)
            val repository = fixture.repository()
            val richSong = song(complete)
            assertEquals(ArtistCreditStatus.COMPLETE, repository.withCredit(richSong).artistCredit!!.status)
            val metadata = repository.withCredit(richSong.toMediaMetadata())
            assertEquals(complete.artists.map { it.name to it.id }, metadata.artists.map { it.name to it.onlineId })

            val canonicalVideo = "repository-canonical-credit-track"
            val canonical = complete.copy(artists = complete.artists.mapIndexed { index, artist ->
                artist.copy(ref = "LA-existing-$index")
            })
            fixture.cache(canonicalVideo, canonical)
            val restored = repository.withCredit(song(complete, canonicalVideo).toMediaMetadata())
            assertEquals(listOf("LA-existing-0", "LA-existing-1"), restored.artists.map { it.id })
            assertFalse(fixture.database.songExists(canonicalVideo))
        } finally {
            fixture.close()
        }
    }

    private data class Session(val token: String, val language: String)

    @Test
    fun missingAlbumArrivesWithoutChangingArtistsAndSurvivesLaterSaveAndRestart() = runBlocking {
        val expected = Album("Original album", "MPRE-repository-album")
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<ArtistCredit>>()
        val fixture = Fixture(expected) { started.complete(Unit); reply.await() }
        try {
            val repository = fixture.repository()
            val track = song(complete) // Even fully identified names do not prove the album is present.
            val albumState = repository.observeAlbum(videoId)
            repository.request(track, priority = true)
            withTimeout(15_000) { started.await() }
            assertNull(albumState.value)
            reply.complete(Result.success(complete))
            fixture.awaitIdle()
            assertEquals(expected, albumState.value)
            assertEquals(expected, repository.withCredit(track).album)
            assertEquals(complete.artists.map { it.name }, repository.withCredit(track).artists.map { it.name })
            assertFalse(fixture.database.songExists(videoId))
            fixture.database.insert(track.toMediaMetadata())
            val restarted = fixture.repository()
            assertEquals(expected, restarted.withCredit(track).album)
            restarted.request(track, priority = true)
            fixture.awaitIdle()
            val saved = fixture.database.song(videoId).first()!!.song
            assertEquals(expected.id, saved.albumId)
            assertEquals(expected.name, saved.albumName)
            val original = Album("Already known", "MPRE-already-known")
            assertEquals(original, restarted.withCredit(track.copy(album = original)).album)
        } finally { fixture.close() }
    }

    private class Fixture(
        resolvedAlbum: Album? = Album("Fixture album", "MPRE-fixture"),
        fetch: suspend (SongItem) -> Result<ArtistCredit>,
    ) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferenceName = "artist-credit-repository-test-${UUID.randomUUID()}"
        private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        private val isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(internal)
        val session = AtomicReference(Session("test:ja:guest", "ja"))
        private val job = SupervisorJob()
        private val runtime = ArtistCreditRepository.Runtime(
            scope = CoroutineScope(job + Dispatchers.IO),
            fetch = { song -> fetch(song).map { ArtistCreditResolution(it, resolvedAlbum) } },
            contextToken = { session.get().token },
            language = { session.get().language },
            now = { 1_000_000L },
        )

        fun repository() = ArtistCreditRepository(database, isolatedContext, runtime)

        fun cache(videoId: String, credit: ArtistCredit) {
            assertTrue(preferences.edit().putString("${session.get().token}:$videoId", credit.toStoredJson()).commit())
        }

        suspend fun awaitIdle() = withTimeout(15_000) { job.children.toList().joinAll() }

        suspend fun close() {
            job.cancelAndJoin()
            internal.close()
            context.deleteSharedPreferences(preferenceName)
        }
    }
}
