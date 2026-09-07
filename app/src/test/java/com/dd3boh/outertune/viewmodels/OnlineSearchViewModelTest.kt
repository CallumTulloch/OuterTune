package com.dd3boh.outertune.viewmodels

import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_ALBUM
import com.zionhuang.innertube.YouTube.SearchFilter.Companion.FILTER_SONG
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class OnlineSearchViewModelTest {
    @Test
    fun `second query replaces summary and every cached category in the same view model`() = fixture { f ->
        f.start("Nirvana")
        f.finishSummary("nirvana")
        f.vm.filter.value = FILTER_SONG
        f.run()
        f.finishCategory("nirvana-song")
        f.vm.filter.value = null
        f.run()

        f.vm.submitQuery("Quruli")

        assertNull(f.vm.summaryPage)
        assertTrue(f.vm.viewStateMap.isEmpty())
        f.run()
        assertEquals("Quruli", f.summaries.last().query)
        f.finishSummary("quruli")
        assertEquals("quruli", f.vm.summaryPage!!.summaries.single().items.single().id)

        f.vm.filter.value = FILTER_SONG
        f.run()
        assertEquals("Quruli", f.categories.last().query)
        f.finishCategory("quruli-song")
        assertEquals(listOf("quruli-song"), f.categoryIds(FILTER_SONG))
    }

    @Test
    fun `new query retains category and all subsequent category requests use it`() = fixture { f ->
        f.vm.filter.value = FILTER_SONG
        f.start("Nirvana")
        f.finishCategory("nirvana-song")

        f.vm.submitQuery("Quruli")
        f.run()

        assertEquals(FILTER_SONG, f.vm.filter.value)
        assertEquals("Quruli", f.categories.last().query)
        f.finishCategory("quruli-song")
        f.vm.filter.value = FILTER_ALBUM
        f.run()
        assertEquals("Quruli", f.categories.last().query)
        assertEquals(FILTER_ALBUM, f.categories.last().filter)
    }

    @Test
    fun `detail return preserves results while explicit source refresh reloads same query`() = fixture { f ->
        f.start("Quruli")
        f.finishSummary("cached")

        f.vm.setSearchActive(false)
        f.run()
        f.vm.submitQuery("Quruli")
        f.vm.setSearchActive(true)
        f.run()

        assertEquals(1, f.summaries.size)
        assertEquals("cached", f.vm.summaryPage!!.summaries.single().items.single().id)

        f.vm.submitQuery("Quruli", refresh = true)
        assertNull(f.vm.summaryPage)
        f.run()
        assertEquals(2, f.summaries.size)
        assertEquals("Quruli", f.summaries.last().query)
    }

    @Test
    fun `response queued just before query change cannot publish an old summary`() = fixture { f ->
        f.start("Nirvana")
        // Complete the response, but change the query before its continuation runs.
        f.summaries.single().response.complete(Result.success(summary("old")))
        f.vm.submitQuery("Quruli")
        f.run()

        assertNull(f.vm.summaryPage)
        assertEquals("Quruli", f.summaries.last().query)
        f.finishSummary("new")
        assertEquals("new", f.vm.summaryPage!!.summaries.single().items.single().id)
        assertTrue(f.failures.isEmpty())
    }

    @Test
    fun `pagination from previous query cannot append to a new query`() = fixture { f ->
        f.vm.filter.value = FILTER_SONG
        f.start("Nirvana")
        f.finishCategory("old-first", continuation = "old-next")
        f.vm.loadMore()
        f.run()
        assertEquals("old-next", f.continuations.single().query)

        f.continuations.single().response.complete(Result.success(SearchResult(listOf(song("old-more")))))
        f.vm.submitQuery("Quruli")
        f.run()
        f.finishCategory("new-first")

        assertEquals(listOf("new-first"), f.categoryIds(FILTER_SONG))
        assertTrue(f.failures.isEmpty())
    }

    @Test
    fun `category response queued before query change cannot restore its old cache`() = fixture { f ->
        f.vm.filter.value = FILTER_SONG
        f.start("Nirvana")
        f.categories.single().response.complete(Result.success(SearchResult(listOf(song("old")))))
        f.vm.submitQuery("Quruli")
        f.run()

        assertTrue(f.vm.viewStateMap.isEmpty())
        assertEquals("Quruli", f.categories.last().query)
        f.finishCategory("new")
        assertEquals(listOf("new"), f.categoryIds(FILTER_SONG))
    }

    @Test
    fun `accepted artist updates still reach both summary and cached categories`() = fixture { f ->
        f.start("Quruli")
        f.finishSummary("song")
        f.vm.filter.value = FILTER_SONG
        f.run()
        f.finishCategory("song")

        f.enrichedTitles["song"] = "Resolved title"
        assertTrue(f.creditUpdates.tryEmit("song"))
        f.run()

        assertEquals("Resolved title", f.vm.summaryPage!!.summaries.single().items.single().title)
        assertEquals("Resolved title", f.vm.viewStateMap[FILTER_SONG.value]!!.items.single().title)
        assertEquals("Resolved title", f.vm.withArtistCredit(song("song")).title)
    }

    @Test
    fun `switching away cancels pending requests even when service wraps cancellation in Result`() = fixture { f ->
        f.start("Nirvana")
        f.vm.setSearchActive(false)
        f.run()

        assertTrue(f.summaries.single().cancelled)
        assertNull(f.vm.summaryPage)
        assertTrue(f.failures.isEmpty())
        f.vm.loadMore()
        assertTrue(f.continuations.isEmpty())

        f.vm.submitQuery("Quruli")
        f.run()
        assertEquals(1, f.summaries.size)
        f.vm.setSearchActive(true)
        f.run()
        assertEquals("Quruli", f.summaries.last().query)
    }

    @Test
    fun `rapid source round trip rejects request started before the switch`() = fixture { f ->
        f.start("Nirvana")
        f.summaries.single().response.complete(Result.success(summary("old")))
        f.vm.setSearchActive(false)
        f.vm.setSearchActive(true)
        f.run()

        assertNull(f.vm.summaryPage)
        assertEquals(2, f.summaries.size)
        f.finishSummary("fresh")
        assertEquals("fresh", f.vm.summaryPage!!.summaries.single().items.single().id)
    }

    @Test
    fun `blank query does not start a network request`() = fixture { f ->
        f.start("")
        assertTrue(f.summaries.isEmpty())
        f.vm.filter.value = FILTER_SONG
        f.run()
        assertTrue(f.categories.isEmpty())
    }

    @Test
    fun `content settings change reloads the current category and invalidates its previous pagination`() = fixture { f ->
        f.vm.filter.value = FILTER_SONG
        f.start("Nirvana")
        f.finishCategory("old", continuation = "old-next")
        f.vm.loadMore()
        f.run()
        assertTrue(f.configurationChanges.tryEmit(Unit))
        f.run()

        assertTrue(f.continuations.single().cancelled)
        assertTrue(f.vm.viewStateMap.isEmpty())
        assertEquals("Nirvana", f.categories.last().query)
        assertEquals(FILTER_SONG, f.vm.filter.value)
        f.finishCategory("new")
        assertEquals(listOf("new"), f.categoryIds(FILTER_SONG))
    }

    @Test
    fun `settings change while inactive clears cached language but waits to request until reactivated`() = fixture { f ->
        f.start("Nirvana")
        f.finishSummary("old-language")
        f.vm.setSearchActive(false)
        f.run()
        assertTrue(f.configurationChanges.tryEmit(Unit))
        f.run()
        assertNull(f.vm.summaryPage)
        assertEquals(1, f.summaries.size)
        f.vm.setSearchActive(true)
        f.run()
        assertEquals(2, f.summaries.size)
    }

    @Test
    fun `return to a partial summary retries its missing language while retaining visible success`() = fixture { f ->
        f.start("Nirvana")
        f.finishSummary("successful-language")
        f.summaryNeedsRetry = true
        f.vm.filter.value = FILTER_SONG
        f.run()
        f.finishCategory("song")
        f.vm.filter.value = null
        f.run()
        assertEquals(2, f.summaries.size)
        assertEquals("successful-language", f.vm.summaryPage!!.summaries.single().items.single().id)
        f.summaryNeedsRetry = false
        f.finishSummary("both-languages")
        assertEquals("both-languages", f.vm.summaryPage!!.summaries.single().items.single().id)
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            test(fixture)
        } finally {
            fixture.scope.cancel()
            fixture.run()
        }
    }

    private class Fixture {
        private val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val summaries = mutableListOf<Pending<SearchSummaryPage>>()
        val categories = mutableListOf<Pending<SearchResult>>()
        val continuations = mutableListOf<Pending<SearchResult>>()
        val failures = mutableListOf<Throwable>()
        val creditUpdates = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val configurationChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        var summaryNeedsRetry = false
        val enrichedTitles = mutableMapOf<String, String>()
        val vm = OnlineSearchViewModel(
            initialQuery = "",
            runtime = OnlineSearchViewModel.Runtime(
                scope = scope,
                searchSummary = { query -> Pending<SearchSummaryPage>(query).also(summaries::add).await() },
                search = { query, filter -> Pending<SearchResult>(query, filter).also(categories::add).await() },
                searchContinuation = { token -> Pending<SearchResult>(token).also(continuations::add).await() },
                configurationChanges = configurationChanges,
                summaryNeedsRetry = { summaryNeedsRetry },
                creditUpdates = creditUpdates,
                withCredit = { song -> song.copy(title = enrichedTitles[song.id] ?: song.title) },
                onFailure = { failures += it },
            ),
        )

        fun run() = dispatcher.runCurrent()

        fun start(query: String) {
            vm.submitQuery(query)
            vm.setSearchActive(true)
            run()
        }

        fun finishSummary(id: String) {
            summaries.last().response.complete(Result.success(summary(id)))
            run()
        }

        fun finishCategory(id: String, continuation: String? = null) {
            categories.last().response.complete(Result.success(SearchResult(listOf(song(id)), continuation)))
            run()
        }

        fun categoryIds(filter: YouTube.SearchFilter): List<String> =
            vm.viewStateMap[filter.value]!!.items.map { it.id }
    }

    private class Pending<T>(val query: String, val filter: YouTube.SearchFilter? = null) {
        val response = CompletableDeferred<Result<T>>()
        var cancelled = false

        suspend fun await(): Result<T> = try {
            response.await()
        } catch (error: CancellationException) {
            cancelled = true
            Result.failure(error)
        }
    }

    /** Run queued coroutine continuations explicitly so response ordering never depends on time. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

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
        private fun summary(id: String) = SearchSummaryPage(listOf(SearchSummary("Songs", listOf(song(id)))))
    }
}
