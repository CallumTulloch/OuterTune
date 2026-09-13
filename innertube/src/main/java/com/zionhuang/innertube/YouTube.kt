package com.zionhuang.innertube

import com.zionhuang.innertube.models.AccountInfo
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditResolution
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.isEmptyByline
import com.zionhuang.innertube.models.merge
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.toArtistCredit
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.ArtTrackOriginalMetadata
import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.GridRenderer
import com.zionhuang.innertube.models.MusicCarouselShelfRenderer
import com.zionhuang.innertube.models.MusicShelfRenderer
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SearchSuggestions
import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.zionhuang.innertube.models.YouTubeClient
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB
import com.zionhuang.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.models.getContinuation
import com.zionhuang.innertube.models.getItems
import com.zionhuang.innertube.models.artistElements
import com.zionhuang.innertube.models.response.AccountMenuResponse
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.models.response.CreatePlaylistResponse
import com.zionhuang.innertube.models.response.GetQueueResponse
import com.zionhuang.innertube.models.response.GetSearchSuggestionsResponse
import com.zionhuang.innertube.models.response.GetTranscriptResponse
import com.zionhuang.innertube.models.response.NextResponse
import com.zionhuang.innertube.models.response.PlayerResponse
import com.zionhuang.innertube.models.response.SearchResponse
import com.zionhuang.innertube.models.response.ResolveUrlResponse
import com.zionhuang.innertube.pages.AlbumPage
import com.zionhuang.innertube.pages.ArtistItemsContinuationPage
import com.zionhuang.innertube.pages.ArtistItemsPage
import com.zionhuang.innertube.pages.ArtistPage
import com.zionhuang.innertube.pages.BrowseResult
import com.zionhuang.innertube.pages.ExplorePage
import com.zionhuang.innertube.pages.HistoryPage
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.pages.LibraryContinuationPage
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.pages.MoodAndGenres
import com.zionhuang.innertube.pages.NewReleaseAlbumPage
import com.zionhuang.innertube.pages.NextPage
import com.zionhuang.innertube.pages.NextResult
import com.zionhuang.innertube.pages.PlaylistContinuationPage
import com.zionhuang.innertube.pages.PlaylistPage
import com.zionhuang.innertube.pages.RelatedPage
import com.zionhuang.innertube.pages.SearchPage
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSuggestionPage
import com.zionhuang.innertube.pages.SearchSummary
import com.zionhuang.innertube.pages.SearchSummaryPage
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.Proxy
import kotlin.random.Random

internal fun parseSearchSummary(contents: List<SectionListRenderer.Content>, language: String = YouTube.locale.hl): SearchSummaryPage =
    SearchSummaryPage(
        summaries = buildList {
            contents.forEach { content ->
                content.musicCardShelfRenderer?.let { renderer ->
                    val items = listOfNotNull(SearchSummaryPage.fromMusicCardShelfRenderer(renderer, language = language))
                        .plus(
                            renderer.contents
                                ?.mapNotNull { it.musicResponsiveListItemRenderer }
                                ?.mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it, language = language) }
                                .orEmpty()
                        )
                        .distinctBy { it.id }
                    if (items.isNotEmpty()) {
                        add(
                            SearchSummary(
                                title = renderer.header?.musicCardShelfHeaderBasicRenderer?.title
                                    ?.runs?.firstOrNull()?.text.orEmpty(),
                                items = items,
                                isTopResult = true,
                            )
                        )
                    }
                }

                content.musicShelfRenderer?.let { renderer ->
                    val items = renderer.contents?.getItems()
                        ?.mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it, language = language) }
                        ?.distinctBy { it.id }
                        .orEmpty()
                    if (items.isNotEmpty()) {
                        add(
                            SearchSummary(
                                title = renderer.title?.runs?.firstOrNull()?.text.orEmpty(),
                                items = items,
                            )
                        )
                    }
                }
            }

            val itemSectionItems = contents
                .flatMap { it.itemSectionRenderer?.contents.orEmpty() }
                .mapNotNull { it.musicResponsiveListItemRenderer }
                .mapNotNull { SearchSummaryPage.fromMusicResponsiveListItemRenderer(it, language = language) }
                .distinctBy { it.id }
            if (itemSectionItems.isNotEmpty()) {
                add(SearchSummary(title = "", items = itemSectionItems))
            }
        }
    )

internal fun List<YTItem>.withResolvedArtists(resolvedSongs: List<SongItem>): List<YTItem> {
    val resolvedSongsById = resolvedSongs
        .filter { it.artists.isNotEmpty() }
        .associateBy(SongItem::id)

    return map { item ->
        if (item is SongItem && item.artists.isEmpty()) {
            resolvedSongsById[item.id]?.let { item.copy(artists = it.artists) } ?: item
        } else {
            item
        }
    }
}

internal fun parseArtTrackOriginalMetadata(response: JsonElement, expectedVideoId: String): ArtTrackOriginalMetadata {
    val details = response.jsonObject["videoDetails"]?.jsonObject ?: error("Missing art track metadata")
    fun text(key: String) = details[key]?.jsonPrimitive?.contentOrNull
    require(text("videoId") == expectedVideoId) { "Art track metadata belongs to another video" }
    val title = text("title")?.takeIf { it.isNotBlank() } ?: error("Missing art track title")
    return ArtTrackOriginalMetadata(expectedVideoId, title, text("author"), text("channelId"), text("shortDescription"))
}

/**
 * Parse useful data with [InnerTube] sending requests.
 * Modified from [ViMusic](https://github.com/vfsfitvnm/ViMusic)
 */
object YouTube {
    private val innerTube = InnerTube()
    private val metadataAuthLock get() = innerTube.authenticationLock
    val authentication: YouTubeAuthentication get() = innerTube.authentication
    val authRevision: Long get() = authentication.revision
    val authUpdates = innerTube.authUpdates

    fun setAuthentication(cookie: String?, visitorData: String?, dataSyncId: String?, useLoginForBrowse: Boolean) =
        innerTube.setAuthentication(cookie, visitorData, dataSyncId, useLoginForBrowse)
    private val mutableLocaleUpdates = MutableStateFlow(innerTube.locale)
    /** The active request language must also invalidate already visible metadata consumers. */
    val localeUpdates = mutableLocaleUpdates.asStateFlow()

