package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.LocalArtistLinkSource
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class LocalArtistLinksViewModelTest {
    @Test
    fun allLinksIncludeZeroSongAndHiddenSourcesAndPassTheOriginalArtistToTheEditor() = fixture { f ->
        f.vm.open(null)
        f.run()
        assertNull(f.requests.single().onlineId)
        val sources = listOf(f.source("LA-zero", 0), f.source("LA-hidden", 3))
        f.reply(sources)
        assertEquals(sources, f.vm.state.value.sources)
        f.vm.edit("LA-zero")
        assertEquals(sources.first().localArtist, f.vm.state.value.editing)
        assertEquals("LA-zero", f.vm.state.value.editing!!.id)
        assertTrue(f.vm.state.value.editing!!.artist.isLocal)
    }

    @Test
    fun theLatestTargetWinsWhenTheOldDatabaseLoadFinishesLate() = fixture { f ->
        f.vm.open("UC-first")
        f.run()
        f.vm.open("UC-second")
        f.run()
        assertEquals(listOf("UC-first", "UC-second"), f.requests.map { it.onlineId })
        f.requests.first().first.complete(listOf(f.source("LA-old", 2)))
        f.run()
        assertTrue(f.vm.state.value.loading)
        assertTrue(f.vm.state.value.sources.isEmpty())
        val latest = listOf(f.source("LA-new", 4))
        f.reply(latest)
        assertEquals(latest, f.vm.state.value.sources)
    }

    @Test
    fun liveUnlinkUpdatesTheListWithoutDisposingOrChangingTheActiveChildEditor() = fixture { f ->
        f.vm.open(null)
        f.run()
        val first = f.source("LA-first", 2)
        val other = f.source("LA-other", 1)
        f.reply(listOf(first, other))
        f.vm.edit(first.localArtist.id)
        f.update(listOf(other))
        assertEquals(listOf(other), f.vm.state.value.sources)
        assertEquals(first.localArtist, f.vm.state.value.editing)
        f.vm.edit(other.localArtist.id)
        assertEquals(first.localArtist, f.vm.state.value.editing)
        f.vm.finishEditing()
        assertNull(f.vm.state.value.editing)
        assertEquals(listOf(other), f.vm.state.value.sources)
        f.vm.edit(first.localArtist.id)
        assertNull(f.vm.state.value.editing)
        f.vm.edit(other.localArtist.id)
        assertEquals(other.localArtist, f.vm.state.value.editing)
    }

    @Test
    fun aChangedRevisionIsSeenOnTheNextEditWithoutReplacingTheOpenEditorsSnapshot() = fixture { f ->
        f.vm.open(null)
        f.run()
        val source = f.source("LA-source", 1)
        f.reply(listOf(source))
        f.vm.edit(source.localArtist.id)
        val changed = source.copy(localArtist = source.localArtist.copy(
            localLink = source.localArtist.localLink!!.copy(onlineArtistId = "UC-new", revision = "new")))
        f.update(listOf(changed))
        assertEquals(source.localArtist, f.vm.state.value.editing)
        f.vm.finishEditing()
        f.vm.edit(source.localArtist.id)
        assertEquals(changed.localArtist, f.vm.state.value.editing)
    }

    @Test
    fun closingRejectsLateDatabaseResultsAndReopeningStartsWithoutAnOldEditor() = fixture { f ->
        f.vm.open(null)
        f.run()
        f.vm.close()
        f.requests.single().first.complete(listOf(f.source("LA-late", 1)))
        f.run()
        assertEquals(LocalArtistLinksViewModel.State(), f.vm.state.value)
        f.vm.open(null)
        f.run()
        f.reply(listOf(f.source("LA-next", 1)))
        assertNull(f.vm.state.value.editing)
        assertEquals("LA-next", f.vm.state.value.sources.single().localArtist.id)
    }

    @Test
    fun failedLoadsCanBeRetriedForTheSameTarget() = fixture { f ->
        f.vm.open("UC-target")
        f.run()
        f.requests.single().first.completeExceptionally(IOException("unavailable"))
        f.run()
        assertTrue(f.vm.state.value.failed)
        assertFalse(f.vm.state.value.loading)
        f.vm.retry()
        f.run()
        assertEquals(listOf("UC-target", "UC-target"), f.requests.map { it.onlineId })
        f.reply(emptyList())
        assertFalse(f.vm.state.value.failed)
        assertFalse(f.vm.state.value.loading)
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally {
            fixture.vm.close()
            fixture.scope.cancel()
            fixture.requests.forEach { it.first.complete(emptyList()) }
            fixture.run()
        }
    }

    private class Request(val onlineId: String?) {
        val first = CompletableDeferred<List<LocalArtistLinkSource>>()
        val updates = MutableSharedFlow<List<LocalArtistLinkSource>>(extraBufferCapacity = 1)
    }

    private class Fixture {
        private val tasks = ArrayDeque<Runnable>()
        private val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val requests = mutableListOf<Request>()
        val vm = LocalArtistLinksViewModel(LocalArtistLinksViewModel.Runtime(
            sources = { onlineId -> flow {
                val request = Request(onlineId).also(requests::add)
                val first = withContext(NonCancellable) { request.first.await() }
                emit(first)
                emitAll(request.updates)
            } },
            dispatcher = dispatcher,
            scope = scope,
        ))
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        fun reply(sources: List<LocalArtistLinkSource>) { requests.last().first.complete(sources); run() }
        fun update(sources: List<LocalArtistLinkSource>) { assertTrue(requests.last().updates.tryEmit(sources)); run() }
        fun source(id: String, songs: Int) = LocalArtistLinkSource(
            localArtist = Artist(ArtistEntity(id, "File name $id", isLocal = true), songCount = songs, downloadCount = 0,
                localLink = LocalArtistLink(id, "UC-destination", "Online name", null, "revision-$id")),
            folders = if (songs == 0) emptyList() else listOf("/storage/emulated/0/Music/$id"),
        )
    }
}
