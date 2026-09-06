package com.zionhuang.innertube.pages

import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer.FlexColumn
import com.zionhuang.innertube.models.MusicResponsiveListItemRenderer
import com.zionhuang.innertube.models.Run
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.artistBylineRuns

object PageHelper {
    /** Retain a supplied search playback endpoint, including its explicit music/video type. */
    fun searchWatchEndpoint(renderer: MusicResponsiveListItemRenderer): WatchEndpoint? {
        val endpoints = listOfNotNull(
            renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer
                ?.playNavigationEndpoint?.anyWatchEndpoint,
            renderer.flexColumns.firstOrNull()?.musicResponsiveListItemFlexColumnRenderer?.text?.runs
                ?.firstOrNull()?.navigationEndpoint?.anyWatchEndpoint,
            renderer.navigationEndpoint?.anyWatchEndpoint,
        ).filter { it.videoId == null || it.videoId == renderer.playlistItemData?.videoId }
        return endpoints.firstOrNull { it.watchEndpointMusicSupportedConfigs != null } ?: endpoints.firstOrNull()
    }

    fun artistRuns(columns: List<FlexColumn>): List<Run> {
        val column = columns.firstOrNull { column ->
            column.musicResponsiveListItemFlexColumnRenderer.text?.runs.orEmpty().any {
                it.navigationEndpoint?.browseEndpoint?.isArtistEndpoint == true
            }
        } ?: columns.getOrNull(1)
        return column?.musicResponsiveListItemFlexColumnRenderer?.text?.runs.orEmpty().artistBylineRuns()
    }

    fun extractRuns(columns: List<FlexColumn>, typeLike: String): List<Run> {
        val filteredRuns = mutableListOf<Run>()
        for (column in columns) {
            val runs = column.musicResponsiveListItemFlexColumnRenderer.text?.runs
                ?: continue

            for (run in runs) {
                val typeStr = run.navigationEndpoint?.watchEndpoint?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType
                    ?: run.navigationEndpoint?.browseEndpoint?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType
                    ?: continue

                if (typeLike in typeStr) {
                    filteredRuns.add(run)
                }
            }
        }
        return filteredRuns
    }
}
