/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_UGC
import com.zionhuang.innertube.models.YTItem

/** Three metadata levels used only when the all-results search opts in. */
@Composable
internal fun SearchResultListItem(
    item: YTItem,
    subtitle: String?,
    modifier: Modifier = Modifier,
    albumIndex: Int? = null,
    isSelected: Boolean = false,
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    badges: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable RowScope.() -> Unit = {},
) {
    val kindLabel = when (item) {
        is AlbumItem -> R.string.search_result_album
        is SongItem -> {
            val videoType = item.endpoint?.watchEndpointMusicSupportedConfigs
                ?.watchEndpointMusicConfig?.musicVideoType
                ?: item.artistCredit?.evidence
                    ?.firstOrNull { it.startsWith("video-source:") }
                    ?.removePrefix("video-source:")
            when (videoType) {
                MUSIC_VIDEO_TYPE_ATV -> R.string.search_result_song
                MUSIC_VIDEO_TYPE_OMV, MUSIC_VIDEO_TYPE_UGC -> R.string.search_result_video
                else -> R.string.search_result_song_or_video
            }
        }

        else -> return
    }
    val playbackLabel = when {
        !isActive -> null
        item is AlbumItem && isPlaying -> R.string.search_result_album_track_playing
        item is AlbumItem -> R.string.search_result_album_track_paused
        isPlaying -> R.string.search_result_playing
        else -> R.string.search_result_paused
    }
    val backgroundColor = when {
        isActive && isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        isActive -> MaterialTheme.colorScheme.secondaryContainer
        isSelected -> MaterialTheme.colorScheme.inversePrimary.copy(alpha = 0.4f)
        else -> Color.Transparent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(backgroundColor)
            .heightIn(min = 84.dp)
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        ItemThumbnail(
            thumbnailUrl = item.thumbnail,
            albumIndex = albumIndex,
            isActive = isActive,
            isPlaying = isPlaying,
            showPauseIcon = true,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.size(48.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(kindLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .background(
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                if (playbackLabel != null) {
                    Text(
                        text = stringResource(playbackLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                badges()
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        trailingContent()
    }
}
