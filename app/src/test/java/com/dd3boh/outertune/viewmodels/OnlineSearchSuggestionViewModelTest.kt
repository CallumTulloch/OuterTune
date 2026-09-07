package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.SearchHistory
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.SearchSuggestions
import com.zionhuang.innertube.models.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class OnlineSearchSuggestionViewModelTest {
    @Test
    fun `later query rejects a response completed just before it changed`() = fixture { f ->
        f.vm.setSearchActive(true, "nirv")
        f.run()
        f.requests.single().response.complete(Result.success(result("old")))
        f.vm.query.value = "yoasobi"
        f.run()
        assertTrue(f.vm.viewState.value.items.isEmpty())
        assertEquals("yoasobi", f.requests.last().query)
        f.finish("new")
        assertEquals("yoasobi", f.vm.viewState.value.query)
        assertEquals("new", f.vm.viewState.value.items.single().id)
    }

    @Test
    fun `rapid deactivate reactivate does not accept the previous pending request`() = fixture { f ->
        f.vm.setSearchActive(true, "nirv")
        f.run()
        f.requests.single().response.complete(Result.success(result("old")))
        f.vm.setSearchActive(false)
        f.vm.setSearchActive(true)
        f.run()
        assertEquals(2, f.requests.size)
        assertTrue(f.vm.viewState.value.items.isEmpty())
        f.finish("new")
        assertEquals("new", f.vm.viewState.value.items.single().id)
    }

    @Test
    fun `content configuration reloads current suggestions and cancels old language`() = fixture { f ->
        f.vm.setSearchActive(true, "nirv")
        f.run()
        assertTrue(f.configurationChanges.tryEmit(Unit))
        f.run()
        assertTrue(f.requests.first().cancelled)
        assertEquals(listOf("nirv", "nirv"), f.requests.map { it.query })
        f.finish("new-language")
        assertEquals("new-language", f.vm.viewState.value.items.single().id)
    }

    @Test
    fun `history removes duplicate query suggestions and item kinds remain distinct`() = fixture { f ->
        f.history = listOf(SearchHistory(query = "Nirvana"))
        f.vm.setSearchActive(true, "nirv")
        f.run()
        f.requests.single().response.complete(Result.success(SearchSuggestions(
            listOf("Nirvana", "ニルヴァーナ"),
            listOf(song("shared"), song("shared"), ArtistItem("shared", "Artist", null,
                shuffleEndpoint = null, radioEndpoint = null)),
        )))
        f.run()
        assertEquals(listOf("ニルヴァーナ"), f.vm.viewState.value.suggestions)
        assertEquals(2, f.vm.viewState.value.items.size)
        assertEquals(listOf("Nirvana"), f.vm.viewState.value.history.map { it.query })
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.scope.cancel(); fixture.run() }
    }

    private class Fixture {
        private val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val requests = mutableListOf<Pending>()
        val configurationChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var history = emptyList<SearchHistory>()
        val vm = OnlineSearchSuggestionViewModel(OnlineSearchSuggestionViewModel.Runtime(
            scope = scope,
            history = { flowOf(history) },
            searchSuggestions = { query -> Pending(query).also(requests::add).await() },
            configurationChanges = configurationChanges,
        ))

        fun run() = dispatcher.runCurrent()
        fun finish(id: String) {
            requests.last().response.complete(Result.success(result(id)))
            run()
        }
    }

    private class Pending(val query: String) {
        val response = CompletableDeferred<Result<SearchSuggestions>>()
        var cancelled = false
        suspend fun await(): Result<SearchSuggestions> = try { response.await() }
        catch (cancelled: CancellationException) { this.cancelled = true; Result.failure(cancelled) }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() {
            var steps = 0
            while (queue.isNotEmpty()) {
                check(++steps < 10_000) { "Coroutine queue did not settle" }
                queue.removeFirst().run()
            }
        }
    }

    companion object {
        private fun song(id: String) = SongItem(id, id, emptyList(), thumbnail = "")
        private fun result(id: String) = SearchSuggestions(emptyList(), listOf(song(id)))
    }
}
