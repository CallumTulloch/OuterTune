package com.zionhuang.innertube

import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.PlaylistPanelVideoRenderer
import com.zionhuang.innertube.models.PlaylistSongReference
import com.zionhuang.innertube.models.PlaylistSongReferences
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.UnavailablePlaylistSourceEntry
import com.zionhuang.innertube.pages.AlbumPage
import com.zionhuang.innertube.pages.NextPage
import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement

private val playlistReferenceVideoId = Regex("[A-Za-z0-9_-]{11}")
internal val playlistReferenceToken = Regex("[A-Za-z0-9_-]{1,512}")
private const val MAX_REFERENCE_PAGES = 100
private const val MAX_REFERENCE_ENTRIES = 10_000

@OptIn(ExperimentalSerializationApi::class)
private val playlistReferenceJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

private data class ReferenceEntry(
    val entryId: String,
    val videoId: String,
    val song: SongItem?,
    val explicitlyUnavailable: Boolean = false,
)
private data class ReferencePage(
    val entries: List<ReferenceEntry>,
    val continuation: String?,
    val current: Pair<String, String>? = null,
)

/** Single-page compatibility entry point. A remaining continuation is never a complete result. */
internal fun parsePlaylistSongReferences(
    browseResponse: JsonElement,
    nextResponse: JsonElement,
    expectedPlaylistId: String,
    language: String,
): PlaylistSongReferences? = runCatching {
    require(playlistReferenceToken.matches(expectedPlaylistId))
    val browse = parseBrowseReferencePage(browseResponse, expectedPlaylistId, language, continued = false)
    val next = parseNextReferencePage(nextResponse, expectedPlaylistId, language, continued = false)
    require(browse.continuation == null && next.continuation == null)
    completePlaylistReferences(expectedPlaylistId, listOf(browse), listOf(next))
}.getOrNull()

/**
 * Follow only tokens issued by the verified playlist's own response chain. Nothing is published
 * until both chains terminate and every target has exactly one matching source.
 * Continuation pages may omit their outer playlist ID; next row endpoints still bind each target
 * to the requested playlist. Only explicitly restricted browse rows may be absent from the
 * terminal queue; their unobserved target identities are returned separately, never inferred.
 */
internal suspend fun loadPlaylistSongReferences(
    expectedPlaylistId: String,
    language: String,
    fetchBrowse: suspend (String?) -> JsonElement,
    fetchNext: suspend (String?) -> JsonElement,
    maxPages: Int = MAX_REFERENCE_PAGES,
): PlaylistSongReferences {
    require(playlistReferenceToken.matches(expectedPlaylistId))
    require(maxPages in 1..MAX_REFERENCE_PAGES)
    val browse = collectReferencePages(fetchBrowse, maxPages) { response, continued ->
        parseBrowseReferencePage(response, expectedPlaylistId, language, continued)
    }
    val next = collectReferencePages(fetchNext, maxPages) { response, continued ->
        parseNextReferencePage(response, expectedPlaylistId, language, continued)
    }
    return completePlaylistReferences(expectedPlaylistId, browse, next)
}

private suspend fun collectReferencePages(
    fetch: suspend (String?) -> JsonElement,
    maxPages: Int,
    parse: (JsonElement, Boolean) -> ReferencePage,
): List<ReferencePage> {
    val pages = mutableListOf<ReferencePage>()
    val tokens = mutableSetOf<String>()
    val entries = mutableSetOf<String>()
    val videos = mutableSetOf<String>()
    var continuation: String? = null
    while (true) {
        check(pages.size < maxPages) { "Playlist identity pagination exceeded its bound" }
        if (continuation != null) check(tokens.add(continuation)) { "Repeated playlist continuation" }
        val page = parse(fetch(continuation), pages.isNotEmpty())
        require(page.entries.isNotEmpty()) { "Empty playlist identity page" }
        page.entries.forEach { entry ->
            require(entries.add(entry.entryId) && videos.add(entry.videoId)) { "Repeated playlist entry or video" }
        }
        require(entries.size <= MAX_REFERENCE_ENTRIES) { "Playlist identity entry limit exceeded" }
        pages += page
        continuation = page.continuation ?: return pages
    }
}