    /** Receives raw parsed names. Observers should enqueue work and return without blocking. */
    @Volatile
    var metadataObserver: ((items: List<YTItem>, requestLocale: YouTubeLocale, source: String) -> Unit)? = null

    /** Keep the request's authentication generation through every suspension and parser step. */
    internal suspend fun <T> metadataRequest(
        requestLocale: YouTubeLocale,
        source: String,
        enabled: Boolean = true,
        items: (T) -> List<YTItem>,
        request: suspend () -> T,
    ): Result<T> {
        val revision = synchronized(metadataAuthLock) { authRevision }
        return runCatching { request() }.onSuccess { page ->
            if (enabled) synchronized(metadataAuthLock) {
                // The observer stamps its packet using current auth. Exclude older responses
                // before invoking it, including an account A -> B -> A round trip.
                if (revision == authRevision) notifyMetadata(items(page), requestLocale, source)
            }
        }
    }

    internal fun notifyMetadata(items: List<YTItem>, requestLocale: YouTubeLocale, source: String) {
        if (items.isEmpty()) return
        try {
            metadataObserver?.invoke(items, requestLocale, source)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional metadata collection must not turn a successful browse or playback into a failure.
        }
    }

    var locale: YouTubeLocale
        get() = innerTube.locale
        set(value) {
            innerTube.locale = value
            mutableLocaleUpdates.value = value
        }
    var visitorData: String?
        get() = innerTube.visitorData
        set(value) { innerTube.visitorData = value }
    var dataSyncId: String?
        get() = innerTube.dataSyncId
        set(value) { innerTube.dataSyncId = value }
    var cookie: String?
        get() = innerTube.cookie
        set(value) { innerTube.cookie = value }
    var proxy: Proxy?
        get() = innerTube.proxy
        set(value) {
            innerTube.proxy = value
        }
    var useLoginForBrowse: Boolean
        get() = innerTube.useLoginForBrowse
        set(value) { innerTube.useLoginForBrowse = value }

    private suspend fun fetchMissingArtists(items: List<YTItem>, requestLocale: YouTubeLocale = locale): List<SongItem> {
        val missingArtistIds = items
            .filterIsInstance<SongItem>()
            .filter { it.artists.isEmpty() }
            .map(SongItem::id)
            .distinct()
            .take(MAX_GET_QUEUE_SIZE)
        if (missingArtistIds.isEmpty()) return emptyList()

        return queue(videoIds = missingArtistIds, requestLocale = requestLocale).getOrDefault(emptyList())
    }

    suspend fun searchSuggestions(query: String, requestLocale: YouTubeLocale = locale): Result<SearchSuggestions> = metadataRequest(
        requestLocale, "searchSuggestions", items = { it.recommendedItems },
    ) {
        val response = innerTube.getSearchSuggestions(WEB_REMIX, query, requestLocale = requestLocale).body<GetSearchSuggestionsResponse>()
        SearchSuggestions(
            queries = response.contents?.getOrNull(0)?.searchSuggestionsSectionRenderer?.contents?.mapNotNull { content ->
                content.searchSuggestionRenderer?.suggestion?.runs?.joinToString(separator = "") { it.text }
            }.orEmpty(),
            recommendedItems = response.contents?.getOrNull(1)?.searchSuggestionsSectionRenderer?.contents?.mapNotNull {
                it.musicResponsiveListItemRenderer?.let { renderer ->
                    SearchSuggestionPage.fromMusicResponsiveListItemRenderer(renderer, language = requestLocale.hl)
                }
            }.orEmpty()
        )
    }

    // Preserve function-reference signatures while new callers can pin a request locale.
    suspend fun searchSummary(query: String): Result<SearchSummaryPage> = searchSummary(query, locale)

    suspend fun search(query: String, filter: SearchFilter): Result<SearchResult> = search(query, filter, locale)

    suspend fun searchContinuation(continuation: String): Result<SearchResult> = searchContinuation(continuation, locale)

    suspend fun searchSummary(query: String, requestLocale: YouTubeLocale): Result<SearchSummaryPage> = metadataRequest(
        requestLocale, "searchSummary", items = { page -> page.summaries.flatMap { it.items } },
    ) {
        val response = innerTube.search(WEB_REMIX, query, requestLocale = requestLocale).body<SearchResponse>()
        val contents = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()
        parseSearchSummary(contents, language = requestLocale.hl)
    }

    suspend fun search(query: String, filter: SearchFilter, requestLocale: YouTubeLocale): Result<SearchResult> =
        search(query, filter, requestLocale, notifyMetadata = true)

    suspend fun search(query: String, filter: SearchFilter, requestLocale: YouTubeLocale, notifyMetadata: Boolean): Result<SearchResult> = metadataRequest(
        requestLocale, "search", enabled = notifyMetadata, items = { it.items },
    ) {
        val response = innerTube.search(WEB_REMIX, query, filter.value, requestLocale = requestLocale).body<SearchResponse>()
        val items = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.lastOrNull()
            ?.musicShelfRenderer?.contents?.getItems()?.mapNotNull {
                SearchPage.toYTItem(it, language = requestLocale.hl)
            }.orEmpty()
        SearchResult(
            items = items,
            continuation = response.contents?.tabbedSearchResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.lastOrNull()
                ?.musicShelfRenderer?.continuations?.getContinuation()
        )
    }

    suspend fun searchContinuation(continuation: String, requestLocale: YouTubeLocale): Result<SearchResult> = metadataRequest(
        requestLocale, "searchContinuation", items = { it.items },
    ) {
        val response = innerTube.search(WEB_REMIX, continuation = continuation, requestLocale = requestLocale).body<SearchResponse>()
        val continuationPage = response.continuationContents?.musicShelfContinuation
            ?: error("Missing search continuation contents")
        val items = continuationPage.contents
            .mapNotNull {
                SearchPage.toYTItem(it.musicResponsiveListItemRenderer, language = requestLocale.hl)
            }
        SearchResult(
            items = items,
            continuation = continuationPage.continuations?.getContinuation()
        )
    }

