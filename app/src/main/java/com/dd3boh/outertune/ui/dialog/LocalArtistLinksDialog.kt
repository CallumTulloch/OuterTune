package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.LocalArtistLinkSource
import com.dd3boh.outertune.viewmodels.LocalArtistLinksViewModel
import com.dd3boh.outertune.utils.displayArtistTarget

@Composable
fun LocalArtistLinksDialog(onlineArtistId: String? = null, onDismiss: () -> Unit) {
    val viewModel: LocalArtistLinksViewModel = hiltViewModel(key = "local-artist-links:${onlineArtistId ?: "all"}")
    val state by viewModel.state.collectAsState()
    DisposableEffect(viewModel, onlineArtistId) {
        viewModel.open(onlineArtistId)
        onDispose { viewModel.close() }
    }
    val dismiss = {
        if (state.editing == null) {
            viewModel.close()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(
            dismissOnBackPress = state.editing == null,
            dismissOnClickOutside = state.editing == null,
            usePlatformDefaultWidth = false,
        ),
        title = {
            Text(stringResource(if (onlineArtistId == null) R.string.local_artist_links_title
                else R.string.local_artist_links_sources))
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    stringResource(R.string.local_artist_links_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    state.loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.local_artist_link_loading))
                    }
                    state.failed -> {
                        Text(stringResource(R.string.local_artist_links_load_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                    }
                    state.sources.isEmpty() -> Text(stringResource(R.string.local_artist_links_empty))
                    else -> state.sources.forEach { source ->
                        key(source.localArtist.id) {
                            LocalArtistLinkSourceDetails(source, onEdit = { viewModel.edit(source.localArtist.id) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = dismiss, enabled = state.editing == null) {
                Text(stringResource(R.string.local_artist_links_close))
            }
        },
    )
    state.editing?.let { localArtist ->
        LocalArtistLinkDialog(localArtist = localArtist, onDismiss = viewModel::finishEditing)
    }
}

@Composable
private fun LocalArtistLinkSourceDetails(source: LocalArtistLinkSource, onEdit: () -> Unit) {
    val localArtist = source.localArtist
    val sourceSongCount = if (localArtist.artist.isChannelSource) source.songs.size else localArtist.songCount
    var showFolders by rememberSaveable(localArtist.id) { mutableStateOf(false) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (localArtist.artist.isChannelSource) R.string.channel_artist_source_name
                        else R.string.local_artist_links_file_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    localArtist.artist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    pluralStringResource(R.plurals.n_song, sourceSongCount, sourceSongCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sourceSongCount == 0) {
                    Text(
                        stringResource(R.string.local_artist_links_no_songs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            localArtist.localLink?.let { link ->
                val target = localArtist.artist.displayArtistTarget().takeIf { it.id == link.onlineArtistId }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(R.drawable.artist),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            AsyncImage(
                                model = target?.thumbnailUrl ?: link.thumbnailUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(stringResource(R.string.local_artist_link_current), style = MaterialTheme.typography.labelMedium)
                            Text(target?.name ?: link.onlineName, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
            HorizontalDivider()
            if (localArtist.artist.isChannelSource) {
                ChannelArtistSourceDetails(source)
            } else if (source.folders.isEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.local_artist_links_no_folders),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                TextButton(
                    onClick = { showFolders = !showFolders },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(if (showFolders) R.string.local_artist_links_hide_folders
                            else R.string.local_artist_links_show_folders, source.folders.size),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Icon(if (showFolders) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                }
                if (showFolders) SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        source.folders.forEach { folder ->
                            Text(folder, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.local_artist_link_manage), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