private fun completePlaylistReferences(
    playlistId: String,
    browsePages: List<ReferencePage>,
    nextPages: List<ReferencePage>,
): PlaylistSongReferences {
    val sources = browsePages.flatMap { it.entries }
    val targets = nextPages.flatMap { it.entries }
    for (entries in listOf(sources, targets)) {
        require(entries.isNotEmpty())
        require(entries.map { it.entryId }.distinct().size == entries.size)
        require(entries.map { it.videoId }.distinct().size == entries.size)
    }
    val sourceEntryIds = sources.map { it.entryId }.toSet()
    val targetsByEntry = targets.associateBy { it.entryId }
    require(targetsByEntry.keys.all { it in sourceEntryIds }) { "Foreign target playlist entries" }
    val omittedSources = sources.filter { it.entryId !in targetsByEntry }
    require(omittedSources.all { it.explicitlyUnavailable }) { "Unexplained playlist identity omissions" }
    nextPages.mapNotNull { it.current }.forEach { (entryId, videoId) ->
        require(targetsByEntry[entryId]?.videoId == videoId) { "Foreign current playlist entry" }
    }
    val matchedSources = sources.filter { it.entryId in targetsByEntry }
    return PlaylistSongReferences(
        playlistId,
        matchedSources.map { PlaylistSongReference(playlistId, it.entryId, it.videoId, targetsByEntry.getValue(it.entryId).videoId) },
        matchedSources.map { requireNotNull(it.song) },
        targets.mapNotNull { it.song },
        omittedSources.map { UnavailablePlaylistSourceEntry(it.entryId, it.videoId) },
    )
}

private fun parseBrowseReferencePage(
    response: JsonElement,
    playlistId: String,
    language: String,
    continued: Boolean,
): ReferencePage {
    val root = response as? JsonObject ?: error("Malformed playlist browse response")
    val containers: List<JsonObject>
    val contents: JsonArray
    if (!continued) {
        root.requireNoResponseContinuation()
        val section = root.playlistPath("contents", "twoColumnBrowseResultsRenderer", "secondaryContents",
            "sectionListRenderer") as? JsonObject ?: error("Missing playlist browse section")
        require(section.playlistText("targetId") == "VL$playlistId")
        section.requireOptionalPlaylistText("playlistId", playlistId)
        val shelf = section.referenceShelf()
        require(shelf.playlistText("targetId") == playlistId)
        shelf.requireOptionalPlaylistText("playlistId", playlistId)
        containers = listOf(section, shelf)
        contents = shelf.playlistArray("contents") ?: error("Missing playlist browse rows")
    } else {
        require(!root.containsKey("contents")) { "Playlist continuation unexpectedly replaced its initial page" }
        val continuation = root.referenceContinuationContainer()
        when (continuation.first) {
            "musicPlaylistShelfContinuation" -> {
                val shelf = continuation.second
                shelf.requireOptionalPlaylistScope(playlistId)
                containers = listOf(shelf)
                contents = shelf.playlistArray("contents") ?: error("Missing continued playlist rows")
            }
            "sectionListContinuation" -> {
                val section = continuation.second
                section.requireOptionalPlaylistScope(playlistId)
                val shelf = section.referenceShelf()
                shelf.requireOptionalPlaylistScope(playlistId)
                containers = listOf(section, shelf)
                contents = shelf.playlistArray("contents") ?: error("Missing continued playlist shelf rows")
            }
            "appendContinuationItemsAction" -> {
                val action = continuation.second
                action.requireOptionalPlaylistScope(playlistId)
                containers = listOf(action)
                contents = action.playlistArray("continuationItems") ?: error("Missing appended playlist rows")
            }
            else -> error("Unexpected playlist browse continuation")
        }
    }
    val (rows, token) = referenceRowsAndToken(contents, containers)
    val entries = rows.map { item ->
        require(item.keys == setOf("musicResponsiveListItemRenderer"))
        val row = item["musicResponsiveListItemRenderer"] as? JsonObject ?: error("Malformed playlist browse row")
        val data = row.playlistField("playlistItemData") ?: error("Missing playlist item identity")
        val videoId = data.playlistText("videoId") ?: error("Missing source video ID")
        val entryId = data.playlistText("playlistSetVideoId") ?: error("Missing source entry ID")
        require(playlistReferenceVideoId.matches(videoId) && playlistReferenceToken.matches(entryId))
        val renderer = playlistReferenceJson.decodeFromJsonElement<MusicResponsiveListItemRenderer>(row)
        val song = AlbumPage.getSong(renderer, language = language) ?: error("Incomplete source song metadata")
        require(song.id == videoId)
        // PageHelper may prefer one endpoint or discard a conflicting video ID. Identity parsing
        // must validate every direct playback endpoint instead of silently choosing a good one.
        val navigationEndpoints = listOfNotNull(
            renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
                ?.playNavigationEndpoint,
            renderer.navigationEndpoint,
        ) + renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text
            ?.runs.orEmpty().mapNotNull { it.navigationEndpoint }
        val endpoints = navigationEndpoints.flatMap { listOfNotNull(it.watchEndpoint, it.watchPlaylistEndpoint) }
        endpoints.forEach { endpoint ->
            require(endpoint.videoId == null || endpoint.videoId == videoId)
            require(endpoint.playlistId == null || endpoint.playlistId == playlistId)
            require(endpoint.playlistSetVideoId == null || endpoint.playlistSetVideoId == entryId)
        }
        ReferenceEntry(entryId, videoId, song.copy(setVideoId = entryId),
            explicitlyUnavailable = renderer.musicItemRendererDisplayPolicy == "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT" &&
                endpoints.isEmpty())
    }
    require(entries.isNotEmpty()) { "Empty playlist browse page" }
    return ReferencePage(entries, token)
}

