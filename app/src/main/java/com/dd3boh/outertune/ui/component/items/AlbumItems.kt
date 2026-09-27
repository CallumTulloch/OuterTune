package com.dd3boh.outertune.ui.component.items

import com.dd3boh.outertune.utils.displayTitle

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.utils.getNSongsString
import com.dd3boh.outertune.utils.getDownloadState
import com.dd3boh.outertune.utils.artistDisplayText
import com.dd3boh.outertune.utils.joinByBullet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
private fun AlbumBadges(album: Album, showLikedIcon: Boolean = true) {
    if (showLikedIcon && album.album.bookmarkedAt != null) {
        Icon.Favorite()
    }

    // Local albums have no download badge; do not load their tracks just to discard them.
    if (album.album.isLocal) return

    val database = LocalDatabase.current
    val downloadUtil = LocalDownloadUtil.current
    var songs by remember(album.id, album.album.isLocal) {
        mutableStateOf(emptyList<Song>())
    }

    LaunchedEffect(database, album.id, album.album.isLocal) {
        database.albumSongs(album.id).collect {
            songs = it
        }
    }

    var downloadState by remember(album.id, songs) {
        mutableIntStateOf(Download.STATE_STOPPED)
    }

    LaunchedEffect(downloadUtil, album.id, songs) {
        val remoteSongs = songs.filterNot { it.song.isLocal }
        if (remoteSongs.isEmpty()) return@LaunchedEffect
        downloadUtil.downloads.collect { downloads ->
            downloadState = getDownloadState(remoteSongs.map { downloads[it.id] })
        }
    }

    Icon.Download(downloadState)
}

@Composable
fun AlbumListItem(
    album: Album,
    modifier: Modifier = Modifier,
    showLikedIcon: Boolean = true,
    badges: @Composable RowScope.() -> Unit = {
        AlbumBadges(album, showLikedIcon)
    },
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    trailingContent: @Composable RowScope.() -> Unit = {},
) = ListItem(
    title = album.album.displayTitle,
    subtitle = joinByBullet(
        album.artistDisplayText(),
        album.takeIf { it.album.songCount != 0 }?.let { album ->
            getNSongsString(album.album.songCount, album.downloadCount)
        },
        album.album.year?.toString()
    ),
    badges = badges,
    thumbnailContent = {
        ItemThumbnail(
            thumbnailUrl = album.album.thumbnailUrl,
            preferredSize = with(LocalDensity.current) { ListThumbnailSize.roundToPx() },
            placeholderIcon = Icons.Outlined.Album,
            isActive = isActive,
            isPlaying = isPlaying,
            shape = RoundedCornerShape(ThumbnailCornerRadius),
            modifier = Modifier.size(ListThumbnailSize)
        )
    },
    trailingContent = trailingContent,
    modifier = modifier
)

@Composable
fun AlbumGridItem(
    album: Album,
    modifier: Modifier = Modifier,
    coroutineScope: CoroutineScope,
    badges: @Composable RowScope.() -> Unit = {
        AlbumBadges(album)
    },
    isActive: Boolean = false,
    isPlaying: Boolean = false,
    fillMaxWidth: Boolean = false,
    showPlayButton: Boolean = true,
) = GridItem(
    title = album.album.displayTitle,
    subtitle = album.artistDisplayText(),
    badges = badges,
    thumbnailContent = {
        val database = LocalDatabase.current
        val playerConnection = LocalPlayerConnection.current ?: return@GridItem

        ItemThumbnail(
            thumbnailUrl = album.album.thumbnailUrl,
            preferredSize = with(LocalDensity.current) { minOf(maxWidth, maxHeight).roundToPx() },
            placeholderIcon = Icons.Outlined.Album,
            isActive = isActive,
            isPlaying = isPlaying,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(ThumbnailCornerRadius),
        )

        AlbumPlayButton(
            visible = showPlayButton && !isActive,
            onClick = {
                coroutineScope.launch {
                    database.albumWithSongs(album.id).first()?.songs
                        ?.map { it.toMediaMetadata() }
                        ?.let {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = album.album.displayTitle,
                                    items = it
                                )
                            )
                        }
                }
            }
        )
    },
    fillMaxWidth = fillMaxWidth,
    modifier = modifier
)
