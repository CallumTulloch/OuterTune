package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.viewmodels.LocalArtistLinkViewModel
import com.dd3boh.outertune.viewmodels.LocalArtistLinkViewModel.Busy
import com.dd3boh.outertune.viewmodels.LocalArtistLinkViewModel.Failure

@Composable
fun LocalArtistLinkDialog(
    localArtist: Artist,
    onDismiss: () -> Unit,
    onLinked: () -> Unit = {},
) {
    if (!localArtist.artist.isLocal) return
    val viewModel: LocalArtistLinkViewModel = hiltViewModel(key = "local-artist-link:${localArtist.id}")
    val state by viewModel.state.collectAsState()
    val currentOnLinked by rememberUpdatedState(onLinked)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(viewModel, localArtist.id) {
        viewModel.open(localArtist)
        onDispose { viewModel.close() }
    }
    LaunchedEffect(state.completed) {
        if (state.completed) {
            currentOnLinked()
            currentOnDismiss()
        }
    }
    val dismiss = {
        if (!state.saving) {
            viewModel.close()
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        properties = DialogProperties(dismissOnBackPress = !state.saving, dismissOnClickOutside = !state.saving),
        title = { Text(stringResource(if (state.link == null) R.string.local_artist_link_title else R.string.local_artist_link_manage)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.local_artist_link_source, localArtist.title, localArtist.songCount))
                state.link?.let { link ->
                    Text(stringResource(R.string.local_artist_link_current), style = MaterialTheme.typography.labelLarge)
                    ArtistLinkName(link.onlineName, link.thumbnailUrl)
                    TextButton(onClick = viewModel::askUnlink, enabled = state.loaded && !state.saving) {
                        Text(stringResource(R.string.local_artist_link_remove))
                    }
                    HorizontalDivider()
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::changeQuery,
                    label = { Text(stringResource(R.string.local_artist_link_query)) },
                    singleLine = true,
                    enabled = state.loaded && !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = viewModel::search,
                    enabled = state.loaded && !state.saving && state.query.isNotBlank() && state.busy != Busy.SEARCHING) {
                    Text(stringResource(R.string.search))
                }
                if (state.busy != Busy.NONE) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(when (state.busy) {
                            Busy.SAVING -> R.string.local_artist_link_saving
                            Busy.UNLINKING -> R.string.local_artist_link_removing
                            else -> R.string.local_artist_link_loading
                        }))
                    }
                }
                state.failure?.let { failure ->
                    Text(stringResource(when (failure) {
                        Failure.LOAD -> R.string.local_artist_link_load_failed
                        Failure.SEARCH -> R.string.local_artist_link_search_failed
                        Failure.PREVIEW -> R.string.local_artist_link_preview_failed
                        Failure.SAVE -> R.string.local_artist_link_save_failed
                        Failure.UNLINK -> R.string.local_artist_link_remove_failed
                        Failure.INVALID_INPUT -> R.string.local_artist_link_invalid_input
                        Failure.INVALID_ARTIST -> R.string.local_artist_link_invalid_artist
                        Failure.CONTEXT_CHANGED -> R.string.local_artist_link_context_changed
                        Failure.LINK_CHANGED -> R.string.local_artist_link_changed
                    }), color = MaterialTheme.colorScheme.error)
                    if (!state.loaded) {
                        TextButton(onClick = { viewModel.open(localArtist) }) { Text(stringResource(R.string.retry)) }
                    }
                }
                state.preview?.let { candidate ->
                    HorizontalDivider()
                    Text(stringResource(R.string.local_artist_link_preview), style = MaterialTheme.typography.labelLarge)
                    ArtistLinkName(candidate.name, candidate.thumbnailUrl)
                    if (candidate.albumTitles.isNotEmpty()) {
                        Text(stringResource(R.string.local_artist_link_albums), style = MaterialTheme.typography.labelLarge)
                        candidate.albumTitles.take(3).forEach { Text(it) }
                    }
                    Text(stringResource(R.string.local_artist_link_confirmation,
                        localArtist.title, localArtist.songCount, candidate.name))
                }
                if (state.candidates.isNotEmpty()) {
                    Text(stringResource(R.string.local_artist_link_candidates), style = MaterialTheme.typography.labelLarge)
                    state.candidates.forEach { candidate ->
                        ArtistLinkName(candidate.title, candidate.thumbnail,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !state.saving) {
                                viewModel.select(candidate.id)
                            }.padding(vertical = 8.dp))
                    }
                } else if (state.searched && state.busy == Busy.NONE && state.failure == null) {
                    Text(stringResource(R.string.local_artist_link_no_results))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirm,
                enabled = state.loaded && state.preview != null && state.busy == Busy.NONE && !state.confirmUnlink) {
                Text(stringResource(if (state.link == null) R.string.local_artist_link_confirm else R.string.local_artist_link_change))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, enabled = !state.saving) { Text(stringResource(android.R.string.cancel)) }
        },
    )
    if (state.confirmUnlink) {
        AlertDialog(
            onDismissRequest = viewModel::cancelUnlink,
            properties = DialogProperties(dismissOnBackPress = !state.saving, dismissOnClickOutside = !state.saving),
            title = { Text(stringResource(R.string.local_artist_link_remove)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.local_artist_link_remove_confirmation,
                        localArtist.title, state.link?.onlineName.orEmpty()))
                    if (state.busy == Busy.UNLINKING) {
                        Text(stringResource(R.string.local_artist_link_removing))
                    }
                    if (state.failure == Failure.UNLINK) {
                        Text(stringResource(R.string.local_artist_link_remove_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::unlink, enabled = !state.saving) {
                    Text(stringResource(R.string.local_artist_link_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelUnlink, enabled = !state.saving) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ArtistLinkName(name: String, thumbnailUrl: String?, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(model = thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(CircleShape))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