private fun parseNextReferencePage(
    response: JsonElement,
    playlistId: String,
    language: String,
    continued: Boolean,
): ReferencePage {
    val root = response as? JsonObject ?: error("Malformed playlist next response")
    val container: JsonObject
    val contents: JsonArray
    if (!continued) {
        root.requireNoResponseContinuation()
        container = root.referenceNextPanel()
        require(container.playlistText("playlistId") == playlistId)
        require(container.referenceBoolean("isInfinite") == false)
        contents = container.playlistArray("contents") ?: error("Missing playlist next rows")
    } else {
        // Some next responses include the initial header again alongside their continued rows.
        if (root.containsKey("contents")) {
            val header = root.referenceNextPanel()
            require(header.playlistText("playlistId") == playlistId)
            require(header.referenceBoolean("isInfinite") == false)
        }
        val continuation = root.referenceContinuationContainer()
        require(continuation.first in setOf("playlistPanelContinuation", "appendContinuationItemsAction"))
        container = continuation.second
        container.requireOptionalPlaylistScope(playlistId)
        if (container.containsKey("isInfinite")) require(container.referenceBoolean("isInfinite") == false)
        contents = container.playlistArray(if (continuation.first == "appendContinuationItemsAction") "continuationItems" else "contents")
            ?: error("Missing continued next rows")
    }
    val (rows, token) = referenceRowsAndToken(contents, listOf(container))
    val entries = rows.mapNotNull { item ->
        if (item.keys == setOf("automixPreviewVideoRenderer")) {
            require(item["automixPreviewVideoRenderer"] is JsonObject)
            return@mapNotNull null
        }
        if (item.keys == setOf("playlistExpandableMessageRenderer")) {
            val message = item["playlistExpandableMessageRenderer"] as? JsonObject
                ?: error("Malformed playlist availability message")
            // Music returns this informational row when a finite queue omits restricted items.
            // Its button must address this same canonical playlist. The message contributes no
            // identity and cannot justify an omission without the browse row's own restriction.
            val url = message.playlistPath("button", "buttonRenderer", "navigationEndpoint", "urlEndpoint")
                .playlistText("url")
            require(isCanonicalPlaylistNoticeUrl(url, playlistId)) { "Foreign or missing playlist availability link" }
            return@mapNotNull null
        }
        require(item.keys == setOf("playlistPanelVideoRenderer"))
        val row = item["playlistPanelVideoRenderer"] as? JsonObject ?: error("Malformed playlist next row")
        val videoId = row.playlistText("videoId") ?: error("Missing target video ID")
        val entryId = row.playlistText("playlistSetVideoId") ?: error("Missing target entry ID")
        require(playlistReferenceVideoId.matches(videoId) && playlistReferenceToken.matches(entryId))
        val endpoint = row.playlistPath("navigationEndpoint", "watchEndpoint") ?: error("Missing target endpoint")
        require(endpoint.playlistText("videoId") == videoId)
        require(endpoint.playlistText("playlistSetVideoId") == entryId)
        require(endpoint.playlistText("playlistId") == playlistId)
        (row.playlistField("navigationEndpoint") as JsonObject)
            .requireAdditionalWatchIdentity(videoId, entryId, playlistId)
        val renderer = playlistReferenceJson.decodeFromJsonElement<PlaylistPanelVideoRenderer>(row)
        // Restricted rows retain identity while omitting their names. Never copy the source name.
        val song = NextPage.fromPlaylistPanelVideoRenderer(renderer, language)?.also { require(it.id == videoId) }
        ReferenceEntry(entryId, videoId, song?.copy(setVideoId = entryId))
    }
    require(entries.isNotEmpty()) { "Empty playlist next page" }
    val current = if (!continued || root.containsKey("currentVideoEndpoint")) {
        val endpoint = root.playlistPath("currentVideoEndpoint", "watchEndpoint") ?: error("Missing current playlist endpoint")
        require(endpoint.playlistText("playlistId") == playlistId)
        val entry = endpoint.playlistText("playlistSetVideoId") ?: error("Missing current entry ID")
        val video = endpoint.playlistText("videoId") ?: error("Missing current video ID")
        require(playlistReferenceToken.matches(entry) && playlistReferenceVideoId.matches(video))
        (root.playlistField("currentVideoEndpoint") as JsonObject)
            .requireAdditionalWatchIdentity(video, entry, playlistId)
        entry to video
    } else null
    return ReferencePage(entries, token, current)
}

