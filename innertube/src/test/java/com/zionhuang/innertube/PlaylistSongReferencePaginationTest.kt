package com.zionhuang.innertube

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSongReferencePaginationTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
        "/playlist-song-reference/nevermind-complete.json")).readText()).jsonObject
    private val id = fixture.getValue("playlistId").jsonPrimitive.content
    private val browse = fixture.getValue("browse")
    private val next = fixture.getValue("next")
    private val section = listOf("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
    private val shelf = section + listOf("contents", "0", "musicPlaylistShelfRenderer")
    private val panel = listOf("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
        "watchNextTabbedResultsRenderer", "tabs", "0", "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
    private val sources = browse.at(shelf + "contents").jsonArray.toList()
    private val targets = next.at(panel + "contents").jsonArray.filter { "playlistPanelVideoRenderer" in it.jsonObject }

    @Test fun `single page loader preserves the complete saved 13 track observation`() = runBlocking {
        val requests = mutableListOf<String>()
        val result = loadPlaylistSongReferences(id, "en",
            fetchBrowse = { requests += "browse:$it"; browse },
            fetchNext = { requests += "next:$it"; next })
        assertEquals(parsePlaylistSongReferences(browse, next, id, "en"), result)
        assertEquals(listOf("browse:null", "next:null"), requests)
    }

    @Test fun `137 entries load across independent page sizes and reversed target order using stable IDs`() = runBlocking {
        val largeSources = (0 until 137).map { index -> generated(sources.first(), "ljUtuoFt-8c", "src%08d".format(index), index) }
        val largeTargets = (0 until 137).map { index -> generated(targets.first(), "hTWKbfoikeg", "tgt%08d".format(index), index) }.reversed()
        val sourcePages = largeSources.chunked(50)
        val targetPages = largeTargets.chunked(80)
        val browseRequests = mutableListOf<String?>()
        val nextRequests = mutableListOf<String?>()
        val result = loadPlaylistSongReferences(id, "en", fetchBrowse = { token ->
            browseRequests += token
            val index = token?.removePrefix("browse-")?.toInt() ?: 0
            val nextToken = if (index < sourcePages.lastIndex) "browse-${index + 1}" else null
            if (index == 0) initialBrowse(sourcePages[index], nextToken)
            else browseContinuation(sourcePages[index], nextToken)
        }, fetchNext = { token ->
            nextRequests += token
            val index = token?.removePrefix("next-")?.toInt() ?: 0
            val nextToken = if (index < targetPages.lastIndex) "next-${index + 1}" else null
            if (index == 0) initialNext(targetPages[index], nextToken)
            else nextContinuation(targetPages[index], nextToken)
        })
        assertEquals(listOf(null, "browse-1", "browse-2"), browseRequests)
        assertEquals(listOf(null, "next-1"), nextRequests)
        assertEquals(137, result.references.size)
        assertTrue(result.references.all { it.sourceVideoId.removePrefix("src") == it.targetVideoId.removePrefix("tgt") })
        assertEquals(137, result.sourceSongs.size)
        assertTrue(result.targetSongs.isEmpty())
    }

    @Test fun `row tokens append actions and section continuation retain canonical playlist scope`() = runBlocking {
        val requests = mutableListOf<String?>()
        val initial = initialBrowse(sources.take(4), null).update(shelf + "contents") { JsonArray(it.jsonArray + marker("append-1")) }
        val result = loadPlaylistSongReferences(id, "en", fetchBrowse = { token ->
            requests += token
            when (token) {
                null -> initial
                "append-1" -> appended(sources.subList(4, 8) + marker("section-2"))
                "section-2" -> obj("continuationContents" to obj("sectionListContinuation" to
                    obj("targetId" to JsonPrimitive("VL$id"), "contents" to JsonArray(listOf(
                        obj("musicPlaylistShelfRenderer" to obj("targetId" to JsonPrimitive(id), "contents" to JsonArray(sources.drop(8)))))))))
                else -> error("Unexpected token")
            }
        }, fetchNext = { next })
        assertEquals(parsePlaylistSongReferences(browse, next, id, "en"), result)
        assertEquals(listOf(null, "append-1", "section-2"), requests)
    }

    @Test fun `result is withheld until the last next page completes and its set matches`() = runBlocking {
        val lastPage = CompletableDeferred<JsonElement>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            loadPlaylistSongReferences(id, "en", fetchBrowse = { browse }, fetchNext = { token ->
                if (token == null) initialNext(targets.take(5), "remaining") else lastPage.await()
            })
        }
        assertFalse(job.isCompleted)
        lastPage.complete(nextContinuation(targets.drop(5)))
        assertEquals(13, job.await().references.size)
    }

    @Test fun `failed empty missing and incomplete later pages never return an earlier partial observation`() = runBlocking {
        for (badPage in listOf(obj(), browseContinuation(emptyList()), browseContinuation(sources.drop(5).dropLast(1)))) {
            assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { token ->
                if (token == null) initialBrowse(sources.take(5), "remaining") else badPage
            }, fetchNext = { next }) }.isFailure)
        }
        assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { browse }, fetchNext = { token ->
            if (token == null) initialNext(targets.take(5), "remaining") else throw IOException("page unavailable")
        }) }.isFailure)
        assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { browse }, fetchNext = { token ->
            if (token == null) initialNext(targets.take(5), "remaining") else nextContinuation(targets.drop(5).dropLast(1))
        }) }.isFailure)
    }

    @Test fun `duplicate entries across page boundaries fail even when identical`() = runBlocking {
        assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { token ->
            if (token == null) initialBrowse(sources.take(5), "remaining") else browseContinuation(sources.drop(4))
        }, fetchNext = { next }) }.isFailure)
        assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { browse }, fetchNext = { token ->
            if (token == null) initialNext(targets.take(5), "remaining") else nextContinuation(targets.drop(4))
        }) }.isFailure)
    }

    @Test fun `repeated tokens conflicting tokens and page limits fail without extra requests`() = runBlocking {
        val requests = mutableListOf<String?>()
        assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { token ->
            requests += token
            if (token == null) initialBrowse(sources.take(5), "loop") else browseContinuation(sources.drop(5), "loop")
        }, fetchNext = { next }) }.isFailure)
        assertEquals(listOf(null, "loop"), requests)
        requests.clear()
        assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { token ->
            requests += token
            if (token == null) initialBrowse(sources.take(5), "next-1") else browseContinuation(sources.drop(5), "next-2")
        }, fetchNext = { next }, maxPages = 2) }.isFailure)
        assertEquals(listOf(null, "next-1"), requests)
        val conflicting = initialBrowse(sources.take(5), "container-token").update(shelf + "contents") {
            JsonArray(it.jsonArray + marker("different-row-token"))
        }
        assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { conflicting }, fetchNext = { next }) }.isFailure)
    }

    @Test fun `foreign playlist scopes endpoints and replacement pages reject the whole chain`() = runBlocking {
        val badBrowse = listOf(
            browseContinuation(sources.drop(5), scope = "OLAK5uy_wrong"),
            appended(sources.drop(5), scope = "OLAK5uy_wrong"),
            browse,
        )
        for (page in badBrowse) assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { token ->
            if (token == null) initialBrowse(sources.take(5), "remaining") else page
        }, fetchNext = { next }) }.isFailure)
        val foreignRow = targets[5].update(listOf("playlistPanelVideoRenderer", "navigationEndpoint", "watchEndpoint", "playlistId")) {
            JsonPrimitive("OLAK5uy_wrong")
        }
        val badNext = listOf(nextContinuation(targets.drop(5), scope = "OLAK5uy_wrong"),
            nextContinuation(listOf(foreignRow) + targets.drop(6)), next)
        for (page in badNext) assertTrue(runCatching { loadPlaylistSongReferences(id, "en", fetchBrowse = { browse }, fetchNext = { token ->
            if (token == null) initialNext(targets.take(5), "remaining") else page
        }) }.isFailure)
    }

    @Test fun `radio token representation remains finite and never follows automix recommendations`() = runBlocking {
        val initial = initialNext(targets.take(5), null).update(panel) {
            JsonObject(it.jsonObject + ("continuations" to tokenData("remaining", "nextRadioContinuationData")))
        }
        val result = loadPlaylistSongReferences(id, "en", fetchBrowse = { browse }, fetchNext = { token ->
            if (token == null) initial else nextContinuation(targets.drop(5) + obj("automixPreviewVideoRenderer" to obj()))
        })
        assertEquals(13, result.references.size)
    }

    private fun initialBrowse(rows: List<JsonElement>, token: String?) = browse.update(shelf) {
        container(it.jsonObject + ("contents" to JsonArray(rows)), token)
    }
    private fun initialNext(rows: List<JsonElement>, token: String?): JsonElement {
        val endpoint = rows.first().at(listOf("playlistPanelVideoRenderer", "navigationEndpoint", "watchEndpoint"))
        return next.update(panel) { container(it.jsonObject + ("contents" to JsonArray(rows)), token) }
            .update(listOf("currentVideoEndpoint")) { obj("watchEndpoint" to endpoint) }
    }
    private fun browseContinuation(rows: List<JsonElement>, token: String? = null, scope: String? = null) =
        obj("continuationContents" to obj("musicPlaylistShelfContinuation" to container(
            mapOf("contents" to JsonArray(rows)) + scope?.let { mapOf("targetId" to JsonPrimitive(it)) }.orEmpty(), token)))
    private fun nextContinuation(rows: List<JsonElement>, token: String? = null, scope: String? = null) =
        obj("continuationContents" to obj("playlistPanelContinuation" to container(
            mapOf("contents" to JsonArray(rows)) + scope?.let { mapOf("playlistId" to JsonPrimitive(it)) }.orEmpty(), token)))
    private fun appended(rows: List<JsonElement>, scope: String = id) = obj("onResponseReceivedActions" to JsonArray(listOf(
        obj("appendContinuationItemsAction" to obj("targetId" to JsonPrimitive(scope), "continuationItems" to JsonArray(rows))))))
    private fun tokenData(token: String, kind: String = "nextContinuationData") = JsonArray(listOf(
        obj(kind to obj("continuation" to JsonPrimitive(token)))))
    private fun marker(token: String) = obj("continuationItemRenderer" to obj("continuationEndpoint" to
        obj("continuationCommand" to obj("token" to JsonPrimitive(token)))))
    private fun container(fields: Map<String, JsonElement>, token: String?) = JsonObject(fields +
        token?.let { mapOf("continuations" to tokenData(it)) }.orEmpty())
    private fun generated(row: JsonElement, oldId: String, newId: String, index: Int) = Json.parseToJsonElement(
        row.toString().replace(oldId, newId).replace("89EB92D23D2DDB11", "entry%04d".format(index)))
    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))
    private fun JsonElement.at(path: List<String>): JsonElement = path.fold(this) { value, key ->
        if (value is JsonArray) value[key.toInt()] else value.jsonObject.getValue(key)
    }
    private fun JsonElement.update(path: List<String>, transform: (JsonElement) -> JsonElement): JsonElement {
        if (path.isEmpty()) return transform(this)
        val key = path.first()
        return when (this) {
            is JsonObject -> JsonObject(this + (key to getValue(key).update(path.drop(1), transform)))
            is JsonArray -> JsonArray(mapIndexed { index, item -> if (index == key.toInt()) item.update(path.drop(1), transform) else item })
            else -> error("Not a JSON container")
        }
    }
}
