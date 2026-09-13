package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
        properties = DialogProperties(dismissOnBackPress = state.editing == null,
            dismissOnClickOutside = state.editing == null),
        title = { Text(stringResource(if (onlineArtistId == null) R.string.local_artist_links_title
            else R.string.local_artist_links_sources)) },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.local_artist_links_description))
                when {
                    state.loading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    state.failed -> {
                        Text(stringResource(R.string.local_artist_links_load_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                    }
                    state.sources.isEmpty() -> Text(stringResource(R.string.local_artist_links_empty))
                    else -> state.sources.forEach { source ->
                        key(source.localArtist.id) {
                            HorizontalDivider()
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
    var showFolders by rememberSaveable(localArtist.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.local_artist_links_file_name, localArtist.artist.name),
            style = MaterialTheme.typography.titleSmall)
        Text(pluralStringResource(R.plurals.n_song, localArtist.songCount, localArtist.songCount))
        localArtist.localLink?.let { link ->
            val target = localArtist.artist.displayArtistTarget().takeIf { it.id == link.onlineArtistId }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(model = target?.thumbnailUrl ?: link.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(40.dp).clip(CircleShape))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.local_artist_link_current), style = MaterialTheme.typography.labelMedium)
                    Text(target?.name ?: link.onlineName)
                }
            }
        }
        if (source.folders.isEmpty()) {
            Text(stringResource(R.string.local_artist_links_no_folders), style = MaterialTheme.typography.bodySmall)
        } else {
            TextButton(onClick = { showFolders = !showFolders }) {
                Text(stringResource(if (showFolders) R.string.local_artist_links_hide_folders
                    else R.string.local_artist_links_show_folders, source.folders.size))
            }
            if (showFolders) SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    source.folders.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        TextButton(onClick = onEdit) { Text(stringResource(R.string.local_artist_link_manage)) }
    }
}
