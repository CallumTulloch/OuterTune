package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.Album
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.BrowseEndpoint
import com.zionhuang.innertube.models.MusicCarouselShelfRenderer
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.MusicShelfRenderer
import com.zionhuang.innertube.models.MusicTwoRowItemRenderer
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.SectionListRenderer
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.getItems
import com.zionhuang.innertube.models.withVideoSource
import com.zionhuang.innertube.models.toArtistCredit
import com.zionhuang.innertube.models.artistBrowseIds
import com.zionhuang.innertube.models.toAlbumArtistCredit
import com.zionhuang.innertube.models.artistElements
import com.zionhuang.innertube.models.splitBySeparator

data class ArtistSection(
    val title: String,
    val items: List<YTItem>,
    val moreEndpoint: BrowseEndpoint?,
)

data class ArtistPage(
    val artist: ArtistItem,
    val sections: List<ArtistSection>,
    val description: String?,
) {
    companion object {
        fun fromSectionListRendererContent(content: SectionListRenderer.Content, language: String = com.zionhuang.innertube.YouTube.locale.hl): ArtistSection? {
            return when {
                content.musicShelfRenderer != null -> fromMusicShelfRenderer(content.musicShelfRenderer, language = language)
                content.musicCarouselShelfRenderer != null -> fromMusicCarouselShelfRenderer(content.musicCarouselShelfRenderer, language = language)
                else -> null
            }
        }

        private fun fromMusicShelfRenderer(renderer: MusicShelfRenderer, language: String = com.zionhuang.innertube.YouTube.locale.hl): ArtistSection? {
            return ArtistSection(
                title = renderer.title?.runs?.firstOrNull()?.text ?: "",
                items = renderer.contents?.getItems()?.mapNotNull {
                    fromMusicResponsiveListItemRenderer(it, language = language)
                }?.ifEmpty { null } ?: return null,
                moreEndpoint = renderer.title?.runs?.firstOrNull()?.navigationEndpoint?.browseEndpoint
            )
        }

        private fun fromMusicCarouselShelfRenderer(renderer: MusicCarouselShelfRenderer, language: String = com.zionhuang.innertube.YouTube.locale.hl): ArtistSection? {
            return ArtistSection(
                title = renderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text ?: return null,
                items = renderer.contents.mapNotNull {
                    it.musicTwoRowItemRenderer?.let { renderer ->
                        fromMusicTwoRowItemRenderer(renderer, language = language)
                    }
                }.ifEmpty { null } ?: return null,
                moreEndpoint = renderer.header.musicCarouselShelfBasicHeaderRenderer.moreContentButton?.buttonRenderer?.navigationEndpoint?.browseEndpoint
            )
        }

        private fun fromMusicResponsiveListItemRenderer(renderer: MusicResponsiveListItemRenderer, language: String = com.zionhuang.innertube.YouTube.locale.hl): SongItem? {
            val artistRuns = PageHelper.artistRuns(renderer.flexColumns)
            return SongItem(
                artistCredit = (artistRuns).toArtistCredit("ArtistPage", language).withVideoSource(renderer),
                artistBrowseIds = renderer.menu.artistBrowseIds(),
                id = renderer.playlistItemData?.videoId ?: return null,
                title = renderer.flexColumns.firstOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer?.text?.runs?.firstOrNull()
                    ?.text ?: return null,
                artists = artistRuns.artistElements().map {
                    Artist(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId
                    )
                },
                album = PageHelper.extractRuns(renderer.flexColumns, "MUSIC_PAGE_TYPE_ALBUM").ifEmpty { renderer.flexColumns.getOrNull(3)?.musicResponsiveListItemFlexColumnRenderer?.text?.runs }?.firstOrNull()?.let {
                    Album(
                        name = it.text,
                        id = it.navigationEndpoint?.browseEndpoint?.browseId ?: return@let null
                    )
                },
                duration = null,
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                explicit = renderer.badges?.find {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } != null,
                endpoint = renderer.overlay?.musicItemThumbnailOverlayRenderer?.content
                    ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint
            )
        }

        private fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer, language: String = com.zionhuang.innertube.YouTube.locale.hl): YTItem? {
            return when {
                renderer.isSong -> {
                    SongItem(
                        artistCredit = (renderer.subtitle?.runs?.splitBySeparator()?.firstOrNull().orEmpty()).toArtistCredit("ArtistPage", language).withVideoSource(renderer),
                        artistBrowseIds = renderer.menu.artistBrowseIds(),
                        id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = renderer.subtitle?.runs?.splitBySeparator()?.firstOrNull()
                            ?.artistElements()?.map {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId
                            )
                        }.orEmpty(),
                        album = null,
                        duration = null,
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
                }

                renderer.isAlbum -> {
                    AlbumItem(
                        artistCredit = (renderer.subtitle?.runs.orEmpty()).toAlbumArtistCredit("ArtistPage", language),
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.anyWatchEndpoint?.playlistId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = null,
                        year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null
                    )
                }

                renderer.isPlaylist -> {
                    // Playlist from YouTube Music
                    PlaylistItem(
                        id = renderer.navigationEndpoint.browseEndpoint?.browseId?.removePrefix("VL") ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        author = Artist(
                            name = renderer.subtitle?.runs?.lastOrNull()?.text ?: return null,
                            id = null
                        ),
                        songCountText = null,
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        playEndpoint = renderer.thumbnailOverlay
                            ?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint ?: return null,
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint = renderer.menu.menuRenderer.items.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null
                    )
                }

                renderer.isArtist -> {
                    ArtistItem(
                        id = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        title = renderer.title.runs?.lastOrNull()?.text ?: return null,
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        channelId = renderer.menu?.menuRenderer?.items?.find {
                            it.toggleMenuServiceItemRenderer?.defaultIcon?.iconType == "SUBSCRIBE"
                        }?.toggleMenuServiceItemRenderer?.defaultServiceEndpoint?.subscribeEndpoint?.channelIds?.firstOrNull(),
                        shuffleEndpoint = renderer.menu?.menuRenderer?.items?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                        radioEndpoint = renderer.menu.menuRenderer.items.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint ?: return null,
                    )
                }

                else -> null
            }
        }
    }
}
