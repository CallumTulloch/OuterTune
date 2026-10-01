package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.db.entities.LocalArtistLinkSource
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.repositories.LocalArtistLinkCandidate
import com.dd3boh.outertune.repositories.LocalArtistLinkException
import com.dd3boh.outertune.repositories.LocalArtistLinkFailure
import com.zionhuang.innertube.models.ArtistItem
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class LocalArtistLinkViewModelTest {
    @Test
    fun aChannelSourceLoadsItsSavedTracksBeforeManualLinkChangeAndUnlink() = fixture { f ->
        val entity = ArtistEntity("CS-fixture", "Original channel", isChannel = true, sourceChannelId = "UC-upload-channel")
        val artist = Artist(entity, songCount = 2, downloadCount = 0)
        val source = LocalArtistLinkSource(artist, emptyList(), songs = listOf(
            Song(SongEntity(id = "first-video", title = "First song", duration = 120, localPath = null), listOf(entity)),
            Song(SongEntity(id = "second-video", title = "Second song", duration = 180, localPath = null), listOf(entity)),
        ))
        f.open(artist)
        assertFalse(f.vm.state.value.loaded)
        f.vm.search()
        f.vm.confirm()
        f.run()
        assertTrue(f.searches.isEmpty())
        assertTrue(f.saves.isEmpty())
        assertTrue(f.sourceDetails.tryEmit(source))
        f.run()
        assertTrue(f.vm.state.value.loaded)
        assertEquals(source, f.vm.state.value.source)
        f.readyPreview("UC-first")
        assertTrue(f.saves.isEmpty())
        f.vm.confirm()
        f.run()
        assertEquals("CS-fixture", f.link.value!!.localArtistId)
        assertEquals("UC-first", f.link.value!!.onlineArtistId)
        assertEquals(entity, f.vm.state.value.source!!.localArtist.artist)
        f.open(artist)
        f.readyPreview("UC-second")
        f.vm.confirm()
        f.run()
        assertEquals(listOf("UC-first" to null, "UC-second" to "saved-revision"), f.saves)
        f.open(artist)
        f.vm.askUnlink()
        f.vm.unlink()
        f.run()
        assertNull(f.link.value)
        assertEquals(source.songs, f.vm.state.value.source!!.songs)
        assertEquals("UC-upload-channel", f.vm.state.value.source!!.localArtist.artist.sourceChannelId)
    }

    @Test
    fun missingChannelSourceBlocksSavingAndRecoversWhenItsTracksCanBeRead() = fixture { f ->
        val artist = Artist(ArtistEntity("CS-fixture", "Channel", isChannel = true), 0, 0)
        f.open(artist)
        assertTrue(f.sourceDetails.tryEmit(null))
        f.run()
        assertFalse(f.vm.state.value.loaded)
        assertEquals(LocalArtistLinkViewModel.Failure.LOAD, f.vm.state.value.failure)
        assertTrue(f.sourceDetails.tryEmit(LocalArtistLinkSource(artist, emptyList())))
        f.run()
        assertTrue(f.vm.state.value.loaded)
        assertNull(f.vm.state.value.failure)
        assertTrue(f.saves.isEmpty())
    }

    @Test
    fun sourceLoadFailureAfterPreviewDisablesConfirmationUntilTheSourceIsReloaded() = fixture { f ->
        val artist = Artist(ArtistEntity("CS-fixture", "Channel", isChannel = true), 0, 0)
        val source = LocalArtistLinkSource(artist, emptyList())
        assertTrue(f.sourceDetails.tryEmit(source))
        f.open(artist)
        f.readyPreview("UC-selected")
        f.sourceFailure = IOException("source unavailable")
        assertTrue(f.sourceDetails.tryEmit(source))
        f.run()
        assertFalse(f.vm.state.value.loaded)
        assertEquals(LocalArtistLinkViewModel.Failure.LOAD, f.vm.state.value.failure)
        assertNull(f.vm.state.value.preview)
        f.vm.confirm()
        f.run()
        assertTrue(f.saves.isEmpty())
        f.sourceFailure = null
        f.open(artist)
        assertTrue(f.vm.state.value.loaded)
        assertNull(f.vm.state.value.failure)
        assertNull(f.vm.state.value.preview)
        f.readyPreview("UC-selected")
        f.vm.confirm()
        f.run()
        assertEquals(listOf("UC-selected" to null), f.saves)
    }

    @Test
    fun searchAndPreviewNeverSaveUntilTheSelectedPageIsConfirmed() = fixture { f ->
        f.open()
        f.vm.confirm()
        assertTrue(f.searches.isEmpty())
        assertTrue(f.saves.isEmpty())
        f.readyPreview("UC-first")
        assertTrue(f.saves.isEmpty())
        f.vm.confirm()
        f.run()
        assertEquals(listOf("UC-first" to null), f.saves)
        assertEquals("UC-first", f.link.value!!.onlineArtistId)
        assertTrue(f.vm.state.value.completed)
    }

    @Test
    fun aLateSearchCannotReplaceTheNewQueryOrSaveAnyLink() = fixture { f ->
        f.open()
        f.vm.search()
        f.run()
        f.vm.changeQuery("Second query")
        f.vm.search()
        f.run()
        f.searches[0].complete(listOf(f.item("UC-old")))
        f.run()
        assertTrue(f.vm.state.value.candidates.isEmpty())
        f.searches[1].complete(listOf(f.item("UC-new")))
        f.run()
        assertEquals("Second query", f.vm.state.value.query)
        assertEquals(listOf("UC-new"), f.vm.state.value.candidates.map { it.id })
        assertTrue(f.saves.isEmpty())
    }

    @Test
    fun selectingAnotherCandidateRejectsThePreviousLatePage() = fixture { f ->
        f.open()
        f.vm.search()
        f.run()
        f.searches.single().complete(listOf(f.item("UC-first"), f.item("UC-second")))
        f.run()
        f.vm.select("UC-first")
        f.run()
        f.vm.select("UC-second")
        f.run()
        f.previews[0].second.complete(f.candidate("UC-first"))
        f.run()
        assertNull(f.vm.state.value.preview)
        f.previews[1].second.complete(f.candidate("UC-second"))
        f.run()
        f.vm.confirm()
        f.run()
        assertEquals(listOf("UC-second" to null), f.saves)
    }

    @Test
    fun changedLinkOrAccountRequiresTheUserToCheckTheCandidateAgain() {
        for (accountChange in listOf(false, true)) fixture { f ->
            f.open()
            f.readyPreview("UC-first")
            if (accountChange) {
                f.token = "new-context"
                assertTrue(f.contextChanges.tryEmit(Unit))
            } else {
                f.link.value = f.savedLink("UC-other", "other-revision")
            }
            f.run()
            assertNull(f.vm.state.value.preview)
            assertEquals(if (accountChange) LocalArtistLinkViewModel.Failure.CONTEXT_CHANGED
                else LocalArtistLinkViewModel.Failure.LINK_CHANGED, f.vm.state.value.failure)
            f.vm.confirm()
            f.run()
            assertTrue(f.saves.isEmpty())
        }
    }

    @Test
    fun anotherUpdateDuringSaveIsReportedAsAConflictWithoutReplacingIt() = fixture { f ->
        f.link.value = f.savedLink("UC-existing", "before")
        f.open()
        f.readyPreview("UC-selected")
        f.pendingSave = CompletableDeferred()
        f.vm.confirm()
        f.run()
        assertTrue(f.vm.state.value.saving)
        f.link.value = f.savedLink("UC-other", "concurrent")
        f.run()
        f.pendingSave!!.complete(Unit)
        f.run()
        assertEquals(listOf("UC-selected" to "before"), f.saves)
        assertEquals("UC-other", f.link.value!!.onlineArtistId)
        assertFalse(f.vm.state.value.completed)
        assertEquals(LocalArtistLinkViewModel.Failure.LINK_CHANGED, f.vm.state.value.failure)
        assertNull(f.vm.state.value.preview)
    }

    @Test
    fun closingTheDialogRejectsAResponseThatIgnoresCancellation() = fixture { f ->
        f.open()
        f.vm.search()
        f.run()
        f.vm.close()
        f.searches.single().complete(listOf(f.item("UC-late")))
        f.run()
        assertNull(f.vm.state.value.artist)
        assertTrue(f.vm.state.value.candidates.isEmpty())
        assertTrue(f.saves.isEmpty())
    }

    @Test
    fun unlinkNeedsConfirmationAndWorksWithoutSearchingOrLoadingAnOnlinePage() = fixture { f ->
        f.link.value = f.savedLink("UC-existing", "original")
        f.open()
        f.vm.unlink()
        f.run()
        assertNotNull(f.link.value)
        f.vm.askUnlink()
        assertTrue(f.vm.state.value.confirmUnlink)
        f.vm.cancelUnlink()
        f.vm.unlink()
        f.run()
        assertNotNull(f.link.value)
        f.vm.askUnlink()
        f.vm.unlink()
        f.run()
        assertNull(f.link.value)
        assertTrue(f.vm.state.value.completed)
        assertTrue(f.searches.isEmpty())
        assertTrue(f.previews.isEmpty())
    }

    @Test
    fun aSearchFailureCanBeRetriedWithoutSaving() = fixture { f ->
        f.open()
        f.vm.search()
        f.run()
        f.searches.single().completeExceptionally(IOException("offline"))
        f.run()
        assertEquals(LocalArtistLinkViewModel.Failure.SEARCH, f.vm.state.value.failure)
        f.vm.search()
        f.run()
        f.searches.last().complete(listOf(f.item("UC-retry")))
        f.run()
        assertNull(f.vm.state.value.failure)
        assertEquals("UC-retry", f.vm.state.value.candidates.single().id)
        assertTrue(f.saves.isEmpty())
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.vm.close(); fixture.scope.cancel(); fixture.run() }
    }

    private class Fixture {
        private val tasks = ArrayDeque<Runnable>()
        private val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val link = MutableStateFlow<LocalArtistLink?>(null)
        val sourceDetails = MutableSharedFlow<LocalArtistLinkSource?>(replay = 1)
        var sourceFailure: IOException? = null
        var sourceId = "LA-fixture"
        val contextChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var token = "initial-context"
        val searches = mutableListOf<CompletableDeferred<List<ArtistItem>>>()
        val previews = mutableListOf<Pair<String, CompletableDeferred<LocalArtistLinkCandidate>>>()
        val saves = mutableListOf<Pair<String, String?>>()
        var pendingSave: CompletableDeferred<Unit>? = null
        val vm = LocalArtistLinkViewModel(LocalArtistLinkViewModel.Runtime(
            links = { link }, contextToken = { token }, contextChanges = contextChanges,
            sourceDetails = { flow {
                sourceDetails.collect { source ->
                    emit(source)
                    sourceFailure?.let { throw it }
                }
            } },
            search = {
                val reply = CompletableDeferred<List<ArtistItem>>().also(searches::add)
                withContext(NonCancellable) { reply.await() }
            },
            preview = { id ->
                val reply = CompletableDeferred<LocalArtistLinkCandidate>()
                previews.add(id to reply)
                withContext(NonCancellable) { reply.await() }
            },
            confirm = { _, candidate, expected ->
                saves.add(candidate.onlineId to expected)
                pendingSave?.await()
                if (link.value?.revision != expected) throw LocalArtistLinkException(LocalArtistLinkFailure.LINK_CHANGED)
                link.value = savedLink(candidate.onlineId, "saved-revision")
            },
            unlink = { _, expected ->
                if (link.value?.revision == expected) { link.value = null; true } else false
            },
            dispatcher = dispatcher, scope = scope,
        ))
        fun open(artist: Artist = Artist(ArtistEntity("LA-fixture", "Local name", isLocal = true), songCount = 3, downloadCount = 0)) {
            sourceId = artist.id
            vm.open(artist)
            run()
        }
        fun run() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        fun item(id: String) = ArtistItem(id, "Name $id", null, shuffleEndpoint = null, radioEndpoint = null)
        fun candidate(id: String) = LocalArtistLinkCandidate(id, "Name $id", null, listOf("Album"), token)
        fun savedLink(id: String, revision: String) = LocalArtistLink(sourceId, id, "Name $id", null, revision)
        fun readyPreview(id: String) {
            vm.search()
            run()
            searches.last().complete(listOf(item(id)))
            run()
            vm.select(id)
            run()
            previews.last().second.complete(candidate(id))
            run()
            assertNotNull(vm.state.value.preview)
        }
    }
}
