package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.MusicTwoRowItemRenderer
import com.zionhuang.innertube.models.toAlbumArtistCredit
import com.zionhuang.innertube.models.toArtist
import com.zionhuang.innertube.models.artistElements
import com.zionhuang.innertube.models.splitBySeparator

object NewReleaseAlbumPage {
    fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer, language: String = com.zionhuang.innertube.YouTube.locale.hl): AlbumItem? {
        return AlbumItem(
            artistCredit = (renderer.subtitle?.runs.orEmpty()).toAlbumArtistCredit("NewReleaseAlbumPage", language),
            browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
            playlistId = renderer.thumbnailOverlay
                ?.musicItemThumbnailOverlayRenderer?.content
                ?.musicPlayButtonRenderer?.playNavigationEndpoint
                ?.watchPlaylistEndpoint?.playlistId ?: return null,
            title = renderer.title.runs?.firstOrNull()?.text ?: return null,
            artists = renderer.subtitle?.runs?.splitBySeparator()?.getOrNull(1)?.artistElements()?.map {
                it.toArtist()
            } ?: return null,
            year = renderer.subtitle.runs.lastOrNull()?.text?.toIntOrNull(),
            thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
            explicit = renderer.subtitleBadges?.find {
                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
            } != null
        )
    }
}
