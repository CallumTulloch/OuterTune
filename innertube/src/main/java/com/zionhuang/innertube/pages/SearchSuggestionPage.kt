package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.withVideoSource
import com.zionhuang.innertube.models.toArtistCredit
import com.zionhuang.innertube.models.artistBrowseIds
import com.zionhuang.innertube.models.toAlbumArtistCredit
import com.zionhuang.innertube.models.artistElements
import com.zionhuang.innertube.models.splitBySeparator

object SearchSuggestionPage {
    fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer, language: String = com.zionhuang.innertube.YouTube.locale.hl): YTItem? {
        return when {
            renderer.isSong -> {
                SongItem(
                    endpoint = PageHelper.searchWatchEndpoint(renderer),
                    artistCredit = (renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs.orEmpty().splitBySeparator().getOrNull(1).orEmpty()).toArtistCredit("SearchSuggestionPage", language).withVideoSource(renderer),
                    artistBrowseIds = renderer.menu.artistBrowseIds(),
                    id = renderer.playlistItemData?.videoId ?: return null,
                    title = renderer.flexColumns.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                        ?.text ?: return null,
                    artists = renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator()
                            ?.getOrNull(1)?.artistElements()?.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        } ?: return null,
                    album = renderer.flexColumns.getOrNull(2)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.let {
                        Album(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return null
                        )
                    },
                    duration = null,
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    explicit = renderer.badges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null
                )
            }
            renderer.isArtist -> {
                ArtistItem(
                    id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    title = renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()?.text ?: return null,
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    shuffleEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    radioEndpoint = renderer.menu?.menuRenderer?.items
                        ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                        ?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint
                )
            }
            renderer.isAlbum -> {
                val secondaryLine = renderer.flexColumns.getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.splitBySeparator() ?: return null
                AlbumItem(
                    artistCredit = (renderer.flexColumns.getOrNull(1)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs.orEmpty()).toAlbumArtistCredit("SearchSuggestionPage", language),
                    browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                    playlistId = renderer.menu?.menuRenderer?.items?.find {
                        it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                    }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint?.playlistId ?: return null,
                    title = renderer.flexColumns.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                        ?.text ?: return null,
                    artists = secondaryLine.getOrNull(1)?.artistElements()?.map {
                        Artist(
                            name = it.text,
                            id = it.navigationEndpoint?.browseEndpoint?.browseId
                        )
                    } ?: return null,
                    year = secondaryLine.lastOrNull()?.firstOrNull()?.text?.toIntOrNull(),
                    thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                    explicit = renderer.badges?.find {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } != null
                )
            }
            else -> null
        }
    }
}