private fun JsonObject.referenceShelf(): JsonObject {
    val sections = playlistArray("contents") ?: error("Missing playlist sections")
    return sections.mapNotNull { it.playlistField("musicPlaylistShelfRenderer") }.singleOrNull() as? JsonObject
        ?: error("Missing or ambiguous playlist shelf")
}

private fun JsonObject.referenceNextPanel(): JsonObject {
    val tabs = playlistPath("contents", "singleColumnMusicWatchNextResultsRenderer", "tabbedRenderer",
        "watchNextTabbedResultsRenderer").playlistArray("tabs") ?: error("Missing playlist tabs")
    return tabs.mapNotNull { it.playlistPath("tabRenderer", "content", "musicQueueRenderer", "content", "playlistPanelRenderer") }
        .singleOrNull() as? JsonObject ?: error("Missing or ambiguous playlist panel")
}

private fun JsonObject.requireNoResponseContinuation() {
    require(keys.none { it in setOf("continuationContents", "onResponseReceivedActions", "onResponseReceivedEndpoints", "continuations", "continuationItemRenderer") })
}

/** These response shapes are also used by the existing browse/next continuation APIs. */
private fun JsonObject.referenceContinuationContainer(): Pair<String, JsonObject> {
    val fields = listOf("continuationContents", "onResponseReceivedActions", "onResponseReceivedEndpoints").filter(::containsKey)
    require(fields.size == 1 && !containsKey("continuations")) { "Missing or ambiguous continuation response" }
    if (fields.single() == "continuationContents") {
        val container = this["continuationContents"] as? JsonObject ?: error("Malformed continuation contents")
        require(container.size == 1)
        val (kind, value) = container.entries.single()
        return kind to (value as? JsonObject ?: error("Malformed continuation page"))
    }
    val actions = this[fields.single()] as? JsonArray ?: error("Malformed continuation actions")
    val action = actions.singleOrNull() as? JsonObject ?: error("Ambiguous continuation actions")
    require(action.keys == setOf("appendContinuationItemsAction"))
    return "appendContinuationItemsAction" to (action["appendContinuationItemsAction"] as? JsonObject
        ?: error("Missing appended continuation items"))
}

