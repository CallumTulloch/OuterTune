package com.dd3boh.outertune.ui.menu

import com.dd3boh.outertune.utils.displayName

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalNetworkConnected
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ArtistSongSortType
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.component.items.ArtistListItem
import com.dd3boh.outertune.ui.dialog.LocalArtistLinkDialog
import com.dd3boh.outertune.ui.dialog.LocalArtistLinksDialog
import com.zionhuang.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ArtistMenu(
    originalArtist: Artist,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isNetworkConnected = LocalNetworkConnected.current
    val artistState = database.artist(originalArtist.id).collectAsState(initial = originalArtist)
    val artist = artistState.value ?: originalArtist
    var showLinkDialog by rememberSaveable(originalArtist.id) { mutableStateOf(false) }
    var showLinkedSources by rememberSaveable(originalArtist.id) { mutableStateOf(false) }
    val rawSource by remember(database, originalArtist.id) { database.rawArtist(originalArtist.id) }
        .collectAsState(initial = originalArtist)
    val onlineId = artist.artist.onlineArtistId
    val linkedSources = if (onlineId != null) {
        val sources by remember(database, onlineId) { database.localArtistLinkSources(onlineId) }
            .collectAsState(initial = emptyList())
        sources
    } else emptyList()

    if (showLinkedSources && onlineId != null) {
        LocalArtistLinksDialog(onlineArtistId = onlineId, onDismiss = { showLinkedSources = false })
    }

    val linkSource = rawSource?.takeIf { it.artist.isLocal }
    if (showLinkDialog && linkSource != null) {
        LocalArtistLinkDialog(
            localArtist = linkSource,
            onDismiss = { showLinkDialog = false },
            onLinked = { showLinkDialog = false; onDismiss() },
        )
    }

    ArtistListItem(
        artist = artist,
        badges = {},
        trailingContent = {
            IconButton(
                onClick = {
                    database.transaction {
                        toggleArtistBookmark(artist.id)
                    }
                }
            ) {
                Icon(
                    painter = painterResource(if (artist.artist.bookmarkedAt != null) R.drawable.favorite else R.drawable.favorite_border),
                    tint = if (artist.artist.bookmarkedAt != null) MaterialTheme.colorScheme.error else LocalContentColor.current,
                    contentDescription = null
                )
            }
        }
    )

    HorizontalDivider()

    GridMenu(
        contentPadding = PaddingValues(
            start = 8.dp,
            top = 8.dp,
            end = 8.dp,
            bottom = 8.dp + WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
        )
    ) {
        if (artist.artist.isLocal) {
            GridMenuItem(
                icon = Icons.Rounded.Link,
                title = if (artist.localLink == null) R.string.local_artist_link_title
                    else R.string.local_artist_link_manage,
            ) { showLinkDialog = true }
        }
        if (linkedSources.isNotEmpty()) {
            GridMenuItem(icon = Icons.Rounded.Link, title = R.string.local_artist_links_sources) {
                showLinkedSources = true
            }
        }
        if (artist.songCount > 0) {
            GridMenuItem(
                icon = Icons.Rounded.PlayArrow,
                title = R.string.play
            ) {
                coroutineScope.launch {
                    val songs = withContext(Dispatchers.IO) {
                        database.artistSongs(artist.id, ArtistSongSortType.CREATE_DATE, true).first()
                            .map { it.toMediaMetadata() }
                    }

                    val playlistId = withContext(Dispatchers.IO) {
                        artist.artist.onlineArtistId?.takeIf { isNetworkConnected }?.let {
                            YouTube.artist(it).getOrNull()?.artist?.shuffleEndpoint?.playlistId
                        }
                    }

                    playerConnection.playQueue(
                        ListQueue(
                            title = artist.artist.displayName,
                            items = songs,
                            playlistId = playlistId
                        )
                    )
                }
                onDismiss()
            }
            GridMenuItem(
                icon = Icons.Rounded.Shuffle,
                title = R.string.shuffle
            ) {
                coroutineScope.launch {
                    val songs = withContext(Dispatchers.IO) {
                        database.artistSongs(artist.id, ArtistSongSortType.CREATE_DATE, true).first()
                            .map { it.toMediaMetadata() }
                            .shuffled()
                    }

                    val playlistId = withContext(Dispatchers.IO) {
                        artist.artist.onlineArtistId?.takeIf { isNetworkConnected }?.let {
                            YouTube.artist(it).getOrNull()?.artist?.shuffleEndpoint?.playlistId
                        }
                    }

                    playerConnection.playQueue(
                        ListQueue(
                            title = artist.artist.displayName,
                            items = songs,
                            playlistId = playlistId
                        )
                    )
                }
                onDismiss()
            }
        }
        if (artist.artist.isYouTubeArtist) {
            GridMenuItem(
                icon = Icons.Rounded.Share,
                title = R.string.share
            ) {
                onDismiss()
                val intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "https://music.youtube.com/channel/${artist.artist.onlineArtistId}")
                }
                context.startActivity(Intent.createChooser(intent, null))
            }
        }
    }
}