    suspend fun album(browseId: String, withSongs: Boolean = true, requestLocale: YouTubeLocale = locale, notifyMetadata: Boolean = true): Result<AlbumPage> = metadataRequest(
        requestLocale, "album", enabled = notifyMetadata, items = { listOf(it.album) + it.songs + it.otherVersions },
    ) {
        val response = innerTube.browse(WEB_REMIX, browseId, requestLocale = requestLocale).body<BrowseResponse>()
        val album = AlbumPage.getAlbum(browseId, response, requestLocale.hl)
        AlbumPage(
            album = album,
            songs = if (!withSongs) emptyList() else if (AlbumPage.trackContents(response) != null) {
                completeAlbumTracks(response, album, requestLocale)
            } else {
                albumSongs(requireNotNull(album.playlistId) { "Album track shelf missing" }, requestLocale, false).getOrThrow()
            },
            otherVersions = AlbumPage.sections(response).flatMap { section ->
                section.musicCarouselShelfRenderer?.contents.orEmpty()
                    .mapNotNull { it.musicTwoRowItemRenderer }
                    .mapNotNull { NewReleaseAlbumPage.fromMusicTwoRowItemRenderer(it, language = requestLocale.hl) }
            },
        )
    }

    private suspend fun completeAlbumTracks(initial: BrowseResponse, album: AlbumItem?, requestLocale: YouTubeLocale): List<SongItem> {
        var response = initial
        val songs = mutableListOf<SongItem>()
        val visited = mutableSetOf<String>()
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val contents = requireNotNull(AlbumPage.trackContents(response)) { "Album track shelf missing" }
            songs += contents.getItems().mapNotNull { AlbumPage.getSong(it, album, requestLocale.hl) }
            val token = AlbumPage.continuation(response) ?: break
            check(visited.add(token)) { "Album continuation repeated" }
            response = innerTube.browse(WEB_REMIX, continuation = token, requestLocale = requestLocale).body()
        }
        return songs.distinctBy { it.id }
    }

    suspend fun albumSongs(playlistId: String, requestLocale: YouTubeLocale = locale, notifyMetadata: Boolean = true): Result<List<SongItem>> = metadataRequest(
        requestLocale, "albumSongs", enabled = notifyMetadata, items = { it },
    ) {
        val response = innerTube.browse(WEB_REMIX, "VL$playlistId", requestLocale = requestLocale).body<BrowseResponse>()
        completeAlbumTracks(response, null, requestLocale)
    }

    /** Resolve a supported channel URL through the fixed YouTube API, without metadata writes. */
    suspend fun resolveArtistUrl(input: String, requestLocale: YouTubeLocale = locale): Result<String> = runCatching {
        resolveYouTubeArtistUrl(input) { canonical ->
            innerTube.resolveArtistUrl(canonical, requestLocale).body<ResolveUrlResponse>()
        }
    }

    suspend fun artist(browseId: String, requestLocale: YouTubeLocale = locale, notifyMetadata: Boolean = true): Result<ArtistPage> = metadataRequest(
        requestLocale, "artist", enabled = notifyMetadata, items = { page -> listOf(page.artist) + page.sections.flatMap { it.items } },
    ) {
        val response = innerTube.browse(WEB_REMIX, browseId, requestLocale = requestLocale).body<BrowseResponse>()

        ArtistPage(
            artist = ArtistItem(
                id = browseId,
                title = response.header?.musicImmersiveHeaderRenderer?.title?.runs?.firstOrNull()?.text
                    ?: response.header?.musicVisualHeaderRenderer?.title?.runs?.firstOrNull()?.text
                    ?: response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text!!,
                thumbnail = response.header?.musicImmersiveHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                    ?: response.header?.musicVisualHeaderRenderer?.foregroundThumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                    ?: response.header?.musicDetailHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl(),
                channelId = response.header?.musicImmersiveHeaderRenderer?.subscriptionButton?.subscribeButtonRenderer?.channelId,
                playEndpoint = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                    ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.musicShelfRenderer
                    ?.contents?.firstOrNull()?.musicResponsiveListItemRenderer?.overlay?.musicItemThumbnailOverlayRenderer
                    ?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                shuffleEndpoint = response.header?.musicImmersiveHeaderRenderer?.playButton?.buttonRenderer?.navigationEndpoint?.watchEndpoint
                    ?: response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer
                        ?.contents?.firstOrNull()?.musicShelfRenderer?.contents?.firstOrNull()?.musicResponsiveListItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                radioEndpoint = response.header?.musicImmersiveHeaderRenderer?.startRadioButton?.buttonRenderer?.navigationEndpoint?.watchEndpoint
            ),
            sections = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents
                ?.mapNotNull { ArtistPage.fromSectionListRendererContent(it, language = requestLocale.hl) }!!,
            description = response.header?.musicImmersiveHeaderRenderer?.description?.runs?.firstOrNull()?.text
        )
    }

    suspend fun artistItems(endpoint: BrowseEndpoint, requestLocale: YouTubeLocale = locale): Result<ArtistItemsPage> = metadataRequest(
        requestLocale, "artistItems", items = { it.items },
    ) {
        val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params, requestLocale = requestLocale).body<BrowseResponse>()
        val gridRenderer = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
            ?.gridRenderer
        if (gridRenderer != null) {
            ArtistItemsPage(
                title = gridRenderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text.orEmpty(),
                items = gridRenderer.items.mapNotNull {
                    it.musicTwoRowItemRenderer?.let { renderer ->
                        ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer, language = requestLocale.hl)
                    }
                },
                continuation = gridRenderer.continuations?.getContinuation()
            )
        } else {
            val musicPlaylistShelfRenderer = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
                ?.musicPlaylistShelfRenderer
            ArtistItemsPage(
                title = response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text!!,
                items = musicPlaylistShelfRenderer?.contents?.getItems()?.mapNotNull {
                        ArtistItemsPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl)
                    }!!,
                continuation = musicPlaylistShelfRenderer.contents.getContinuation()
            )
        }
    }

    suspend fun artistItemsContinuation(continuation: String, requestLocale: YouTubeLocale = locale): Result<ArtistItemsContinuationPage> = metadataRequest(
        requestLocale, "artistItemsContinuation", items = { it.items },
    ) {
        val response = innerTube.browse(WEB_REMIX, continuation = continuation, requestLocale = requestLocale).body<BrowseResponse>()

        when {
            response.continuationContents?.gridContinuation != null -> {
                val gridContinuation = response.continuationContents.gridContinuation
                ArtistItemsContinuationPage(
                    items = gridContinuation.items.mapNotNull {
                        it.musicTwoRowItemRenderer?.let { renderer ->
                            ArtistItemsPage.fromMusicTwoRowItemRenderer(renderer, language = requestLocale.hl)
                        }
                    },
                    continuation = gridContinuation.continuations?.getContinuation()
                )
            }

            response.continuationContents?.musicPlaylistShelfContinuation != null -> {
                val musicPlaylistShelfContinuation = response.continuationContents.musicPlaylistShelfContinuation
                ArtistItemsContinuationPage(
                    items = musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                        ArtistItemsPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl)
                    },
                    continuation = musicPlaylistShelfContinuation.continuations?.getContinuation()
                )
            }

            else -> {
                val continuationItems = response.onResponseReceivedActions?.firstOrNull()
                    ?.appendContinuationItemsAction?.continuationItems
                ArtistItemsContinuationPage(
                    items = continuationItems?.getItems()?.mapNotNull {
                        ArtistItemsPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl)
                    }!!,
                    continuation = continuationItems.getContinuation()
                )
            }
        }
    }

    suspend fun playlist(playlistId: String, requestLocale: YouTubeLocale = locale): Result<PlaylistPage> = metadataRequest(
        requestLocale, "playlist", items = { listOf(it.playlist) + it.songs },
    ) {
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = "VL$playlistId",
            setLogin = true,
            requestLocale = requestLocale,
        ).body<BrowseResponse>()

        val base = response.contents?.twoColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()
        val header = base?.musicResponsiveHeaderRenderer ?: base?.musicEditablePlaylistDetailHeaderRenderer?.header?.musicResponsiveHeaderRenderer

        val editable = base?.musicEditablePlaylistDetailHeaderRenderer != null

        PlaylistPage(
            playlist = PlaylistItem(
                id = playlistId,
                title = header?.title?.runs?.firstOrNull()?.text!!,
                author = header.straplineTextOne?.runs?.firstOrNull()?.let {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId
                    )
                },
                songCountText = header.secondSubtitle?.runs?.firstOrNull()?.text,
                thumbnail = response.background?.musicThumbnailRenderer?.getThumbnailUrl(),
                playEndpoint = header.buttons.getOrNull(1)?.musicPlayButtonRenderer
                    ?.playNavigationEndpoint?.watchEndpoint,
                shuffleEndpoint = header.buttons.getOrNull(2)?.menuRenderer?.items?.find {
                    it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                radioEndpoint = header.buttons.getOrNull(2)?.menuRenderer?.items?.find {
                    it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                isEditable = editable
            ),
            songs = response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer
                ?.contents?.firstOrNull()?.musicPlaylistShelfRenderer?.contents?.getItems()?.mapNotNull {
                    PlaylistPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl)
                }!!,
            songsContinuation = response.contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer
                .contents.firstOrNull()?.musicPlaylistShelfRenderer?.contents?.getContinuation(),
            continuation = response.contents.twoColumnBrowseResultsRenderer.secondaryContents.sectionListRenderer
                .continuations?.getContinuation(),
            requestLocale = requestLocale,
        )
    }

    suspend fun playlistContinuation(continuation: String, requestLocale: YouTubeLocale = locale) = metadataRequest(
        requestLocale, "playlistContinuation", items = { page: PlaylistContinuationPage -> page.songs },
    ) {
        val response = innerTube.browse(
            client = WEB_REMIX,
            continuation = continuation,
            setLogin = true,
            requestLocale = requestLocale,
        ).body<BrowseResponse>()

        val musicPlaylistShelfContinuation = response.continuationContents?.musicPlaylistShelfContinuation
        if (musicPlaylistShelfContinuation != null) {
            PlaylistContinuationPage(
                songs = musicPlaylistShelfContinuation.contents.getItems().mapNotNull {
                    PlaylistPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl)
                },
                continuation = musicPlaylistShelfContinuation.continuations?.getContinuation()
            )
        } else {
            val continuationItems = response.onResponseReceivedActions?.firstOrNull()
                ?.appendContinuationItemsAction?.continuationItems
            PlaylistContinuationPage(
                songs = continuationItems?.getItems()?.mapNotNull {
                        PlaylistPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl)
                    } ?: emptyList(),
                continuation = continuationItems?.getContinuation()
            )
        }
    }

    suspend fun home(continuation: String? = null, params: String? = null, requestLocale: YouTubeLocale = locale): Result<HomePage> = metadataRequest(
        requestLocale, "home", items = { page -> page.sections.flatMap { it.items } },
    ) {
        if (continuation != null) {
            return@metadataRequest homeContinuation(continuation, requestLocale = requestLocale).getOrThrow()
        }

        val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_home", params = params, requestLocale = requestLocale).body<BrowseResponse>()
        val continuation = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer?.continuations?.getContinuation()
        val sectionListRender = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
            ?.tabRenderer?.content?.sectionListRenderer
            val sections = sectionListRender?.contents!!
            .mapNotNull { it.musicCarouselShelfRenderer }
            .mapNotNull {
                HomePage.Section.fromMusicCarouselShelfRenderer(it, language = requestLocale.hl)
            }.toMutableList()
        val chips = sectionListRender?.header?.chipCloudRenderer?.chips?.mapNotNull { HomePage.Chip.fromChipCloudChipRenderer(it) }
        HomePage(chips, sections, continuation)
    }

    private suspend fun homeContinuation(continuation: String, requestLocale: YouTubeLocale = locale): Result<HomePage> = metadataRequest(
        requestLocale, "homeContinuation", items = { page -> page.sections.flatMap { it.items } },
    ) {
        val response =
            innerTube.browse(WEB_REMIX, continuation = continuation, requestLocale = requestLocale).body<BrowseResponse>()
        val continuation =
            response.continuationContents?.sectionListContinuation?.continuations?.getContinuation()
        HomePage(
            null,
            response.continuationContents?.sectionListContinuation?.contents
            ?.mapNotNull { it.musicCarouselShelfRenderer }
            ?.mapNotNull {
                HomePage.Section.fromMusicCarouselShelfRenderer(it, language = requestLocale.hl)
            }.orEmpty(), continuation
        )
    }

    suspend fun explore(requestLocale: YouTubeLocale = locale): Result<ExplorePage> = metadataRequest(
        requestLocale, "explore", items = { it.newReleaseAlbums },
    ) {
        val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_explore", requestLocale = requestLocale).body<BrowseResponse>()
        ExplorePage(
            newReleaseAlbums = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.find {
                it.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint?.browseId == "FEmusic_new_releases_albums"
            }?.musicCarouselShelfRenderer?.contents
                ?.mapNotNull { it.musicTwoRowItemRenderer }
                ?.mapNotNull { NewReleaseAlbumPage.fromMusicTwoRowItemRenderer(it, language = requestLocale.hl) }.orEmpty(),
            moodAndGenres = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.find {
                it.musicCarouselShelfRenderer?.header?.musicCarouselShelfBasicHeaderRenderer?.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint?.browseId == "FEmusic_moods_and_genres"
            }?.musicCarouselShelfRenderer?.contents
                ?.mapNotNull { it.musicNavigationButtonRenderer }
                ?.mapNotNull(MoodAndGenres.Companion::fromMusicNavigationButtonRenderer)
                .orEmpty()
        )
    }

    suspend fun newReleaseAlbums(requestLocale: YouTubeLocale = locale): Result<List<AlbumItem>> = metadataRequest(
        requestLocale, "newReleaseAlbums", items = { it },
    ) {
        val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_new_releases_albums", requestLocale = requestLocale).body<BrowseResponse>()
        response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.firstOrNull()?.gridRenderer?.items
            ?.mapNotNull { it.musicTwoRowItemRenderer }
            ?.mapNotNull { NewReleaseAlbumPage.fromMusicTwoRowItemRenderer(it, language = requestLocale.hl) }
            .orEmpty()
    }

    suspend fun moodAndGenres(requestLocale: YouTubeLocale = locale): Result<List<MoodAndGenres>> = runCatching {
        val response = innerTube.browse(WEB_REMIX, browseId = "FEmusic_moods_and_genres", requestLocale = requestLocale).body<BrowseResponse>()
        response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents!!
            .mapNotNull(MoodAndGenres.Companion::fromSectionListRendererContent)
    }

    suspend fun browse(browseId: String, params: String?, requestLocale: YouTubeLocale = locale): Result<BrowseResult> = metadataRequest(
        requestLocale, "browse", items = { page -> page.items.flatMap { it.items } },
    ) {
        val response = innerTube.browse(WEB_REMIX, browseId = browseId, params = params, requestLocale = requestLocale).body<BrowseResponse>()
        BrowseResult(
            title = response.header?.musicHeaderRenderer?.title?.runs?.firstOrNull()?.text,
            items = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents?.mapNotNull { content ->
                when {
                    content.gridRenderer != null -> {
                        BrowseResult.Item(
                            title = content.gridRenderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text,
                            items = content.gridRenderer.items
                                .mapNotNull(GridRenderer.Item::musicTwoRowItemRenderer)
                                .mapNotNull { RelatedPage.fromMusicTwoRowItemRenderer(it, language = requestLocale.hl) }
                        )
                    }

                    content.musicCarouselShelfRenderer != null -> {
                        BrowseResult.Item(
                            title = content.musicCarouselShelfRenderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text,
                            items = content.musicCarouselShelfRenderer.contents
                                .mapNotNull(MusicCarouselShelfRenderer.Content::musicTwoRowItemRenderer)
                                .mapNotNull { RelatedPage.fromMusicTwoRowItemRenderer(it, language = requestLocale.hl) }
                        )
                    }

                    else -> null
                }
            }.orEmpty()
        )
    }

    suspend fun library(browseId: String, tabIndex: Int = 0, requestLocale: YouTubeLocale = locale) = metadataRequest(
        requestLocale, "library", items = { page: LibraryPage -> page.items },
    ) {
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = browseId,
            setLogin = true,
            requestLocale = requestLocale,
        ).body<BrowseResponse>()

        val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs

        val contents = if (tabs != null && tabs.size >= tabIndex) {
            tabs[tabIndex].tabRenderer.content?.sectionListRenderer?.contents?.firstOrNull()
        }
        else {
            null
        }

        when {
            contents?.gridRenderer != null -> {
                LibraryPage(
                    requestLocale = requestLocale,
                    items = contents.gridRenderer.items
                        .mapNotNull (GridRenderer.Item::musicTwoRowItemRenderer)
                        .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it, language = requestLocale.hl) },
                    continuation = contents.gridRenderer.continuations?.getContinuation()
                )
            }

            else -> { // contents?.musicShelfRenderer != null
                LibraryPage(
                    requestLocale = requestLocale,
                    items = contents?.musicShelfRenderer?.contents!!
                        .mapNotNull (MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                        .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl) },
                    continuation = contents.musicShelfRenderer.continuations?.getContinuation()
                )
            }
        }
    }

    suspend fun libraryContinuation(continuation: String, requestLocale: YouTubeLocale = locale) = metadataRequest(
        requestLocale, "libraryContinuation", items = { page: LibraryContinuationPage -> page.items },
    ) {
        val response = innerTube.browse(
            client = WEB_REMIX,
            continuation = continuation,
            setLogin = true,
            requestLocale = requestLocale,
        ).body<BrowseResponse>()

        val contents = response.continuationContents

        when {
            contents?.gridContinuation != null -> {
                LibraryContinuationPage(
                    items = contents.gridContinuation.items
                        .mapNotNull (GridRenderer.Item::musicTwoRowItemRenderer)
                        .mapNotNull { LibraryPage.fromMusicTwoRowItemRenderer(it, language = requestLocale.hl) },
                    continuation = contents.gridContinuation.continuations?.getContinuation()
                )
            }

            else -> { // contents?.musicShelfContinuation != null
                LibraryContinuationPage(
                    items = contents?.musicShelfContinuation?.contents!!
                        .mapNotNull (MusicShelfRenderer.Content::musicResponsiveListItemRenderer)
                        .mapNotNull { LibraryPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl) },
                    continuation = contents.musicShelfContinuation.continuations?.getContinuation()
                )
            }
        }
    }

    suspend fun libraryRecentActivity(requestLocale: YouTubeLocale = locale): Result<LibraryPage> = metadataRequest(
        requestLocale, "libraryRecentActivity", items = { it.items },
    ) {
        val continuation = LibraryFilter.FILTER_RECENT_ACTIVITY.value

        val response = innerTube.browse(
            client = WEB_REMIX,
            continuation = continuation,
            setLogin = true,
            requestLocale = requestLocale,
        ).body<BrowseResponse>()

        val items = response.continuationContents?.sectionListContinuation?.contents?.firstOrNull()
            ?.gridRenderer?.items!!.mapNotNull {
                it.musicTwoRowItemRenderer?.let { renderer ->
                    LibraryPage.fromMusicTwoRowItemRenderer(renderer, language = requestLocale.hl)
                }
            }.toMutableList()

        /*
         * We need to fetch the artist page when accessing the library because it allows to have
         * a proper playEndpoint, which is needed to correctly report the playing indicator in
         * the home page.
         *
         * Despite this, we need to use the old thumbnail because it's the proper format for a
         * square picture, which is what we need.
         */
        items.forEachIndexed { index, item ->
            if (item is ArtistItem)
                items[index] = artist(item.id, requestLocale = requestLocale).getOrNull()?.artist!!.copy(thumbnail = item.thumbnail)
        }

        LibraryPage(
            items = items,
            continuation = null,
            requestLocale = requestLocale,
        )
    }

    suspend fun musicHistory(requestLocale: YouTubeLocale = locale) = metadataRequest(
        requestLocale, "musicHistory", items = { page: HistoryPage -> page.sections.orEmpty().flatMap { it.songs } },
    ) {
        val response = innerTube.browse(
            client = WEB_REMIX,
            browseId = "FEmusic_history",
            setLogin = true,
            requestLocale = requestLocale,
        ).body<BrowseResponse>()

        HistoryPage(
            sections = response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents
                ?.mapNotNull {
                    it.musicShelfRenderer?.let { musicShelfRenderer ->
                        HistoryPage.fromMusicShelfRenderer(musicShelfRenderer, language = requestLocale.hl)
                    }
                }
        )
    }

    suspend fun likeVideo(videoId: String, like: Boolean) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        if (like)
            innerTube.likeVideo(WEB_REMIX, videoId)
        else
            innerTube.unlikeVideo(WEB_REMIX, videoId)
    }

    suspend fun likePlaylist(playlistId: String, like: Boolean) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        if (like)
            innerTube.likePlaylist(WEB_REMIX, playlistId)
        else
            innerTube.unlikePlaylist(WEB_REMIX, playlistId)
    }

    suspend fun subscribeChannel(channelId: String, subscribe: Boolean) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        if (subscribe)
            innerTube.subscribeChannel(WEB_REMIX, channelId)
        else
            innerTube.unsubscribeChannel(WEB_REMIX, channelId)
    }

    suspend fun getChannelId(browseId: String, requestLocale: YouTubeLocale = locale): String {
        artist(browseId, requestLocale = requestLocale).onSuccess {
            return it.artist.channelId ?: ""
        }
        return ""
    }

    suspend fun addToPlaylist(playlistId: String, videoId: String) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        innerTube.addToPlaylist(WEB_REMIX, playlistId, videoId)
    }

    suspend fun addPlaylistToPlaylist(playlistId: String, addPlaylistId: String) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        innerTube.addPlaylistToPlaylist(WEB_REMIX, playlistId, addPlaylistId)
    }

    suspend fun removeFromPlaylist(playlistId: String, videoId: String, setVideoId: String) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        innerTube.removeFromPlaylist(WEB_REMIX, playlistId, videoId, setVideoId)
    }

    suspend fun moveSongPlaylist(playlistId: String, setVideoId: String, successorSetVideoId: String) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        innerTube.moveSongPlaylist(WEB_REMIX, playlistId, setVideoId, successorSetVideoId)
    }

    fun createPlaylist(title: String): String {
        YouTubeSyncPolicy.requireEnabled()
        return runBlocking {
            innerTube.createPlaylist(WEB_REMIX, title).body<CreatePlaylistResponse>().playlistId
        }
    }

    suspend fun renamePlaylist(playlistId: String, name: String) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        innerTube.renamePlaylist(WEB_REMIX, playlistId, name)
    }

    suspend fun deletePlaylist(playlistId: String) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        innerTube.deletePlaylist(WEB_REMIX, playlistId)
    }

    suspend fun player(videoId: String, playlistId: String? = null, client: YouTubeClient, signatureTimestamp: Int? = null, webPlayerPot: String? = null, requestLocale: YouTubeLocale = locale, requestAuthentication: YouTubeAuthentication = authentication): Result<PlayerResponse> = runCatching {
        innerTube.player(client, videoId, playlistId, signatureTimestamp, webPlayerPot,
            requestLocale = requestLocale, requestAuthentication = requestAuthentication).body<PlayerResponse>()
            .also { innerTube.ensureAuthenticationCurrent(requestAuthentication) }
    }

    /** Caller must first verify MUSIC_VIDEO_TYPE_ATV. Returned names remain unclassified candidates. */
    suspend fun artTrackOriginalMetadata(
        videoId: String,
        requestLocale: YouTubeLocale = locale,
    ): Result<ArtTrackOriginalMetadata> = runCatching {
        parseArtTrackOriginalMetadata(
            innerTube.artTrackOriginalMetadata(videoId, requestLocale).body<JsonElement>(),
            expectedVideoId = videoId,
        )
    }

    suspend fun registerPlayback(playlistId: String? = null, playbackTracking: String) = runCatching {
        YouTubeSyncPolicy.requireEnabled()
        val cpn = (1..16).map {
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"[Random.Default.nextInt(
                0,
                64
            )]
        }.joinToString("")

        val playbackUrl = playbackTracking.replace(
            "https://s.youtube.com",
            "https://music.youtube.com",
        )

        innerTube.registerPlayback(
            url = playbackUrl,
            playlistId = playlistId,
            cpn = cpn
        )
    }

    suspend fun next(endpoint: WatchEndpoint, continuation: String? = null, requestLocale: YouTubeLocale = locale): Result<NextResult> = metadataRequest(
        requestLocale, "next", items = { it.items },
    ) {
        val response = innerTube.next(
            WEB_REMIX,
            endpoint.videoId,
            endpoint.playlistId,
            endpoint.playlistSetVideoId,
            endpoint.index,
            endpoint.params,
            continuation,
            requestLocale = requestLocale,
        ).body<NextResponse>()
        val playlistPanelRenderer = response.continuationContents?.playlistPanelContinuation
            ?: response.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer
                .watchNextTabbedResultsRenderer.tabs[0].tabRenderer.content?.musicQueueRenderer
                ?.content?.playlistPanelRenderer!!
        val title = response.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer
            .watchNextTabbedResultsRenderer.tabs[0].tabRenderer.content?.musicQueueRenderer
            ?.header?.musicQueueHeaderRenderer?.subtitle?.runs?.firstOrNull()?.text
        val items = playlistPanelRenderer.contents.mapNotNull { content ->
            content.playlistPanelVideoRenderer
                ?.let { NextPage.fromPlaylistPanelVideoRenderer(it, language = requestLocale.hl) }
                ?.let { it to content.playlistPanelVideoRenderer.selected }
        }
        val songs = items.map { it.first }
        val currentIndex = items.indexOfFirst { it.second }.takeIf { it != -1 }

        // load automix items
        playlistPanelRenderer.contents.lastOrNull()?.automixPreviewVideoRenderer?.content?.automixPlaylistVideoRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.let { watchPlaylistEndpoint ->
            return@metadataRequest next(watchPlaylistEndpoint, requestLocale = requestLocale).getOrThrow().let { result ->
                result.copy(
                    title = title,
                    items = songs + result.items,
                    lyricsEndpoint = response.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs.getOrNull(1)?.tabRenderer?.endpoint?.browseEndpoint,
                    relatedEndpoint = response.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs.getOrNull(2)?.tabRenderer?.endpoint?.browseEndpoint,
                    currentIndex = currentIndex,
                    endpoint = watchPlaylistEndpoint
                )
            }
        }
        NextResult(
            title = title,
            items = songs,
            currentIndex = currentIndex,
            lyricsEndpoint = response.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs.getOrNull(1)?.tabRenderer?.endpoint?.browseEndpoint,
            relatedEndpoint = response.contents.singleColumnMusicWatchNextResultsRenderer.tabbedRenderer.watchNextTabbedResultsRenderer.tabs.getOrNull(2)?.tabRenderer?.endpoint?.browseEndpoint,
            continuation = playlistPanelRenderer.continuations?.getContinuation(),
            endpoint = endpoint
        )
    }

    suspend fun lyrics(endpoint: BrowseEndpoint, requestLocale: YouTubeLocale = locale): Result<String?> = runCatching {
        val response = innerTube.browse(WEB_REMIX, endpoint.browseId, endpoint.params, requestLocale = requestLocale).body<BrowseResponse>()
        response.contents?.sectionListRenderer?.contents?.firstOrNull()?.musicDescriptionShelfRenderer?.description?.runs?.firstOrNull()?.text
    }

    suspend fun related(endpoint: BrowseEndpoint, requestLocale: YouTubeLocale = locale): Result<RelatedPage> = metadataRequest(
        requestLocale, "related", items = { it.songs + it.albums + it.artists + it.playlists },
    ) {
        val response = innerTube.browse(WEB_REMIX, endpoint.browseId, requestLocale = requestLocale).body<BrowseResponse>()
        val songs = mutableListOf<SongItem>()
        val albums = mutableListOf<AlbumItem>()
        val artists = mutableListOf<ArtistItem>()
        val playlists = mutableListOf<PlaylistItem>()
        response.contents?.sectionListRenderer?.contents?.forEach { sectionContent ->
            sectionContent.musicCarouselShelfRenderer?.contents?.forEach { content ->
                when (val item = content.musicResponsiveListItemRenderer?.let { RelatedPage.fromMusicResponsiveListItemRenderer(it, language = requestLocale.hl) }
                    ?: content.musicTwoRowItemRenderer?.let { RelatedPage.fromMusicTwoRowItemRenderer(it, language = requestLocale.hl) }) {
                    is SongItem -> if (content.musicResponsiveListItemRenderer?.overlay
                            ?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchEndpoint?.watchEndpointMusicSupportedConfigs
                            ?.watchEndpointMusicConfig?.musicVideoType == MUSIC_VIDEO_TYPE_ATV
                    ) songs.add(item)

                    is AlbumItem -> albums.add(item)
                    is ArtistItem -> artists.add(item)
                    is PlaylistItem -> playlists.add(item)
                    null -> {}
                }
            }
        }
        RelatedPage(songs, albums, artists, playlists)
    }

    suspend fun queue(videoIds: List<String>? = null, playlistId: String? = null, requestLocale: YouTubeLocale = locale, notifyMetadata: Boolean = true): Result<List<SongItem>> = metadataRequest(
        requestLocale, "queue", enabled = notifyMetadata, items = { it },
    ) {
        if (videoIds != null) {
            assert(videoIds.size <= MAX_GET_QUEUE_SIZE) // Max video limit
        }
        innerTube.getQueue(WEB_REMIX, videoIds, playlistId, requestLocale = requestLocale).body<GetQueueResponse>().queueDatas
            .mapNotNull {
                it.content.playlistPanelVideoRenderer?.let { renderer ->
                    NextPage.fromPlaylistPanelVideoRenderer(renderer, language = requestLocale.hl)
                }
            }
    }

    /** Called only for relevant visible/selected songs. The app owns scheduling and cache policy. */
    suspend fun resolveArtistCredit(song: SongItem, requestLocale: YouTubeLocale = locale): Result<ArtistCredit> =
        resolveTrackArtistCredit(song, requestLocale = requestLocale).map { it.credit }

    /** Preserve album metadata already obtained by the same queue request used for artist credits. */
    suspend fun resolveTrackArtistCredit(song: SongItem, requestLocale: YouTubeLocale = locale): Result<ArtistCreditResolution> =
        resolveTrackArtistCredit(
            song = song,
            requestLocale = requestLocale,
            getQueue = { queue(videoIds = listOf(song.id), requestLocale = requestLocale).getOrThrow() },
            browse = { innerTube.browse(WEB_REMIX, it, requestLocale = requestLocale).body<JsonElement>() },
        )

    internal suspend fun resolveTrackArtistCredit(
        song: SongItem,
        getQueue: suspend () -> List<SongItem>,
        browse: suspend (String) -> JsonElement,
        requestLocale: YouTubeLocale = locale,
    ): Result<ArtistCreditResolution> {
        return try {
            var credit = ArtistCreditResolver.beginAttempt(song.artistCredit ?: ArtistCredit(
                song.artists.joinToString("、") { it.name },
                if (song.artists.size > 1 || song.artists.singleOrNull()?.id != null) song.artists else emptyList(),
                if (song.artists.size > 1 || song.artists.singleOrNull()?.id != null)
                    ArtistCreditStatus.COMPLETE else ArtistCreditStatus.RAW,
                "song", requestLocale.hl))
            var album = song.album
            fun result() = Result.success(ArtistCreditResolution(credit, album))
            val knownType = song.endpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
            val videoSource = credit.evidence.any { it.startsWith("video-source:") } ||
                (knownType != null && knownType != MUSIC_VIDEO_TYPE_ATV)
            if ((videoSource && !credit.isEmptyByline()) ||
                (album != null && (credit.status == ArtistCreditStatus.CONFLICT ||
                    (credit.status == ArtistCreditStatus.COMPLETE && credit.artists.all { it.id != null }))))
                return result()
            // An album row can omit a video's entire byline while its own queue supplies it.
            // Retain the explicit type even when only the endpoint carried it before this repair.
            if (knownType != null && knownType != MUSIC_VIDEO_TYPE_ATV)
                credit = credit.copy(evidence = (credit.evidence + "video-source:$knownType").distinct())

            val queued = getQueue().singleOrNull { it.id == song.id }
            queued?.artistCredit?.let { credit = credit.merge(it) }
            album = album ?: queued?.album
            // Repair only the directly supplied byline; do not infer video performers from other pages.
            if (videoSource || credit.evidence.any { it.startsWith("video-source:") } || credit.status == ArtistCreditStatus.CONFLICT)
                return result()
            val candidateIds = (song.artistBrowseIds + queued?.artistBrowseIds.orEmpty()).distinct()
                .filter { Regex("^UC[A-Za-z0-9_-]{22}$").matches(it) }.take(5)
            val pages = mutableListOf<Artist>()
            if (credit.status != ArtistCreditStatus.CONFLICT &&
                (credit.status != ArtistCreditStatus.COMPLETE || credit.artists.any { it.id == null })) {
                for (id in candidateIds.filterNot { id -> credit.artists.any { it.id == id } }) {
                    try {
                        val response = browse(id)
                        ArtistCreditResolver.pageArtist(response, id)?.let(pages::add)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { credit = credit.copy(evidence = credit.evidence + "retry:artist-page:$id") }
                }
                // A verified page for the complete literal name protects a group name before
                // performer credits are used to infer any individual name boundaries.
                credit = ArtistCreditResolver.withPageNames(credit, pages)
            }
            if (credit.status != ArtistCreditStatus.COMPLETE && credit.status != ArtistCreditStatus.CONFLICT) {
                try {
                    val response = browse("MPTC${song.id}")
                    credit = ArtistCreditResolver.fromPerformers(credit, ArtistCreditResolver.performers(response, song.id))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { credit = credit.copy(evidence = credit.evidence + "retry:credits:${song.id}") }
                credit = ArtistCreditResolver.withPageNames(credit, pages)
            }
            result()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }

    suspend fun transcript(videoId: String, requestLocale: YouTubeLocale = locale): Result<String> = runCatching {
        val response = innerTube.getTranscript(WEB, videoId, requestLocale = requestLocale).body<GetTranscriptResponse>()
        response.actions?.firstOrNull()?.updateEngagementPanelAction?.content?.transcriptRenderer?.body?.transcriptBodyRenderer?.cueGroups?.joinToString(separator = "\n") { group ->
            val time = group.transcriptCueGroupRenderer.cues[0].transcriptCueRenderer.startOffsetMs
            val text = group.transcriptCueGroupRenderer.cues[0].transcriptCueRenderer.cue.simpleText
                .trim('♪')
                .trim(' ')
            "[%02d:%02d.%03d]$text".format(time / 60000, (time / 1000) % 60, time % 1000)
        }!!
    }

    suspend fun visitorData(): Result<String> = runCatching {
        Json.parseToJsonElement(innerTube.getSwJsData().bodyAsText().substring(5))
            .jsonArray[0]
            .jsonArray[2]
            .jsonArray.first {
                (it as? JsonPrimitive)?.contentOrNull?.let { candidate ->
                    VISITOR_DATA_REGEX.containsMatchIn(candidate)
                } ?: false
            }
            .jsonPrimitive.content
    }

    suspend fun accountInfo(): Result<AccountInfo> = runCatching {
        innerTube.accountMenu(WEB_REMIX).body<AccountMenuResponse>()
            .actions[0].openPopupAction.popup.multiPageMenuRenderer
            .header?.activeAccountHeaderRenderer
            ?.toAccountInfo()!!
    }

    @JvmInline
    value class SearchFilter(val value: String) {
        companion object {
            val FILTER_SONG = SearchFilter("EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D")
            val FILTER_VIDEO = SearchFilter("EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ALBUM = SearchFilter("EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_ARTIST = SearchFilter("EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D")
            val FILTER_FEATURED_PLAYLIST = SearchFilter("EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D")
            val FILTER_COMMUNITY_PLAYLIST = SearchFilter("EgeKAQQoAEABagoQAxAEEAoQCRAF")
        }
    }

    @JvmInline
    value class LibraryFilter(val value: String) {
        companion object {
            val FILTER_RECENT_ACTIVITY = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCaEFCb0FZQg%3D%3D")
            val FILTER_RECENTLY_PLAYED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpYnJhcnlfbGFuZGluZxoQZ2dNR0tnUUlCUkFCb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_ALPHABETICAL = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBUkFBb0FZQg%3D%3D")
            val FILTER_PLAYLISTS_RECENTLY_SAVED = LibraryFilter("4qmFsgIrEhdGRW11c2ljX2xpa2VkX3BsYXlsaXN0cxoQZ2dNR0tnUUlBQkFCb0FZQg%3D%3D")
        }
    }

    const val MAX_GET_QUEUE_SIZE = 1000

    private val VISITOR_DATA_REGEX = Regex("^Cg[t|s]")
}
