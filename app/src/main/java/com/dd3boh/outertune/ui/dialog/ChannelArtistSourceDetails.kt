package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.db.entities.LocalArtistLinkSource

/** Shows the saved track scope of a channel source, before any link is written. */
@Composable
internal fun ChannelArtistSourceDetails(source: LocalArtistLinkSource) {
    var showSongs by rememberSaveable(source.localArtist.id) { mutableStateOf(false) }
    source.localArtist.artist.sourceChannelId?.let { channelId ->
        SelectionContainer {
            Text(
                stringResource(R.string.channel_artist_source_id, channelId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        stringResource(if (source.localArtist.artist.sourceChannelId.isNullOrBlank())
            R.string.channel_artist_link_video_scope else R.string.channel_artist_link_scope),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (source.songs.isNotEmpty()) {
        TextButton(
            onClick = { showSongs = !showSongs },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                stringResource(if (showSongs) R.string.channel_artist_hide_songs else R.string.channel_artist_show_songs,
                    source.songs.size),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Icon(if (showSongs) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        if (showSongs) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                source.songs.forEach { song ->
                    Text(song.title, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
