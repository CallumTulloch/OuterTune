package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.ArtistSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class LocalArtistLinkRepositoryTest {
    private val onlineId = "UCabcdefghijklmnopqrstuv"
    private val otherId = "UCABCDEFGHIJKLMNOPQRSTUV"
    private val localId = "LAlocal-artist"

    private class MemoryStorage : LocalArtistLinkRepository.Storage {
        val rows = mutableMapOf<String, LocalArtistLink>()
        var commits = 0
        var beforeTransaction: () -> Unit = {}
        var afterPut: () -> Unit = {}
        private val mutex = Mutex()

        override suspend fun transaction(block: LocalArtistLinkRepository.Storage.Transaction.() -> Unit): Unit = mutex.withLock {
            beforeTransaction()
            val pending = rows.toMutableMap()
            block(object : LocalArtistLinkRepository.Storage.Transaction {
                override fun current(localId: String) = pending[localId]
                override fun put(link: LocalArtistLink) { pending[link.localArtistId] = link; afterPut() }
                override fun remove(localId: String, expectedRevision: String): Boolean {
                    if (pending[localId]?.revision != expectedRevision) return false
                    return pending.remove(localId) != null
                }
            })
            rows.clear()
            rows.putAll(pending)
            commits++
        }
    }

    private inner class Fixture {
        val storage = MemoryStorage()
        var locale = YouTubeLocale("JP", "ja")
        var authRevision = 1L
        var nextRevision = 0
        val queries = mutableListOf<Pair<String, YouTubeLocale>>()
        val fetches = mutableListOf<Pair<String, YouTubeLocale>>()
        var search: suspend (String, YouTubeLocale) -> List<ArtistItem> = { _, _ -> listOf(artist(onlineId)) }
        var resolve: suspend (String, YouTubeLocale) -> String = { _, _ -> onlineId }
        var fetch: suspend (String, YouTubeLocale) -> ArtistPage = { id, _ -> page(id) }
        val repository = LocalArtistLinkRepository(storage, LocalArtistLinkRepository.Runtime(
            locale = { locale }, authRevision = { authRevision },
            search = { query, requestLocale -> queries += query to requestLocale; search(query, requestLocale) },
            resolveUrl = { input, requestLocale -> resolve(input, requestLocale) },
            artist = { id, requestLocale -> fetches += id to requestLocale; fetch(id, requestLocale) },
            newRevision = { "revision-${++nextRevision}" },
        ))
    }

    private fun artist(id: String, title: String = "天音かなた") = ArtistItem(
        id, title, "https://example.invalid/image", shuffleEndpoint = null, radioEndpoint = null,
    )

    private fun page(id: String) = ArtistPage(artist(id), listOf(ArtistSection("Albums",
        listOf("First", "Second", "First", "Third", "Fourth").mapIndexed { index, name ->
            AlbumItem("album-$index", null, title = name, artists = null, thumbnail = "")
        }, null)), null)

    private suspend fun assertFailure(reason: LocalArtistLinkFailure, action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is LocalArtistLinkException)
        assertEquals(reason, (failure as LocalArtistLinkException).reason)
    }

    @Test fun `name search and real page preview are read only and cap representative albums`() = runBlocking {
        val fixture = Fixture()
        fixture.search = { _, _ -> listOf(artist(onlineId, "Search title"), artist(onlineId), artist("private")) }
        val repository = fixture.repository
        assertEquals(listOf(onlineId), repository.findCandidates(" 天音かなた ").map { it.id })
        val candidate = repository.preview(onlineId)
        assertEquals("天音かなた", candidate.name)
        assertEquals(listOf("First", "Second", "Third"), candidate.albumTitles)
        assertEquals("https://example.invalid/image", candidate.thumbnailUrl)
        assertEquals(repository.contextToken(), candidate.contextToken)
        assertEquals(listOf("天音かなた" to YouTubeLocale("JP", "ja")), fixture.queries)
        assertEquals(0, fixture.storage.commits)
        assertTrue(fixture.storage.rows.isEmpty())
    }

    @Test fun `URL selection resolves an identity and verifies its page without using name search`() = runBlocking {
        val fixture = Fixture()
        fixture.search = { _, _ -> error("A URL must not become a name search") }
        assertEquals(onlineId, fixture.repository.findCandidates("https://music.youtube.com/@example").single().id)
        assertEquals(listOf(onlineId to fixture.locale), fixture.fetches)
        assertEquals(0, fixture.storage.commits)
    }

    @Test fun `confirmation and relinking create independent revisions and unlink compares the exact revision`() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository
        val first = repository.preview(onlineId)
        repository.confirm(localId, first, null)
        val saved = fixture.storage.rows.getValue(localId)
        assertEquals(onlineId, saved.onlineArtistId)
        assertEquals(first.name, saved.onlineName)
        assertEquals(first.thumbnailUrl, saved.thumbnailUrl)
        val replacement = repository.preview(otherId)
        repository.confirm(localId, replacement, saved.revision)
        val changed = fixture.storage.rows.getValue(localId)
        assertEquals(otherId, changed.onlineArtistId)
        assertNotEquals(saved.revision, changed.revision)
        assertFalse(repository.unlink(localId, saved.revision))
        assertEquals(changed, fixture.storage.rows[localId])
        assertTrue(repository.unlink(localId, changed.revision))
        assertTrue(fixture.storage.rows.isEmpty())
        assertEquals(2, fixture.fetches.size) // Confirmation and unlink never fetch again.
    }

    @Test fun `concurrent save and delayed replacement cannot overwrite a newer link`() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository
        val candidate = repository.preview(onlineId)
        repository.confirm(localId, candidate, null)
        val first = fixture.storage.rows.getValue(localId)
        assertFailure(LocalArtistLinkFailure.LINK_CHANGED) { repository.confirm(localId, candidate, null) }
        repository.confirm(localId, repository.preview(otherId), first.revision)
        val latest = fixture.storage.rows.getValue(localId)
        assertFailure(LocalArtistLinkFailure.LINK_CHANGED) { repository.confirm(localId, candidate, first.revision) }
        assertEquals(latest, fixture.storage.rows[localId])
    }

    @Test fun `stale language or authentication invalidates preview and does not replace a saved link`() = runBlocking {
        for (change in listOf<(Fixture) -> Unit>({ it.locale = YouTubeLocale("US", "en") }, { it.authRevision += 2 })) {
            val fixture = Fixture()
            val repository = fixture.repository
            val candidate = repository.preview(onlineId)
            repository.confirm(localId, candidate, null)
            val saved = fixture.storage.rows.getValue(localId)
            change(fixture)
            assertFailure(LocalArtistLinkFailure.STALE_CONTEXT) { repository.confirm(localId, candidate, saved.revision) }
            assertEquals(saved, fixture.storage.rows[localId])
        }
    }

    @Test fun `session changes while waiting for transaction or during write roll back the link`() = runBlocking {
        for (insideWrite in listOf(false, true)) {
            val fixture = Fixture()
            val candidate = fixture.repository.preview(onlineId)
            if (insideWrite) fixture.storage.afterPut = { fixture.authRevision++ }
            else fixture.storage.beforeTransaction = { fixture.authRevision++ }
            assertFailure(LocalArtistLinkFailure.STALE_CONTEXT) { fixture.repository.confirm(localId, candidate, null) }
            assertTrue(fixture.storage.rows.isEmpty())
            assertEquals(0, fixture.storage.commits)
        }
    }

    @Test fun `cancelling during a synchronous transaction rolls back the replacement`() = runBlocking {
        val fixture = Fixture()
        val repository = fixture.repository
        repository.confirm(localId, repository.preview(onlineId), null)
        val saved = fixture.storage.rows.getValue(localId)
        val replacement = repository.preview(otherId)
        val confirmation = async(start = CoroutineStart.LAZY) {
            repository.confirm(localId, replacement, saved.revision)
        }
        fixture.storage.afterPut = { confirmation.cancel() }
        confirmation.start()
        assertTrue(runCatching { confirmation.await() }.exceptionOrNull() is CancellationException)
        assertEquals(saved, fixture.storage.rows[localId])
        assertEquals(1, fixture.storage.commits)
    }

    @Test fun `delayed search and artist responses cannot cross session or locale changes`() = runBlocking {
        val fixture = Fixture()
        val searchReply = CompletableDeferred<List<ArtistItem>>()
        fixture.search = { _, _ -> searchReply.await() }
        val search = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.repository.findCandidates("artist") }
        }
        fixture.authRevision += 2 // Includes an account A -> B -> A round trip.
        searchReply.complete(listOf(artist(onlineId)))
        assertEquals(LocalArtistLinkFailure.STALE_CONTEXT, (search.await().exceptionOrNull() as LocalArtistLinkException).reason)
        val artistReply = CompletableDeferred<ArtistPage>()
        fixture.fetch = { _, _ -> artistReply.await() }
        val preview = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { fixture.repository.preview(onlineId) }
        }
        fixture.locale = YouTubeLocale("US", "en")
        artistReply.complete(page(onlineId))
        assertEquals(LocalArtistLinkFailure.STALE_CONTEXT, (preview.await().exceptionOrNull() as LocalArtistLinkException).reason)
        assertEquals(0, fixture.storage.commits)
    }

    @Test fun `wrong artist page invalid input and failures never write a link`() = runBlocking {
        val fixture = Fixture()
        assertFailure(LocalArtistLinkFailure.INVALID_INPUT) { fixture.repository.findCandidates(" ") }
        assertFailure(LocalArtistLinkFailure.INVALID_ARTIST) { fixture.repository.preview("UCshort") }
        fixture.fetch = { _, _ -> page(otherId) }
        assertFailure(LocalArtistLinkFailure.INVALID_ARTIST) { fixture.repository.preview(onlineId) }
        fixture.fetch = { _, _ -> throw IllegalStateException("Offline") }
        assertTrue(runCatching { fixture.repository.preview(onlineId) }.isFailure)
        assertTrue(fixture.storage.rows.isEmpty())
        assertEquals(0, fixture.storage.commits)
    }
}
