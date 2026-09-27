package com.zionhuang.innertube

import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.UnavailablePlaylistSourceEntry
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedPlaylistSongReferenceTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
        "/playlist-song-reference/1989-deluxe-restricted.json")).readText()).jsonObject
    private val id = fixture.getValue("playlistId").jsonPrimitive.content
    private val browse = fixture.getValue("browse")
    private val next = fixture.getValue("next")
    private val section = listOf("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
    private val shelf = section + listOf("contents", "0", "musicPlaylistShelfRenderer")
    private val panel = listOf("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
        "watchNextTabbedResultsRenderer", "tabs", "0", "tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer")
    private val sources = browse.at(shelf + "contents").jsonArray
    private val omittedRow = shelf + listOf("contents", "16", "musicResponsiveListItemRenderer")

    @Test fun `1989 terminal queue preserves all 16 witnessed pairs and reports its three restricted omissions`() {
        val result = requireNotNull(parse())
        assertEquals(16, result.references.size)
        assertEquals(16, result.sourceSongs.size)
        assertEquals(16, result.targetSongs.size)
        assertFalse(result.hasCompleteIdentityCoverage)
        assertEquals(listOf(
            UnavailablePlaylistSourceEntry("19D1B75F81881B33", "cV73DqEtqs8"),
            UnavailablePlaylistSourceEntry("14A9062AE2470C6E", "16kF9RwwcM4"),
            UnavailablePlaylistSourceEntry("3653FFDA987CA820", "4tegR9aUobo"),
        ), result.unavailableSourceEntries)
        assertEquals(PlaylistSongReference(id, "A5B03A001F101332", "YXspld9F7-A", "JhICcmiIE80"),
            result.references.single { it.targetVideoId == "JhICcmiIE80" })
        assertEquals(PlaylistSongReference(id, "67000BB798216A47", "KsPK-waqvf0", "wyK7YuwUWsU"),
            result.references.single { it.targetVideoId == "wyK7YuwUWsU" })
        assertEquals(result.references.map { it.sourceVideoId }, result.sourceSongs.map { it.id })
        assertTrue(result.unavailableSourceEntries.none { missing ->
            result.references.any { it.playlistSetVideoId == missing.playlistSetVideoId }
        })
    }

    @Test fun `partial identity joins still use stable keys when next order and text change`() {
        val reordered = next.update(panel + "contents") { JsonArray(it.jsonArray.reversed()) }
        assertEquals(parse()?.references, parse(next = reordered)?.references)
        val renamed = browse.update(shelf + listOf("contents", "13", "musicResponsiveListItemRenderer",
            "flexColumns", "0", "musicResponsiveListItemFlexColumnRenderer", "text", "runs", "0", "text")) {
            JsonPrimitive("Unrelated title")
        }
        assertEquals(parse()?.references, parse(browse = renamed)?.references)
    }

    @Test fun `missing restriction evidence unknown policy or a playback endpoint cannot authorize omission`() {
        for (policy in listOf(JsonNull, JsonPrimitive("UNKNOWN_POLICY"), JsonPrimitive("false"))) {
            assertNull(parse(browse = browse.update(omittedRow + "musicItemRendererDisplayPolicy") { policy }))
        }
        assertNull(parse(browse = browse.update(omittedRow) { JsonObject(it.jsonObject - "musicItemRendererDisplayPolicy") }))
        val playable = browse.update(omittedRow) { JsonObject(it.jsonObject + ("navigationEndpoint" to obj(
            "watchEndpoint" to obj("videoId" to JsonPrimitive("cV73DqEtqs8"))))) }
        assertNull(parse(browse = playable))
        // The queue silently dropping one ordinary track is not the known restricted case.
        assertNull(parse(next = next.update(panel + "contents") {
            JsonArray(it.jsonArray.filterIndexed { index, _ -> index != 15 })
        }))
    }

    @Test fun `partial coverage does not forgive foreign or duplicate identities`() {
        assertNull(parse(browse = browse.update(shelf + "contents") { JsonArray(it.jsonArray.drop(1)) }))
        assertNull(parse(browse = browse.update(shelf + "contents") { JsonArray(it.jsonArray + it.jsonArray.last()) }))
        assertNull(parse(next = next.update(panel + "contents") { JsonArray(it.jsonArray + it.jsonArray.first()) }))
        assertNull(parse(next = Json.parseToJsonElement(next.toString().replace("67000BB798216A47", "unknown-entry"))))
        assertNull(parse(next = Json.parseToJsonElement(next.toString().replace(id, "OLAK5uy_foreign"))))
        assertNull(parse(next = next.update(listOf("currentVideoEndpoint", "watchEndpoint", "playlistSetVideoId")) {
            JsonPrimitive("19D1B75F81881B33")
        }))
        assertNull(parse(browse = browse.update(omittedRow + listOf("playlistItemData", "videoId")) { JsonPrimitive("bad") }))
        assertNull(parse(browse = browse.update(omittedRow + "flexColumns") { JsonArray(emptyList()) }))
    }

    @Test fun `every direct browse playback endpoint must agree even when another valid endpoint exists`() {
        val firstRow = shelf + listOf("contents", "0", "musicResponsiveListItemRenderer")
        val conflicting = obj("watchEndpoint" to obj("videoId" to JsonPrimitive("abcdefghijk")))
        assertNull(parse(browse = browse.update(firstRow) {
            JsonObject(it.jsonObject + ("navigationEndpoint" to conflicting))
        }))
        assertNull(parse(browse = browse.update(firstRow + listOf("flexColumns", "0",
            "musicResponsiveListItemFlexColumnRenderer", "text", "runs", "0")) {
            JsonObject(it.jsonObject + ("navigationEndpoint" to conflicting))
        }))
        assertNull(parse(browse = browse.update(firstRow + listOf("overlay", "musicItemThumbnailOverlayRenderer",
            "content", "musicPlayButtonRenderer", "playNavigationEndpoint")) {
            JsonObject(it.jsonObject + ("watchPlaylistEndpoint" to obj("playlistId" to JsonPrimitive("foreign"))))
        }))
    }

    @Test fun `finite terminal evidence remains required for restricted partial coverage`() {
        assertNull(parse(next = next.update(panel + "isInfinite") { JsonPrimitive(true) }))
        assertNull(parse(next = next.update(panel) { JsonObject(it.jsonObject + ("continuations" to token("remaining"))) }))
        assertNull(parse(browse = browse.update(shelf) { JsonObject(it.jsonObject + ("continuations" to token("remaining"))) }))
    }

    @Test fun `availability notice is informational and must identify the same playlist`() {
        val message = panel + listOf("contents", "16", "playlistExpandableMessageRenderer")
        val url = message + listOf("button", "buttonRenderer", "navigationEndpoint", "urlEndpoint", "url")
        // Keep the real response's message and trailing automix row in the fixture. Dropping
        // all non-video rows from a fixture hides the production response's actual parse path.
        assertEquals(setOf("playlistExpandableMessageRenderer"), next.at(panel + listOf("contents", "16")).jsonObject.keys)
        assertEquals(setOf("automixPreviewVideoRenderer"), next.at(panel + listOf("contents", "17")).jsonObject.keys)
        for (invalid in listOf(
            JsonPrimitive("/playlist?list=OLAK5uy_foreign"), JsonPrimitive("https://example.invalid/playlist?list=$id"),
            JsonPrimitive("https://youtube.com.evil.invalid/playlist?list=$id"),
            JsonPrimitive("https://music.youtube.com@evil.invalid/playlist?list=$id"),
            JsonPrimitive("http://music.youtube.com/playlist?list=$id"), JsonPrimitive("/watch?list=$id"),
            JsonPrimitive("/playlist?list=$id&list=$id"), JsonPrimitive("/playlist?list=$id&l%69st=other"),
            JsonPrimitive("/playlist?list=%zz"), JsonNull,
        )) {
            assertNull(parse(next = next.update(url) { invalid }))
        }
        for (samePlaylist in listOf("/playlist?list=$id&hl=ja", "https://music.youtube.com/playlist?list=$id",
            "https://www.youtube.com/playlist?feature=share&list=$id", "https://youtube.com/playlist?l%69st=$id")) {
            assertEquals(parse()?.references, parse(next = next.update(url) { JsonPrimitive(samePlaylist) })?.references)
        }
        assertNull(parse(next = next.update(message) { JsonNull }))
        assertNull(parse(next = next.update(message + listOf("button", "buttonRenderer")) {
            JsonObject(it.jsonObject - "navigationEndpoint")
        }))
        assertNull(parse(next = next.update(panel + listOf("contents", "16")) {
            JsonObject(it.jsonObject + ("unknownRenderer" to obj()))
        }))
        assertNull(parse(browse = browse.update(omittedRow) {
            JsonObject(it.jsonObject - "musicItemRendererDisplayPolicy")
        }))
        assertEquals(parse()?.references, parse(next = next.update(panel + "contents") {
            JsonArray(it.jsonArray.filter { row -> "playlistExpandableMessageRenderer" !in row.jsonObject })
        })?.references)
    }

    @Test fun `optional section scope cannot contradict the canonical playlist`() {
        for (value in listOf(JsonPrimitive("OLAK5uy_foreign"), JsonNull, JsonPrimitive(1))) {
            assertNull(parse(browse = browse.update(section) { JsonObject(it.jsonObject + ("playlistId" to value)) }))
        }
        assertEquals(parse(), parse(browse = browse.update(section) {
            JsonObject(it.jsonObject + ("playlistId" to JsonPrimitive(id)))
        }))
    }

    @Test fun `secondary next and current endpoints cannot hide conflicting identities`() {
        val locations = listOf(panel + listOf("contents", "0", "playlistPanelVideoRenderer", "navigationEndpoint"),
            listOf("currentVideoEndpoint"))
        for (location in locations) {
            val primary = next.at(location + "watchEndpoint").jsonObject
            for (field in listOf("videoId", "playlistSetVideoId", "playlistId")) {
                val contradictory = JsonObject(primary + (field to JsonPrimitive("foreign0000")))
                assertNull(parse(next = next.update(location) {
                    JsonObject(it.jsonObject + ("watchPlaylistEndpoint" to contradictory))
                }))
            }
            assertNull(parse(next = next.update(location) {
                JsonObject(it.jsonObject + ("watchPlaylistEndpoint" to JsonNull))
            }))
            assertEquals(parse(), parse(next = next.update(location) {
                JsonObject(it.jsonObject + ("watchPlaylistEndpoint" to primary))
            }))
        }
    }

    @Test fun `paginated restricted observation waits for the terminal page and preserves exact omissions`() = runBlocking {
        val lastPage = CompletableDeferred<JsonElement>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            loadPlaylistSongReferences(id, "en", fetchBrowse = { page ->
                if (page == null) browse.update(shelf) { JsonObject(it.jsonObject + mapOf(
                    "contents" to JsonArray(sources.take(10)), "continuations" to token("remaining"))) }
                else lastPage.await()
            }, fetchNext = { next })
        }
        assertFalse(job.isCompleted)
        lastPage.complete(obj("continuationContents" to obj("musicPlaylistShelfContinuation" to
            obj("contents" to JsonArray(sources.drop(10))))))
        assertEquals(parse(), job.await())
    }

    @Test fun `restricted rows do not permit failed looping or duplicate later pages to leak pairs`() = runBlocking {
        val initial = browse.update(shelf) { JsonObject(it.jsonObject + mapOf(
            "contents" to JsonArray(sources.take(16)), "continuations" to token("remaining"))) }
        for (mode in listOf("failure", "loop", "duplicate")) {
            assertTrue(runCatching {
                loadPlaylistSongReferences(id, "en", fetchBrowse = { page ->
                    if (page == null) initial else {
                        if (mode == "failure") throw IOException("last page unavailable")
                        val rows = if (mode == "duplicate") sources.drop(15) else sources.drop(16)
                        val fields = mapOf("contents" to JsonArray(rows)) +
                            if (mode == "loop") mapOf("continuations" to token("remaining")) else emptyMap()
                        obj("continuationContents" to obj("musicPlaylistShelfContinuation" to JsonObject(fields)))
                    }
                }, fetchNext = { next })
            }.isFailure)
        }
    }

    private fun parse(browse: JsonElement = this.browse, next: JsonElement = this.next) =
        parsePlaylistSongReferences(browse, next, id, "en")
    private fun token(value: String) = JsonArray(listOf(obj("nextContinuationData" to obj("continuation" to JsonPrimitive(value)))))
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
