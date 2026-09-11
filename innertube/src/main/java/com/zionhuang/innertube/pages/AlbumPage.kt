package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.MusicResponsiveHeaderRenderer
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.getItems
import com.zionhuang.innertube.models.getContinuation
import com.zionhuang.innertube.models.MusicShelfRenderer
import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.withVideoSource
import com.zionhuang.innertube.models.toArtistCredit
import com.zionhuang.innertube.models.artistBrowseIds
import com.zionhuang.innertube.models.artistElements
import com.zionhuang.innertube.models.response.BrowseResponse
import com.zionhuang.innertube.models.splitBySeparator
import com.zionhuang.innertube.utils.parseTime

data class AlbumPage(
    val album: AlbumItem,
    val songs: List<SongItem>,
    val otherVersions: List<AlbumItem>,
) {
    companion object {
        fun getAlbum(browseId: String, response: BrowseResponse, language: String): AlbumItem = AlbumItem(
            browseId = browseId,
            playlistId = getPlaylistId(response),
            title = requireNotNull(getTitle(response)) { "Album title missing" },
            artists = getArtists(response),
            artistCredit = getArtistCredit(response, language),
            year = getYear(response),
            thumbnail = requireNotNull(getThumbnail(response)) { "Album thumbnail missing" },
        )

        fun getArtistCredit(response: BrowseResponse, language: String = com.zionhuang.innertube.YouTube.locale.hl) = (
            getHeader(response)?.straplineTextOne?.runs
                ?: response.header?.musicDetailHeaderRenderer?.subtitle?.runs?.splitBySeparator()?.getOrNull(1)
            ).orEmpty().toArtistCredit("album-header", language)

        fun getPlaylistId(response: BrowseResponse): String? {
            var playlistId = response.microformat?.microformatDataRenderer?.urlCanonical
                ?.substringAfter('?', "")?.split('&')?.firstOrNull { it.startsWith("list=") }
                ?.substringAfter('=')?.takeIf { it.isNotBlank() }
            if (playlistId == null)
            {
                playlistId = response.header?.musicDetailHeaderRenderer?.menu?.menuRenderer?.topLevelButtons?.firstOrNull()
                    ?.buttonRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.playlistId
            }
            return playlistId
        }

        fun getTitle(response: BrowseResponse): String? {
            val title = getHeader(response)?.title ?: response.header?.musicDetailHeaderRenderer?.title
            return title?.runs?.firstOrNull()?.text
        }

        fun getYear(response: BrowseResponse): Int? {
            val title = getHeader(response)?.subtitle ?: response.header?.musicDetailHeaderRenderer?.subtitle
            return title?.runs?.lastOrNull()?.text?.removeSuffix("年")?.toIntOrNull()
        }

        fun getThumbnail(response: BrowseResponse): String? {
            return getHeader(response)?.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                ?: response.background?.musicThumbnailRenderer?.getThumbnailUrl() ?: response.header?.musicDetailHeaderRenderer?.thumbnail
                ?.croppedSquareThumbnailRenderer?.getThumbnailUrl()
        }

        fun getArtists(response: BrowseResponse): List<Artist> {
            val artists = getHeader(response)?.straplineTextOne?.runs?.artistElements()?.map {
                Artist(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                )
            } ?: response.header?.musicDetailHeaderRenderer?.subtitle?.runs?.splitBySeparator()?.getOrNull(1)?.artistElements()?.map {
                Artist(
                    name = it.text,
                    id = it.navigationEndpoint?.browseEndpoint?.browseId
                )
            } ?: emptyList()

            return artists
        }

        private fun getHeader(response: BrowseResponse): MusicResponsiveHeaderRenderer? {
            val tabs = response.contents?.singleColumnBrowseResultsRenderer?.tabs
                ?: response.contents?.twoColumnBrowseResultsRenderer?.tabs
            return tabs?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents
                ?.firstNotNullOfOrNull { it.musicResponsiveHeaderRenderer }
        }

        fun sections(response: BrowseResponse): List<SectionListRenderer.Content> =
            response.contents?.twoColumnBrowseResultsRenderer?.secondaryContents?.sectionListRenderer?.contents.orEmpty() +
                (response.contents?.singleColumnBrowseResultsRenderer?.tabs
                    ?: response.contents?.twoColumnBrowseResultsRenderer?.tabs)
                    ?.firstOrNull()?.tabRenderer?.content?.sectionListRenderer?.contents.orEmpty()

        /** Null means the server did not return a track shelf, distinct from an empty shelf. */
        fun trackContents(response: BrowseResponse): List<MusicShelfRenderer.Content>? {
            val shelves = sections(response).mapNotNull { it.musicShelfRenderer?.contents ?: it.musicPlaylistShelfRenderer?.contents }
            return if (shelves.isNotEmpty()) shelves.flatten() else
                response.onResponseReceivedActions?.mapNotNull { it.appendContinuationItemsAction?.continuationItems }
                    ?.takeIf { it.isNotEmpty() }?.flatten()
                    ?: response.continuationContents?.musicShelfContinuation?.contents
                    ?: response.continuationContents?.musicPlaylistShelfContinuation?.contents
        }

        fun continuation(response: BrowseResponse): String? =
            trackContents(response)?.getContinuation()
                ?: sections(response).firstNotNullOfOrNull { it.musicShelfRenderer?.continuations?.getContinuation() }
                ?: response.continuationContents?.musicShelfContinuation?.continuations?.getContinuation()
                ?: response.continuationContents?.musicPlaylistShelfContinuation?.continuations?.getContinuation()

        fun getSongs(response: BrowseResponse, album: AlbumItem, language: String = com.zionhuang.innertube.YouTube.locale.hl): List<SongItem> {
            return trackContents(response)?.getItems()?.mapNotNull { getSong(it, album, language) }.orEmpty()
        }

        fun getSong(renderer: MusicResponsiveListItemRenderer, album: AlbumItem? = null, language: String = com.zionhuang.innertube.YouTube.locale.hl): SongItem? {
            return SongItem(
                    artistCredit = (PageHelper.artistRuns(renderer.flexColumns)).toArtistCredit("AlbumPage", language).withVideoSource(renderer),
                    artistBrowseIds = renderer.menu.artistBrowseIds(),
                id = renderer.playlistItemData?.videoId ?: return null,
                title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text
                    ?.runs?.joinToString("") { it.text }?.takeIf { it.isNotBlank() } ?: return null,
                artists = PageHelper.artistRuns(renderer.flexColumns).artistElements().map {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId
                    )
                },
                album = album?.let {
                    Album(it.title, it.browseId)
                } ?: renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                    ?.takeIf { it.navigationEndpoint?.browseEndpoint?.browseId != null }?.let {
                    Album(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId!!
                    )
                },
                duration = renderer.fixedColumns?.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                    ?.text?.parseTime(),
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: album?.thumbnail ?: return null,
                endpoint = PageHelper.searchWatchEndpoint(renderer),
                isPlayable = renderer.musicItemRendererDisplayPolicy != "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT" &&
                    renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
                        ?.playNavigationEndpoint?.showDialogCommand == null &&
                    renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                        ?.firstOrNull()?.navigationEndpoint?.showDialogCommand == null,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null
            )
        }
    }
}
