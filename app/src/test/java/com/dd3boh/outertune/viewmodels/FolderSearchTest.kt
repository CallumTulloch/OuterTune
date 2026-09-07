package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSearchTest {
    private fun song(id: String) = Song(SongEntity(id, id, localPath = null), emptyList())

    @Test
    fun `edits cancel the previous database observer before waiting for new results`() = runBlocking {
        val requests = MutableStateFlow(FolderSearchRequest("old", immediate = true))
        val old = MutableSharedFlow<List<Song>>(replay = 1).apply { tryEmit(listOf(song("old"))) }
        val results = Channel<FolderSearchResult>(Channel.UNLIMITED)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            folderSearchResults(requests) { if (it == "old") old else flowOf(listOf(song(it))) }
                .collect { results.send(it) }
        }
        try {
            withTimeout(5_000) {
                assertEquals("old", results.receive().query)
                assertEquals("old", results.receive().songs.single().id)
                requests.value = FolderSearchRequest("new")
                val pending = results.receive()
                assertEquals("new", pending.query)
                assertTrue(pending.songs.isEmpty())
                assertEquals(0, old.subscriptionCount.value)
                old.emit(listOf(song("stale")))
                assertEquals("new", results.receive().songs.single().id)
            }
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `clearing a query removes results and disconnects further database updates`() = runBlocking {
        val requests = MutableStateFlow(FolderSearchRequest("found", immediate = true))
        val source = MutableSharedFlow<List<Song>>(replay = 1).apply { tryEmit(listOf(song("found"))) }
        val results = Channel<FolderSearchResult>(Channel.UNLIMITED)
        val job = launch { folderSearchResults(requests) { source }.collect { results.send(it) } }
        try {
            withTimeout(5_000) {
                results.receive()
                assertEquals("found", results.receive().songs.single().id)
                requests.value = FolderSearchRequest("")
                assertEquals(FolderSearchResult(), results.receive())
                assertEquals(0, source.subscriptionCount.value)
                source.emit(listOf(song("stale")))
                assertTrue(results.tryReceive().isFailure)
                requests.value = FolderSearchRequest("   ")
                assertTrue(results.receive().songs.isEmpty())
                assertEquals(0, source.subscriptionCount.value)
            }
        } finally { job.cancelAndJoin() }
    }

    @Test
    fun `submit replaces a pending edit and keeps observing the submitted query`() = runBlocking {
        val requests = MutableStateFlow(FolderSearchRequest("latest"))
        val source = MutableSharedFlow<List<Song>>(replay = 1).apply { tryEmit(listOf(song("first"))) }
        val results = Channel<FolderSearchResult>(Channel.UNLIMITED)
        val searches = mutableListOf<String>()
        val job = launch { folderSearchResults(requests) { searches.add(it); source }.collect { results.send(it) } }
        try {
            withTimeout(5_000) {
                assertTrue(results.receive().songs.isEmpty())
                requests.value = FolderSearchRequest("latest", immediate = true)
                assertTrue(results.receive().songs.isEmpty())
                assertEquals("first", results.receive().songs.single().id)
                source.emit(listOf(song("updated")))
                assertEquals("updated", results.receive().songs.single().id)
                assertEquals(listOf("latest"), searches)
            }
        } finally { job.cancelAndJoin() }
    }
}