private fun referenceRowsAndToken(contents: JsonArray, containers: List<JsonObject>): Pair<List<JsonObject>, String?> {
    val tokens = mutableListOf<String>()
    containers.forEach { container ->
        if (container.containsKey("continuations")) {
            val continuations = container["continuations"] as? JsonArray ?: error("Malformed playlist continuations")
            require(continuations.size <= 1) { "Ambiguous playlist continuation tokens" }
            continuations.singleOrNull()?.let { continuation ->
                val value = continuation as? JsonObject ?: error("Malformed playlist continuation")
                val field = value.keys.singleOrNull() ?: error("Ambiguous continuation data")
                require(field in setOf("nextContinuationData", "nextRadioContinuationData"))
                tokens += requireNotNull(value[field].playlistText("continuation")) { "Missing playlist continuation token" }
            }
        }
    }
    val rows = contents.mapIndexedNotNull { index, item ->
        val value = item as? JsonObject ?: error("Malformed playlist row wrapper")
        if (value.containsKey("continuationItemRenderer")) {
            require(value.keys == setOf("continuationItemRenderer") && index == contents.lastIndex)
            tokens += value.playlistPath("continuationItemRenderer", "continuationEndpoint", "continuationCommand")
                .playlistText("token") ?: error("Missing playlist row continuation token")
            null
        } else value
    }
    tokens.forEach { require(it.isNotBlank() && it.length <= 16_384 && it.none { character -> character.isWhitespace() || character.isISOControl() }) }
    require(tokens.distinct().size <= 1) { "Conflicting playlist continuation tokens" }
    return rows to tokens.firstOrNull()
}

private fun JsonElement?.playlistField(name: String): JsonElement? = (this as? JsonObject)?.get(name)
private fun JsonElement?.playlistPath(vararg names: String): JsonElement? =
    names.fold(this) { element, name -> element.playlistField(name) }
private fun JsonElement?.playlistText(name: String): String? =
    (playlistField(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.playlistArray(name: String): JsonArray? = playlistField(name) as? JsonArray
private fun JsonObject.referenceBoolean(name: String): Boolean? = (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
private fun JsonObject.requireOptionalPlaylistText(name: String, expected: String) {
    if (containsKey(name)) require(playlistText(name) == expected)
}
private fun JsonObject.requireAdditionalWatchIdentity(videoId: String, entryId: String, playlistId: String) {
    if (!containsKey("watchPlaylistEndpoint")) return
    val endpoint = get("watchPlaylistEndpoint") as? JsonObject ?: error("Malformed secondary playback endpoint")
    endpoint.requireOptionalPlaylistText("videoId", videoId)
    endpoint.requireOptionalPlaylistText("playlistSetVideoId", entryId)
    endpoint.requireOptionalPlaylistText("playlistId", playlistId)
}
private fun isCanonicalPlaylistNoticeUrl(value: String?, playlistId: String): Boolean = runCatching {
    val uri = URI(value ?: return false)
    if (uri.scheme == null) require(uri.rawAuthority == null)
    else require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null &&
        uri.host?.lowercase() in setOf("youtube.com", "www.youtube.com", "music.youtube.com") &&
        uri.port in setOf(-1, 443))
    require(uri.rawPath == "/playlist")
    val playlistValues = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
        val parts = parameter.split('=', limit = 2)
        if (URLDecoder.decode(parts.first(), "UTF-8") != "list") null
        else URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
    }
    playlistValues.size == 1 && playlistValues.single() == playlistId
}.getOrDefault(false)
private fun JsonObject.requireOptionalPlaylistScope(expected: String) {
    requireOptionalPlaylistText("playlistId", expected)
    if (containsKey("targetId")) require(playlistText("targetId") in setOf(expected, "VL$expected"))
}
