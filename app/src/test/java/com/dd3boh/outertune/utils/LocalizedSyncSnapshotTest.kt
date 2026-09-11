package com.dd3boh.outertune.utils

import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizedSyncSnapshotTest {
    private val english = YouTubeLocale(gl = "JP", hl = "en")
    private val japanese = YouTubeLocale(gl = "JP", hl = "ja")
    private data class Label(val browseId: String, val name: String)
    private fun labels(locale: YouTubeLocale) = listOf(
        Label("playlist-1", if (locale.hl == "ja") "おすすめ" else "Recommended"),
        Label("playlist-2", "My unchanged custom title"),
    )

    @Test(timeout = 5_000)
    fun `language change during a slow response retries labels without committing the obsolete page`() = runBlocking {
        var current = english
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<YouTubeLocale>()
        val committed = mutableListOf<List<Label>>()
        val result = async {
            fetchAndCommitCurrentSyncSnapshot(
                currentLocale = { current },
                fetch = { locale ->
                    requests += locale
                    if (requests.size == 1) { entered.complete(Unit); release.await() }
                    labels(locale)
                },
                commit = { _, page -> committed += page; true },
            )
        }
        entered.await()
        current = japanese
        release.complete(Unit)

        assertEquals(japanese to labels(japanese), result.await())
        assertEquals(listOf(english, japanese), requests)
        assertEquals(listOf(labels(japanese)), committed)
        assertEquals(listOf("playlist-1", "playlist-2"), committed.single().map { it.browseId })
    }

    @Test(timeout = 5_000)
    fun `locale change while waiting for the database transaction retries the snapshot`() = runBlocking {
        var current = english
        val enteredCommit = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val committed = mutableListOf<List<Label>>()
        var attempts = 0
        val result = async {
            fetchAndCommitCurrentSyncSnapshot(
                currentLocale = { current },
                fetch = { labels(it) },
                commit = { requestLocale, page ->
                    if (++attempts == 1) { enteredCommit.complete(Unit); releaseCommit.await() }
                    // Matches the guard inside the real MusicDatabase.awaitTransaction block.
                    if (current != requestLocale) false else { committed += page; true }
                },
            )
        }
        enteredCommit.await()
        current = japanese
        releaseCommit.complete(Unit)

        assertEquals(japanese to labels(japanese), result.await())
        assertEquals(2, attempts)
        assertEquals(listOf(labels(japanese)), committed)
    }

    @Test(timeout = 5_000)
    fun `failure in an obsolete locale retries but current failure retains the original error`() = runBlocking {
        var current = english
        val requests = mutableListOf<YouTubeLocale>()
        val result = fetchAndCommitCurrentSyncSnapshot(
            currentLocale = { current },
            fetch = { locale ->
                requests += locale
                if (locale == english) { current = japanese; error("old-locale failure") }
                labels(locale)
            },
            commit = { _, _ -> true },
        )
        assertEquals(japanese to labels(japanese), result)
        assertEquals(listOf(english, japanese), requests)

        val failure = IllegalStateException("current request failed")
        val caught = runCatching {
            fetchAndCommitCurrentSyncSnapshot<List<Label>>(
                currentLocale = { current },
                fetch = { throw failure },
                commit = { _, _ -> error("Failed fetch must not alter saved labels") },
            )
        }.exceptionOrNull()
        assertSame(failure, caught)
    }

    @Test(timeout = 5_000)
    fun `cancellation is never converted into a locale retry or a successful sync`() = runBlocking {
        var current = english
        var requests = 0
        val cancelled = CancellationException("cancelled sync")
        val caught = runCatching {
            fetchAndCommitCurrentSyncSnapshot<List<Label>>(
                currentLocale = { current },
                fetch = {
                    requests++
                    current = japanese
                    throw cancelled
                },
                commit = { _, _ -> error("Cancelled fetch must not commit") },
            )
        }.exceptionOrNull()
        assertSame(cancelled, caught)
        assertEquals(1, requests)
    }

    @Test(timeout = 5_000)
    fun `stable locale commits once and passes its captured context to the next sync stage`() = runBlocking {
        var requests = 0
        var commits = 0
        val result = fetchAndCommitCurrentSyncSnapshot(
            currentLocale = { japanese },
            fetch = { requests++; labels(it) },
            commit = { locale, page ->
                commits++
                assertEquals(japanese, locale)
                assertTrue(page.all { it.browseId.startsWith("playlist-") })
                true
            },
        )
        assertEquals(japanese to labels(japanese), result)
        assertEquals(1, requests)
        assertEquals(1, commits)
    }

    @Test(timeout = 5_000)
    fun `change immediately after successful commit retries instead of marking the old locale complete`() = runBlocking {
        var current = english
        var savedLabels = emptyList<Label>()
        var commits = 0
        val result = fetchAndCommitCurrentSyncSnapshot(
            currentLocale = { current },
            fetch = { labels(it) },
            commit = { _, page ->
                savedLabels = page
                if (++commits == 1) current = japanese
                true
            },
        )

        assertEquals(japanese to labels(japanese), result)
        assertEquals(labels(japanese), savedLabels)
        assertEquals(2, commits)
    }

    @Test(timeout = 5_000)
    fun `obsolete transaction rollback is retried while committed contents remain intact`() = runBlocking {
        var current = english
        val originalLabels = listOf(Label("existing", "Preserved before commit"))
        var savedLabels = originalLabels
        var commits = 0
        val result = fetchAndCommitCurrentSyncSnapshot(
            currentLocale = { current },
            fetch = { locale ->
                // The first transaction's exception must leave the previous saved state intact.
                if (locale == japanese) assertEquals(originalLabels, savedLabels)
                labels(locale)
            },
            commit = { locale, page ->
                // Model Room's staging: the end guard throws before any staged replacement commits.
                val stagedLabels = page.toList()
                if (++commits == 1) current = japanese
                check(locale == current) { "Content locale changed during transaction" }
                savedLabels = stagedLabels
                true
            },
        )

        assertEquals(japanese to labels(japanese), result)
        assertEquals(labels(japanese), savedLabels)
        assertEquals(2, commits)
    }

    @Test(timeout = 5_000)
    fun `current context database failures and transaction cancellation still propagate`() = runBlocking {
        for (failure in listOf(IllegalStateException("database unavailable"), CancellationException("cancelled commit"))) {
            var current = english
            var commits = 0
            val caught = runCatching {
                fetchAndCommitCurrentSyncSnapshot(
                    currentLocale = { current },
                    fetch = { labels(it) },
                    commit = { _, _ ->
                        commits++
                        if (failure is CancellationException) current = japanese
                        throw failure
                    },
                )
            }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(1, commits)
        }
    }
}
