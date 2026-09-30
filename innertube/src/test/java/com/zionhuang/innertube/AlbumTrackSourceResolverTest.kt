@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.zionhuang.innertube

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.getItems
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.pages.AlbumPage
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

/** Saved public Music responses from 2026-09-30; no network or title/order matching. */
class AlbumTrackSourceResolverTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val albumId = "MPREb_dqWTncCjkSp"
    private val albumResponse = json.decodeFromJsonElement<BrowseResponse>(fixture("album-track-source/album-thriller"))
    private val album = AlbumPage.getAlbum(albumId, albumResponse, "en")
    private val playlistId = requireNotNull(album.playlistId)
    private val rows = requireNotNull(AlbumPage.trackContents(albumResponse)).getItems()
    private val browse = fixture("album-track-source/thriller-playlist-browse")
    private val section = listOf("contents", "twoColumnBrowseResultsRenderer", "secondaryContents", "sectionListRenderer")
    private val shelf = section + listOf("contents", "0", "musicPlaylistShelfRenderer")
    private val sourceRows = browse.at(shelf + "contents").jsonArray.toList()
    private val source = shelf + listOf("contents", "5", "musicResponsiveListItemRenderer")
    private val sourceEndpoint = source + listOf("overlay", "musicItemThumbnailOverlayRenderer", "content",
        "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint")
    private val audioIds = listOf("8KWf_-ofYgI", "COSMzAASQj4", "SX5vM6F57_E", "Z85lxckrtzg", "kOn-HdEg6AQ",
        "Kr4EQDVETuA", "oqLpko9Gprs", "y32ejtuxSjM", "Eqcw7tLnrd8")

    @Test fun `Thriller uses all nine canonical audio sources with complete source metadata`() = runBlocking {
        val requests = mutableListOf<String?>()
        val result = resolveAlbumTrackSources(album, rows, "en") { requests += it; browse }
        assertEquals(listOf<String?>(null), requests)
        assertFalse(result.hasUnresolvedSources)
        assertEquals(audioIds, result.songs.map { it.id })
        assertEquals(rows.map { it.playlistItemData!!.playlistSetVideoId }, result.songs.map { it.setVideoId })
        assertTrue(result.songs.all { it.album?.id == albumId && it.album?.name == "Thriller" })
        val billie = result.songs[5]
        assertEquals("Kr4EQDVETuA", billie.id)
        assertEquals("5CFC2BF6F37BEF73", billie.setVideoId)
        assertEquals(295, billie.duration)
        val endpoint = requireNotNull(billie.endpoint)
        assertEquals(billie.id, endpoint.videoId)
        assertEquals(playlistId, endpoint.playlistId)
        assertEquals("MUSIC_VIDEO_TYPE_ATV", endpoint.watchEndpointMusicSupportedConfigs!!.watchEndpointMusicConfig.musicVideoType)
        assertEquals("Michael Jackson", billie.artists.single().name)
        assertTrue(billie.artistCredit!!.evidence.none { it.startsWith("video-source:") })
        assertTrue(result.songs.none { song -> rows.any { it.playlistItemData!!.videoId == song.id } })
    }

    @Test fun `reordered sources and different source titles still join only stable entries`() = runBlocking {
        val changed = browse.update(shelf + "contents") { values ->
            JsonArray(values.jsonArray.reversed().map { row -> row.update(listOf("musicResponsiveListItemRenderer",
                "flexColumns", "0", "musicResponsiveListItemFlexColumnRenderer", "text", "runs", "0", "text")) {
                JsonPrimitive("Unrelated title")
            } })
        }
        val result = resolve(changed)
        assertEquals(audioIds, result.songs.map { it.id })
        assertTrue(result.songs.all { it.title == "Unrelated title" })
    }

    @Test fun `unavailable shelf entries remain in order and cannot be made playable by the source`() = runBlocking {
        val restricted = rows.mapIndexed { index, row ->
            if (index == 5) row.copy(musicItemRendererDisplayPolicy = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT") else row
        }
        val result = resolve(shelfRows = restricted)
        assertEquals(audioIds, result.songs.map { it.id })
        assertEquals(9, result.songs.size)
        assertFalse(result.songs[5].isPlayable)
        assertEquals(8, result.songs.count { it.isPlayable })
    }

    @Test fun `audio shelves and metadata only paths need no canonical lookup`() = runBlocking {
        val audioRows = sourceRows.map { json.decodeFromJsonElement<MusicResponsiveListItemRenderer>(it.jsonObject.getValue("musicResponsiveListItemRenderer")) }
        val result = resolveAlbumTrackSources(album, audioRows, "en") { error("Unnecessary request") }
        assertEquals(audioIds, result.songs.map { it.id })
        assertFalse(result.hasUnresolvedSources)
        assertTrue(resolveAlbumTrackSources(album, emptyList(), "en") { error("Unnecessary request") }.songs.isEmpty())
    }

    @Test fun `missing entry evidence or canonical playlist leaves the entire shelf unresolved`() = runBlocking {
        val incomplete = rows.mapIndexed { index, row -> if (index == 5)
            row.copy(playlistItemData = row.playlistItemData!!.copy(playlistSetVideoId = null)) else row }
        for ((header, shelfRows) in listOf(album.copy(playlistId = null) to rows, album to incomplete)) {
            val result = resolveAlbumTrackSources(header, shelfRows, "en") { error("No identity evidence") }
            assertTrue(result.hasUnresolvedSources)
            assertEquals(shelfRows.map { it.playlistItemData!!.videoId }, result.songs.map { it.id })
        }
    }

    @Test fun `header only restricted playlist preserves all King Gnu tracks and restrictions`() = runBlocking {
        val response = json.decodeFromJsonElement<BrowseResponse>(fixture("album-browsing/greatest-album-ja"))
        val header = AlbumPage.getAlbum("MPREb_r2SiObOBunG", response, "ja")
        val shelfRows = requireNotNull(AlbumPage.trackContents(response)).getItems()
        val result = resolveAlbumTrackSources(header, shelfRows, "ja") { fixture("album-browsing/greatest-album-songs-ja") }
        assertTrue(result.hasUnresolvedSources)
        assertEquals(AlbumPage.getSongs(response, header, "ja"), result.songs)
        assertEquals(21, result.songs.size)
        assertEquals(13, result.songs.count { !it.isPlayable })
    }

    @Test fun `an unsupported source type withholds every substitution`() = runBlocking {
        val changed = Json.parseToJsonElement(browse.toString().replace("MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV"))
        val result = resolve(changed)
        assertTrue(result.hasUnresolvedSources)
        assertEquals(rows.map { it.playlistItemData!!.videoId }, result.songs.map { it.id })
    }

    @Test fun `foreign missing duplicate and contradictory source identities fail without partial results`() = runBlocking {
        val bad = listOf(
            browse.update(section + "targetId") { JsonPrimitive("VLforeign") },
            browse.update(shelf + "targetId") { JsonNull },
            browse.update(shelf + "contents") { JsonArray(it.jsonArray.dropLast(1)) },
            browse.update(shelf + "contents") { JsonArray(it.jsonArray + it.jsonArray.first()) },
            browse.update(source + listOf("playlistItemData", "playlistSetVideoId")) { JsonPrimitive("differentEntry") },
            browse.update(sourceEndpoint + "videoId") { JsonPrimitive("abcdefghijk") },
            browse.update(sourceEndpoint + "playlistId") { JsonPrimitive("OLAK5uy_foreign") },
            browse.update(sourceEndpoint + "playlistSetVideoId") { JsonPrimitive("differentEntry") },
            browse.update(source + "flexColumns") { JsonArray(emptyList()) },
            Json.parseToJsonElement(browse.toString().replace(albumId, "MPREb_other_album")),
            Json.parseToJsonElement(browse.toString().replace(audioIds[1], audioIds[0])),
            JsonObject(mapOf("responseContext" to JsonObject(emptyMap()), "error" to JsonObject(emptyMap()))),
        )
        for ((index, response) in bad.withIndex()) {
            assertTrue("Invalid source case $index", runCatching { resolve(response) }.isFailure)
        }
    }

    @Test fun `conflicting shelf endpoints and duplicate shelf identities cannot be concealed by parser selection`() = runBlocking {
        val wrongVideo = rows.toMutableList().also { list -> list[5] = list[5].copy(
            playlistItemData = list[5].playlistItemData!!.copy(videoId = "abcdefghijk")) }
        val duplicateEntry = rows.toMutableList().also { list -> list[5] = list[5].copy(
            playlistItemData = list[5].playlistItemData!!.copy(playlistSetVideoId = list[0].playlistItemData!!.playlistSetVideoId)) }
        for (shelfRows in listOf(wrongVideo, duplicateEntry, rows + rows.first()))
            assertTrue(runCatching { resolve(shelfRows = shelfRows) }.isFailure)
    }

    @Test fun `complete canonical continuations are collected before applying identities`() = runBlocking {
        val last = CompletableDeferred<JsonElement>()
        val requests = mutableListOf<String?>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            resolveAlbumTrackSources(album, rows, "en") { token ->
                requests += token
                if (token == null) initial(sourceRows.take(4), "remaining") else last.await()
            }
        }
        assertFalse(result.isCompleted)
        last.complete(continued(sourceRows.drop(4)))
        assertEquals(audioIds, result.await().songs.map { it.id })
        assertEquals(listOf(null, "remaining"), requests)
    }

    @Test fun `failed empty repeated and foreign continuation pages cannot publish earlier replacements`() = runBlocking {
        val badPages = listOf(continued(emptyList()), continued(sourceRows.drop(4), "remaining"),
            continued(sourceRows.drop(3)), continued(sourceRows.drop(4), scope = "foreign"), browse)
        for (bad in badPages) assertTrue(runCatching {
            resolveAlbumTrackSources(album, rows, "en") { token -> if (token == null) initial(sourceRows.take(4), "remaining") else bad }
        }.isFailure)
        val failure = IOException("Later source page failed")
        val result = runCatching { resolveAlbumTrackSources(album, rows, "en") { token ->
            if (token == null) initial(sourceRows.take(4), "remaining") else throw failure
        } }
        assertSame(failure, result.exceptionOrNull())
    }

    @Test fun `source lookup cancellation is propagated`() = runBlocking {
        val cancelled = CancellationException("Album no longer visible")
        val result = runCatching { resolveAlbumTrackSources(album, rows, "en") { throw cancelled } }
        assertSame(cancelled, result.exceptionOrNull())
    }

    private suspend fun resolve(response: JsonElement = browse, shelfRows: List<MusicResponsiveListItemRenderer> = rows,
        header: AlbumItem = album) = resolveAlbumTrackSources(header, shelfRows, "en") { response }

    private fun fixture(path: String) = json.parseToJsonElement(requireNotNull(javaClass.getResource("/$path.json")).readText())
    private fun initial(values: List<JsonElement>, token: String) = browse.update(shelf) {
        JsonObject(it.jsonObject + mapOf("contents" to JsonArray(values), "continuations" to tokens(token)))
    }
    private fun continued(values: List<JsonElement>, token: String? = null, scope: String = playlistId) = obj(
        "continuationContents" to obj("musicPlaylistShelfContinuation" to JsonObject(mapOf(
            "targetId" to JsonPrimitive(scope), "contents" to JsonArray(values)) +
            token?.let { mapOf("continuations" to tokens(it)) }.orEmpty())))
    private fun tokens(token: String) = JsonArray(listOf(obj("nextContinuationData" to obj("continuation" to JsonPrimitive(token)))))
    private fun obj(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private fun JsonElement.at(path: List<String>): JsonElement = path.fold(this) { value, key ->
        if (value is JsonArray) value[key.toInt()] else value.jsonObject.getValue(key)
    }
    private fun JsonElement.update(path: List<String>, transform: (JsonElement) -> JsonElement): JsonElement {
        if (path.isEmpty()) return transform(this)
        val key = path.first()
        return when (this) {
            is JsonObject -> JsonObject(this + (key to getValue(key).update(path.drop(1), transform)))
            is JsonArray -> JsonArray(mapIndexed { index, value -> if (index == key.toInt()) value.update(path.drop(1), transform) else value })
            else -> error("Not a JSON container")
        }
    }
}
