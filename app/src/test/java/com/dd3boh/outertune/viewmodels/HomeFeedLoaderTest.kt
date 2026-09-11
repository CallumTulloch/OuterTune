package com.dd3boh.outertune.viewmodels

import com.zionhuang.innertube.pages.HomePage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class HomeFeedLoaderTest {
    @Test fun `rapid scroll sends one request and repeated continuation terminates`() = fixture { f ->
        f.start("A")
        repeat(10) { f.loader.loadMore() }
        assertTrue(f.loader.loadingMore.value)
        f.run()
        assertEquals(listOf(null, "A"), f.tokens)
        f.finish(page("A", "same"))
        assertFalse(f.loader.loadingMore.value)
        assertNull(f.loader.page.value!!.continuation)
        assertEquals(1, f.loader.page.value!!.sections.size)
    }

    @Test fun `failure clears spinner and only explicit retry resubmits`() = fixture { f ->
        f.start("A")
        f.loader.loadMore(); f.run()
        f.pending.last().complete(Result.failure(IllegalStateException("offline"))); f.run()
        assertFalse(f.loader.loadingMore.value)
        assertTrue(f.loader.failed.value)
        repeat(5) { f.loader.loadMore() }; f.run()
        assertEquals(2, f.tokens.size)
        f.loader.retry(); f.run(); f.finish(page(null, "new"))
        assertFalse(f.loader.failed.value)
        assertEquals(listOf("same", "new"), f.loader.page.value!!.sections.map { it.title })
    }

    @Test fun `empty progressing pages have a bound and retry continues at the unconsumed token`() = fixture { f ->
        f.start("A"); f.loader.loadMore(); f.run()
        for (token in listOf("B", "C", "D")) f.finish(HomePage(null, emptyList(), token))
        assertEquals(4, f.tokens.size)
        assertTrue(f.loader.failed.value)
        assertFalse(f.loader.loadingMore.value)
        f.loader.retry(); f.run()
        assertEquals("D", f.tokens.last())
        f.finish(page(null, "new"))
        assertEquals(2, f.loader.page.value!!.sections.size)
    }

    @Test fun `refresh rejects a previously completed continuation before its dispatcher resumes`() = fixture { f ->
        f.start("A"); f.loader.loadMore(); f.run()
        f.pending.last().complete(Result.success(page(null, "stale")))
        f.loader.refresh(); f.run(); f.finish(page(null, "fresh"))
        assertEquals(listOf("fresh"), f.loader.page.value!!.sections.map { it.title })
        assertFalse(f.loader.loadingMore.value)
    }

    @Test fun `language reset discards the old page chip and continuation even when the new request fails`() = fixture { f ->
        f.start("old-language-token")
        val chip = HomePage.Chip("Old language chip", null, null)
        f.loader.toggleChip(chip); f.run()
        f.pending.last().complete(Result.success(page("old-chip-token", "stale chip")))
        f.loader.refresh(clearExisting = true)
        assertNull(f.loader.page.value)
        assertNull(f.loader.selectedChip.value)
        f.run()
        f.pending.last().complete(Result.failure(IllegalStateException("offline"))); f.run()
        assertNull(f.loader.page.value)
        assertTrue(f.loader.failed.value)
        f.loader.toggleChip(null); f.run()
        f.finish(page(null, "new language"))
        assertEquals("new language", f.loader.page.value!!.sections.single().title)
    }

    @Test fun `timeout releases initial loading and permits retry`() = runBlocking {
        val loader = HomeFeedLoader(this, { _, _ -> awaitCancellation() }, timeoutMillis = 20)
        loader.refresh()
        withTimeout(2000) { while (loader.loading.value) delay(5) }
        assertTrue(loader.failed.value)
        loader.retry()
        assertTrue(loader.loading.value)
        withTimeout(2000) { while (loader.loading.value) delay(5) }
    }

    @Test fun `deselecting a chip restores the original page and rejects its pending response`() = fixture { f ->
        f.start("A")
        val chip = HomePage.Chip("Selected", null, null)
        f.loader.toggleChip(chip); f.run()
        f.pending.last().complete(Result.success(page(null, "late chip")))
        f.loader.toggleChip(chip); f.run()
        assertNull(f.loader.selectedChip.value)
        assertEquals("same", f.loader.page.value!!.sections.single().title)
        assertEquals("A", f.loader.page.value!!.continuation)
        assertFalse(f.loader.loading.value)
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.scope.cancel(); f.run() }
    }
    private class Fixture {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val tokens = mutableListOf<String?>()
        val pending = mutableListOf<CompletableDeferred<Result<HomePage>>>()
        val loader = HomeFeedLoader(scope, { token, _ ->
            tokens += token
            CompletableDeferred<Result<HomePage>>().also(pending::add).await()
        })
        fun run() = dispatcher.runCurrent()
        fun start(token: String?) { loader.refresh(); run(); finish(page(token, "same")) }
        fun finish(page: HomePage) { pending.last().complete(Result.success(page)); run() }
    }
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }
    companion object {
        private fun page(token: String?, title: String) = HomePage(null,
            listOf(HomePage.Section(title, null, null, null, emptyList())), token)
    }
}
