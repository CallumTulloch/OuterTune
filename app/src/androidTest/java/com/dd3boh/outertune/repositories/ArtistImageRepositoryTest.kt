package com.dd3boh.outertune.repositories

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.MetadataFetchEntity
import com.dd3boh.outertune.db.entities.MetadataNameEntity
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.YouTubeLocale
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Real Room and isolated preferences; every network response is supplied by the test. */
class ArtistImageRepositoryTest {
    private val id = "UC-image-profile"
    private val image = "https://example.invalid/profile"
    private fun profile(thumbnail: String? = image, artistId: String = id) =
        ArtistItem(artistId, "Remote name", thumbnail, shuffleEndpoint = null, radioEndpoint = null)

    @Test fun laterSavedArtistIsObservedWithoutOpeningLibraryAndSharesTheRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<ArtistItem>>()
        val calls = AtomicInteger()
        val fixture = Fixture { _, _ -> calls.incrementAndGet(); started.complete(Unit); reply.await() }
        try {
            // A search already populated the independent name cache before the artist was saved.
            fixture.database.recordMetadataNames(listOf(MetadataNameEntity("ARTIST", id, "en", "Name", "detail")),
                MetadataFetchEntity("ARTIST", id, "en", MetadataFetchEntity.SUCCESS, fixture.now.get(), "JP:test"))
            val repository = fixture.repository()
            fixture.database.insert(ArtistEntity("LA-image-profile", "Saved name", onlineId = id))
            withTimeout(15_000) { started.await() }
            repeat(8) { repository.refreshSavedArtists() }
            reply.complete(Result.success(profile()))
            fixture.awaitIdle(repository)
            assertEquals(1, calls.get())
            val saved = fixture.database.artistByOnlineId(id)!!
            assertEquals("LA-image-profile", saved.id)
            assertEquals("Saved name", saved.name)
            assertEquals(image, saved.thumbnailUrl)
            assertEquals(MetadataFetchEntity.SUCCESS, fixture.database.metadataFetch("ARTIST", id, "en", "JP:test")!!.status)
            // A new incomplete row/state must not be held back by an old success-only retry cache.
            fixture.database.update(saved.copy(thumbnailUrl = null))
            withTimeout(15_000) { fixture.database.artist(saved.id).first { it?.artist?.thumbnailUrl == image } }
            fixture.awaitIdle(repository)
            assertEquals(2, calls.get())
        } finally { fixture.close() }
    }

    @Test fun failureAndEmptyReplyKeepTheImageAndRetryDeadlinesSurviveRepositoryRestart() = runBlocking {
        val response = AtomicReference<Result<ArtistItem>>(Result.failure(IOException("offline")))
        val calls = AtomicInteger()
        val fixture = Fixture { _, _ -> calls.incrementAndGet(); response.get() }
        try {
            val oldTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(fixture.now.get() - IMAGE_REFRESH_MS), ZoneId.systemDefault())
            fixture.database.insert(ArtistEntity(id, "Saved", thumbnailUrl = image, lastUpdateTime = oldTime))
            var repository = fixture.repository()
            repository.refreshSavedArtists()
            fixture.awaitIdle(repository)
            assertEquals(1, calls.get())
            assertEquals(image, fixture.database.artistById(id)!!.thumbnailUrl)
            repository = fixture.repository() // Simulated restart with the same Room/preferences.
            repository.refreshSavedArtists()
            fixture.awaitIdle(repository)
            assertEquals(1, calls.get())
            fixture.now.addAndGet(IMAGE_FAILURE_RETRY_MS + 1)
            response.set(Result.success(profile(null)))
            repository.refreshSavedArtists()
            fixture.awaitIdle(repository)
            assertEquals(2, calls.get())
            assertEquals(image, fixture.database.artistById(id)!!.thumbnailUrl)
            assertEquals(oldTime, fixture.database.artistById(id)!!.lastUpdateTime)
            repository = fixture.repository()
            repository.refreshSavedArtists()
            fixture.awaitIdle(repository)
            assertEquals(2, calls.get())
            fixture.now.addAndGet(IMAGE_EMPTY_RETRY_MS + 1)
            response.set(Result.success(profile("https://example.invalid/refreshed")))
            repository.refreshSavedArtists()
            fixture.awaitIdle(repository)
            assertEquals(3, calls.get())
            assertEquals("https://example.invalid/refreshed", fixture.database.artistById(id)!!.thumbnailUrl)
        } finally { fixture.close() }
    }

    @Test fun obsoleteAccountReplyIsDiscardedBeforeTheNewContextIsFetched() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val firstReply = CompletableDeferred<Result<ArtistItem>>()
        val secondReply = CompletableDeferred<Result<ArtistItem>>()
        val calls = AtomicInteger()
        val fixture = Fixture { _, _ ->
            if (calls.incrementAndGet() == 1) { firstStarted.complete(Unit); firstReply.await() }
            else { secondStarted.complete(Unit); secondReply.await() }
        }
        try {
            fixture.database.insert(ArtistEntity(id, "Saved"))
            val repository = fixture.repository()
            withTimeout(15_000) { firstStarted.await() }
            fixture.contextKey.set("JP:changed-account")
            firstReply.complete(Result.success(profile("https://example.invalid/obsolete")))
            withTimeout(15_000) { secondStarted.await() }
            assertNull(fixture.database.artistById(id)!!.thumbnailUrl)
            secondReply.complete(Result.success(profile()))
            fixture.awaitIdle(repository)
            assertEquals(image, fixture.database.artistById(id)!!.thumbnailUrl)
            assertEquals(2, calls.get())
        } finally { fixture.close() }
    }

    @Test fun wrongIdResponseCannotFillEitherArtistsImage() = runBlocking {
        val fixture = Fixture { _, _ -> Result.success(profile(artistId = "UC-unrelated")) }
        try {
            fixture.database.insert(ArtistEntity(id, "Saved"))
            val repository = fixture.repository()
            repository.refreshSavedArtists()
            fixture.awaitIdle(repository)
            assertNull(fixture.database.artistById(id)!!.thumbnailUrl)
            assertNull(fixture.database.artistById("UC-unrelated"))
        } finally { fixture.close() }
    }

    private class Fixture(private val fetch: suspend (String, YouTubeLocale) -> Result<ArtistItem>) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferenceName = "artist-image-repository-test-${UUID.randomUUID()}"
        private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        private val isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(internal)
        val now = AtomicLong(1_789_000_000_000L)
        val contextKey = AtomicReference("JP:test")
        private var job: Job? = null

        suspend fun repository(): ArtistImageRepository {
            job?.cancelAndJoin()
            val newJob = SupervisorJob().also { job = it }
            return ArtistImageRepository(database, isolatedContext, ArtistImageRepository.Runtime(
                scope = CoroutineScope(newJob + Dispatchers.IO), now = now::get,
                locale = { YouTubeLocale(gl = "JP", hl = "en") },
                contextKey = { contextKey.get() }, fetch = fetch,
            )).also { it.start() }
        }

        suspend fun awaitIdle(repository: ArtistImageRepository) = withTimeout(15_000) {
            while (repository.pendingRequestCount != 0) delay(10)
        }

        suspend fun close() {
            job?.cancelAndJoin()
            database.close()
            context.deleteSharedPreferences(preferenceName)
        }
    }
}
