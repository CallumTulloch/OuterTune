package com.zionhuang.innertube

import com.zionhuang.innertube.models.PlaylistSongReference
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

class PlaylistSongReferenceParserTest {
    private val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
        "/playlist-song-reference/nevermind-complete.json",
    )).readText()).jsonObject
    private val playlistId = fixture.getValue("playlistId").jsonPrimitive.content
    private val browse = fixture.getValue("browse")
    private val next = fixture.getValue("next")
    private val sectionPath = listOf("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
    private val shelfPath = sectionPath + listOf("contents", "0", "musicPlaylistShelfRenderer")
    private val panelPath = listOf("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
        "watchNextTabbedResultsRenderer", "tabs", "0", "tabRenderer", "content", "musicQueueRenderer",
        "content", "playlistPanelRenderer")
    private val sourcePath = shelfPath + listOf("contents", "0", "musicResponsiveListItemRenderer")
    private val targetPath = panelPath + listOf("contents", "0", "playlistPanelVideoRenderer")

    @Test fun `saved Music responses identify all 13 tracks even when targets are region blocked`() {
        val result = requireNotNull(parse())
        assertEquals(13, result.references.size)
        assertEquals(13, result.sourceSongs.size)
        assertEquals(0, result.targetSongs.size)
        assertTrue(result.hasCompleteIdentityCoverage)
        assertTrue(result.unavailableSourceEntries.isEmpty())
        assertEquals(PlaylistSongReference(playlistId, "5A39A74538F3ADE6", "ox_BG6sLPq8", "J6EDW5WFb2M"),
            result.references.single { it.targetVideoId == "J6EDW5WFb2M" })
        assertEquals(PlaylistSongReference(playlistId, "2A09C19594D466EF", "_oWUgfpGi0M", "pkcJEvMcnEg"),
            result.references.single { it.targetVideoId == "pkcJEvMcnEg" })
        assertEquals("Smells Like Teen Spirit", result.sourceSongs.first().title)
        assertFalse(result.sourceSongs.first().isPlayable)
        assertNull(result.sourceSongs.first().album)
        assertEquals(result.references.map { it.playlistSetVideoId }, result.sourceSongs.map { it.setVideoId })
    }

    @Test fun `target names are included only when separately present in the next response`() {
        val named = next.update(targetPath) { row -> JsonObject(row.jsonObject + mapOf(
            "title" to Json.parseToJsonElement("""{"runs":[{"text":"Independent video title"}]}"""),
            "lengthText" to Json.parseToJsonElement("""{"runs":[{"text":"3:00"}]}"""),
            "longBylineText" to Json.parseToJsonElement("""{"runs":[{"text":"Nirvana"}]}"""),
        )) }
        val result = requireNotNull(parse(next = named))
        assertEquals("Independent video title", result.targetSongs.single().title)
        assertEquals("Smells Like Teen Spirit", result.sourceSongs.first().title)
        assertEquals(parse()?.references, result.references)
    }

    @Test fun `joins stable keys despite reordered targets and unrelated names`() {
        val reordered = next.update(panelPath + "contents") { JsonArray(it.jsonArray.reversed()) }
        // Reordering alone must not change a single association. Text is not an identity input.
        assertEquals(parse()?.references, parse(next = reordered)?.references)
        val differentTitle = browse.update(sourcePath + listOf("flexColumns", "0",
            "musicResponsiveListItemFlexColumnRenderer", "text", "runs", "0", "text")) { JsonPrimitive("別の表示名") }
        assertEquals(parse()?.references, parse(browse = differentTitle)?.references)
    }

    @Test fun `self pairs remain in the complete observation`() {
        val selfNext = Json.parseToJsonElement(next.toString().replace("hTWKbfoikeg", "ljUtuoFt-8c"))
        val result = requireNotNull(parse(next = selfNext))
        assertEquals(13, result.references.size)
        assertEquals("ljUtuoFt-8c", result.references.first().targetVideoId)
    }

    @Test fun `wrong or missing playlist identities reject the entire observation`() {
        val wrong = JsonPrimitive("OLAK5uy_wrong")
        listOf(sectionPath + "targetId", shelfPath + "targetId").forEach { path ->
            assertNull(parse(browse = browse.update(path) { wrong }))
            assertNull(parse(browse = browse.update(path) { JsonNull }))
        }
        listOf(panelPath + "playlistId", targetPath + listOf("navigationEndpoint", "watchEndpoint", "playlistId"),
            listOf("currentVideoEndpoint", "watchEndpoint", "playlistId")).forEach { path ->
            assertNull(parse(next = next.update(path) { wrong }))
            assertNull(parse(next = next.update(path) { JsonNull }))
        }
        assertNull(parse(expectedPlaylistId = "VL$playlistId"))
        assertNull(parse(expectedPlaylistId = "../invalid"))
    }

    @Test fun `duplicate keys or source and target video ids are ambiguous even when rows match`() {
        assertNull(parse(browse = browse.update(shelfPath + "contents") { JsonArray(it.jsonArray + it.jsonArray.first()) }))
        assertNull(parse(next = next.update(panelPath + "contents") { JsonArray(it.jsonArray + it.jsonArray.first()) }))
        assertNull(parse(browse = Json.parseToJsonElement(browse.toString().replace("ng_vqlKtxLs", "ljUtuoFt-8c"))))
        assertNull(parse(next = Json.parseToJsonElement(next.toString().replace("PbgKEjNBHqM", "hTWKbfoikeg"))))
    }

    @Test fun `missing sources missing current target and mismatched stable keys cannot become a partial success`() {
        assertNull(parse(browse = browse.update(shelfPath + "contents") { JsonArray(it.jsonArray.dropLast(1)) }))
        assertNull(parse(next = next.update(panelPath + "contents") { JsonArray(it.jsonArray.drop(1)) }))
        assertNull(parse(next = Json.parseToJsonElement(next.toString().replace("89EB92D23D2DDB11", "differentEntry"))))
        assertNull(parse(browse = browse.update(shelfPath + "contents") { JsonArray(emptyList()) }))
    }

    @Test fun `pending pagination infinite queues and unknown row renderers remain incomplete`() {
        val continuation = JsonArray(listOf(JsonObject(mapOf("nextContinuationData" to JsonObject(emptyMap())))))
        listOf(sectionPath, shelfPath).forEach { path ->
            assertNull(parse(browse = browse.update(path) { JsonObject(it.jsonObject + ("continuations" to continuation)) }))
        }
        assertNull(parse(next = next.update(panelPath) { JsonObject(it.jsonObject + ("continuations" to continuation)) }))
        assertNull(parse(next = next.update(panelPath + "isInfinite") { JsonPrimitive(true) }))
        assertNull(parse(next = next.update(panelPath + "isInfinite") { JsonPrimitive("false") }))
        assertNull(parse(next = next.update(panelPath + "isInfinite") { JsonNull }))
        val unknown = JsonObject(mapOf("continuationItemRenderer" to JsonObject(emptyMap())))
        assertNull(parse(next = next.update(panelPath + "contents") { JsonArray(it.jsonArray + unknown) }))
        assertNull(parse(next = next.update(panelPath + listOf("contents", "0")) { JsonObject(it.jsonObject + unknown) }))
        assertNull(parse(browse = browse.update(shelfPath + "contents") { JsonArray(it.jsonArray + unknown) }))
        assertNull(parse(next = JsonObject(next.jsonObject + ("continuationContents" to JsonObject(emptyMap())))))
        assertNull(parse(browse = browse.update(shelfPath) { JsonObject(it.jsonObject + ("continuations" to JsonNull)) }))
    }

    @Test fun `malformed IDs endpoints or unparseable row metadata cannot silently drop tracks`() {
        listOf("videoId", "playlistSetVideoId").forEach { key ->
            listOf(JsonNull, JsonPrimitive(123), JsonPrimitive("bad ID")).forEach { invalid ->
                assertNull(parse(browse = browse.update(sourcePath + listOf("playlistItemData", key)) { invalid }))
                assertNull(parse(next = next.update(targetPath + key) { invalid }))
            }
            assertNull(parse(next = next.update(targetPath + listOf("navigationEndpoint", "watchEndpoint", key)) {
                JsonPrimitive("abcdefghijk")
            }))
        }
        assertNull(parse(browse = browse.update(sourcePath + "flexColumns") { JsonArray(emptyList()) }))
        assertNull(parse(next = next.update(listOf("currentVideoEndpoint", "watchEndpoint", "videoId")) {
            JsonPrimitive("abcdefghijk")
        }))
        assertNull(parse(browse = JsonObject(emptyMap())))
        assertNull(parse(next = JsonArray(emptyList())))
    }

    @Test fun `multiple playlist shelves or queue panels do not allow choosing the first match`() {
        assertNull(parse(browse = browse.update(sectionPath + "contents") { JsonArray(it.jsonArray + it.jsonArray.first()) }))
        val tabsPath = panelPath.take(5)
        assertNull(parse(next = next.update(tabsPath) { JsonArray(it.jsonArray + it.jsonArray.first()) }))
    }

    private fun parse(browse: JsonElement = this.browse, next: JsonElement = this.next,
        expectedPlaylistId: String = playlistId) = parsePlaylistSongReferences(browse, next, expectedPlaylistId, "en")

    private fun JsonElement.update(path: List<String>, transform: (JsonElement) -> JsonElement): JsonElement {
        if (path.isEmpty()) return transform(this)
        val key = path.first()
        return when (this) {
            is JsonObject -> JsonObject(this + (key to getValue(key).update(path.drop(1), transform)))
            is JsonArray -> JsonArray(mapIndexed { index, item -> if (index == key.toInt()) item.update(path.drop(1), transform) else item })
            else -> error("Not a JSON container at $key")
        }
    }
}
