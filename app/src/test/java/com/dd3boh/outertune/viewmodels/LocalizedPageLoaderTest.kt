package com.dd3boh.outertune.viewmodels

import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class LocalizedPageLoaderTest {
    @Test fun `explicit refresh rejects the prior request even when the locale stays the same`() = fixture { f ->
        f.start()
        f.loader.loadMore(); f.run()
        val oldContinuation = f.pending.last()
        f.loader.refresh()
        // An old page token cannot be claimed before the refresh collector starts.
        f.loader.loadMore()
        oldContinuation.complete(Result.success(Page("Stale", listOf("old"), null)))
        f.run()
        assertNull(f.loader.page.value)
        assertEquals(listOf(null, "A", null), f.calls.map { it.first })
        assertTrue(f.calls.all { it.second == english })
        f.finish(Page("Refreshed", listOf("fresh"), "new"))
        assertEquals(listOf("fresh"), f.loader.page.value!!.items)
        assertFalse(f.loader.loading.value)
    }

    @Test fun `locale change clears old heading and rejects completed old initial response`() = fixture { f ->
        f.run()
        val old = f.pending.single()
        f.locales.value = japanese
        old.complete(Result.success(Page("Old heading", listOf("old"), "en-token")))
        f.run()
        assertNull(f.loader.page.value)
        assertTrue(f.loader.loading.value)
        assertEquals(listOf(english, japanese), f.calls.map { it.second })
        f.finish(Page("新しい見出し", listOf("new"), "ja-token"))
        assertEquals("新しい見出し", f.loader.page.value!!.heading)
        assertFalse(f.loader.loading.value)
    }

    @Test fun `continuation stays with its locale and setting changes cancel old pagination`() = fixture { f ->
        f.start()
        repeat(10) { f.loader.loadMore() }
        assertTrue(f.loader.loading.value)
        f.run()
        assertEquals(listOf(null, "A"), f.calls.map { it.first })
        assertEquals(english, f.calls.last().second)
        val oldContinuation = f.pending.last()
        f.locales.value = japanese
        // Before the locale collector runs, an old token still must not start a new request.
        f.loader.loadMore()
        f.run()
        assertNull(f.loader.page.value)
        f.finish(Page("日本語", listOf("new"), "J"))
        oldContinuation.complete(Result.success(Page("Old", listOf("late"), null)))
        f.run()
        assertEquals(listOf("new"), f.loader.page.value!!.items)
        f.loader.loadMore(); f.run()
        assertEquals("J" to japanese, f.calls.last())
        f.finish(Page("", listOf("more"), null))
        assertEquals(listOf("new", "more"), f.loader.page.value!!.items)
    }

    @Test fun `load remaining stops on failure and explicit retry retains the failed token`() = fixture { f ->
        f.start()
        f.loader.loadMore(remaining = true); f.run()
        f.pending.last().complete(Result.failure(IllegalStateException("offline"))); f.run()
        assertFalse(f.loader.loading.value)
        assertEquals(2, f.calls.size)
        assertEquals(1, f.failures.size)
        assertEquals("A", f.loader.page.value!!.token)
        assertEquals(listOf("song"), f.loader.page.value!!.items)
        f.loader.loadMore(remaining = true); f.run()
        assertEquals("A" to english, f.calls.last())
        f.finish(Page("", listOf("next"), null))
        assertFalse(f.loader.loading.value)
        assertEquals(listOf("song", "next"), f.loader.page.value!!.items)
    }

    @Test fun `cyclic continuation ends and deliberate repeated playlist songs remain`() = fixture { f ->
        f.start()
        f.loader.loadMore(remaining = true); f.run()
        f.finish(Page("", listOf("song"), "B"))
        assertEquals("B" to english, f.calls.last())
        f.finish(Page("", listOf("third"), "A"))
        assertNull(f.loader.page.value!!.token)
        assertFalse(f.loader.loading.value)
        assertEquals(listOf("song", "song", "third"), f.loader.page.value!!.items)
        f.loader.loadMore(remaining = true); f.run()
        assertEquals(3, f.calls.size)
    }

    private fun fixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally { fixture.scope.cancel(); fixture.run() }
    }

    private data class Page(val heading: String, val items: List<String>, val token: String?)
    private class Fixture {
        private val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val locales = MutableStateFlow(english)
        val calls = mutableListOf<Pair<String?, YouTubeLocale>>()
        val pending = mutableListOf<CompletableDeferred<Result<Page>>>()
        val failures = mutableListOf<Throwable>()
        private suspend fun fetch(token: String?, locale: YouTubeLocale): Result<Page> {
            calls += token to locale
            return CompletableDeferred<Result<Page>>().also(pending::add).await()
        }
        val loader = LocalizedPageLoader(scope, locales,
            initial = { fetch(null, it) },
            continuation = Page::token,
            append = { previous, token, locale -> fetch(token, locale).map { next ->
                previous.copy(items = previous.items + next.items, token = next.token)
            } },
            stopPagination = { it.copy(token = null) },
            onFailure = { failures += it },
        )
        fun run() = dispatcher.runCurrent()
        fun start() { run(); finish(Page("English", listOf("song"), "A")) }
        fun finish(page: Page) { pending.last().complete(Result.success(page)); run() }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.addLast(block) }
        fun runCurrent() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    companion object {
        private val english = YouTubeLocale("JP", "en")
        private val japanese = YouTubeLocale("JP", "ja")
    }
}
