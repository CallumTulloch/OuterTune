package com.dd3boh.outertune.repositories

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.db.InternalDatabase
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.ArtistIdentity
import com.dd3boh.outertune.models.toMediaMetadata
import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditResolution
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.SongItem
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Deferred replies and real Room/preferences verify provenance without live requests. */
class ChannelArtistCreditRepositoryTest {
    private val channelId = "UCPCiIrrrNJOKvi_5vr3G6PA"
    private val channel = ArtistCredit("Same name",
        listOf(Artist("Same name", null, sourceChannelId = channelId)),
        ArtistCreditStatus.COMPLETE, "channel-byline", "ja",
        listOf("channel-byline:fixture", "video-source:MUSIC_VIDEO_TYPE_OMV"))
    private val performer = ArtistCredit("Same name", listOf(Artist("Same name", channelId)),
        ArtistCreditStatus.COMPLETE, "structured-byline", "ja")
    private fun song(id: String, credit: ArtistCredit = channel) = SongItem(
        id, "Track $id", credit.artists, album = Album("Album", "MPRE-channel-repository"),
        duration = 180, thumbnail = "https://example.invalid/cover", artistCredit = credit)

    @Test fun sameNamePerformerUpgradeRemovesOnlyItsVideoFromTheSharedChannelContext() = runBlocking {
        val fixture = Fixture { error("Complete channel and performer credits must not fetch") }
        try {
            val repository = fixture.repository()
            val one = song("channel-context-one")
            val two = song("channel-context-two")
            fixture.database.insert(one.toMediaMetadata())
            fixture.database.insert(two.toMediaMetadata())
            repository.request(one, priority = true)
            repository.request(two, priority = true)
            fixture.awaitIdle()

            val sourceId = ArtistIdentity.channelId(channelId, one.id, channel.rawText)
            val channelContext = repository.artistContext(sourceId)
            assertEquals(setOf(one.id, two.id), channelContext.value!!.sourceSongs.map { it.id }.toSet())
            assertTrue(channelContext.value!!.isChannel)
            assertNull(channelContext.value!!.onlineId)

            repository.request(song(one.id, performer), priority = true)
            fixture.awaitIdle()

            assertEquals(listOf(two.id), channelContext.value!!.sourceSongs.map { it.id })
            assertTrue(channelContext.value!!.isChannel)
            assertEquals(channelId, channelContext.value!!.sourceChannelId)
            assertNull(channelContext.value!!.onlineId)
            val performerContext = repository.artistContext(channelId).value!!
            assertFalse(performerContext.isChannel)
            assertEquals(channelId, performerContext.onlineId)
            assertEquals(listOf(one.id), performerContext.sourceSongs.map { it.id })
            assertEquals(listOf(channelId), fixture.database.artistIdsForSong(one.id))
            assertEquals(listOf(sourceId), fixture.database.artistIdsForSong(two.id))
            assertTrue(fixture.database.artistCredit(two.id).first()!!.artists.single().isChannel)
            assertFalse(fixture.database.artistCredit(one.id).first()!!.artists.single().isChannel)
        } finally { fixture.close() }
    }

    @Test fun aDelayedUploaderReplyCannotReintroduceTheChannelAfterAnExplicitPerformerWasAdopted() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Result<ArtistCreditResolution>>()
        val calls = AtomicInteger()
        val fixture = Fixture {
            calls.incrementAndGet()
            started.complete(Unit)
            reply.await()
        }
        try {
            val id = "late-channel-credit"
            val raw = ArtistCredit("Same name", emptyList(), ArtistCreditStatus.RAW, "search", "ja")
            val initial = song(id, raw)
            fixture.database.insert(initial.toMediaMetadata())
            val repository = fixture.repository()
            repository.request(initial, priority = true)
            withTimeout(15_000) { started.await() }
            repository.request(song(id, performer), priority = true)
            reply.complete(Result.success(ArtistCreditResolution(channel, initial.album)))
            fixture.awaitIdle()

            assertEquals(1, calls.get())
            val accepted = repository.observe(id).value!!
            assertEquals(performer.artists.map { it.name to it.id }, accepted.artists.map { it.name to it.id })
            assertFalse(accepted.artists.single().isChannel)
            assertNull(accepted.artists.single().sourceChannelId)
            assertEquals(listOf(channelId), fixture.database.artistIdsForSong(id))
            assertEquals(accepted, fixture.database.artistCredit(id).first())
            assertNull(repository.artistContext(ArtistIdentity.channelId(channelId, id, channel.rawText)).value)
        } finally { fixture.close() }
    }

    @Test fun thinnerSavedAndCachedRepliesRetainChannelIdentityWithoutAnAutomaticLookup() = runBlocking {
        val calls = AtomicInteger()
        val fixture = Fixture { calls.incrementAndGet(); error("Channel sources must not resolve online by name") }
        try {
            val original = song("restored-channel-credit")
            fixture.database.insert(original.toMediaMetadata())
            val repository = fixture.repository()
            repository.request(original, priority = true)
            fixture.awaitIdle()
            val adopted = repository.observe(original.id).value!!
            val thinner = original.copy(artists = emptyList(), artistCredit = ArtistCredit(
                "", emptyList(), ArtistCreditStatus.RAW, "failed-request", "ja"))

            val reopened = fixture.repository()
            reopened.request(thinner, priority = true)
            fixture.awaitIdle()
            assertEquals(0, calls.get())
            assertEquals(adopted, reopened.observe(original.id).value)
            assertEquals(adopted, fixture.database.artistCredit(original.id).first())
            val artist = reopened.withCredit(thinner).toMediaMetadata().artists.single()
            assertTrue(artist.isChannel)
            assertEquals(channelId, artist.sourceChannelId)
            assertNull(artist.onlineId)
            assertEquals(ArtistIdentity.channelId(channelId, original.id, channel.rawText), artist.id)
        } finally { fixture.close() }
    }

    private class Fixture(fetch: suspend (SongItem) -> Result<ArtistCreditResolution>) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val preferenceName = "channel-artist-repository-${UUID.randomUUID()}"
        private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
        private val isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).build()
        val database = MusicDatabase(internal)
        private val job = SupervisorJob()
        private val runtime = ArtistCreditRepository.Runtime(
            scope = CoroutineScope(job + Dispatchers.IO), fetch = fetch,
            contextToken = { "channel-test:ja" }, authRevision = { 0L },
            language = { "ja" }, now = { 1_000_000L })
        fun repository() = ArtistCreditRepository(database, isolatedContext, runtime)
        suspend fun awaitIdle() = withTimeout(15_000) { job.children.toList().joinAll() }
        suspend fun close() {
            job.cancelAndJoin()
            internal.close()
            context.deleteSharedPreferences(preferenceName)
        }
    }
}
