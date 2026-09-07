package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.utils.displayName

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.dialog.ArtistInformationDialog
import com.dd3boh.outertune.ui.utils.rememberResolvedArtistMetadata
import com.dd3boh.outertune.utils.artistDisplayText
import com.dd3boh.outertune.utils.artistNameSeparator
import com.dd3boh.outertune.utils.hasCompleteArtistList
import com.dd3boh.outertune.utils.isVideoCredit

@Composable
fun PlayerArtistText(
    metadata: MediaMetadata,
    color: Color,
    navController: NavController,
    onNavigate: () -> Unit,
) {
    val resolved = rememberResolvedArtistMetadata(metadata)
    var showInformation by rememberSaveable(metadata.id) { mutableStateOf(false) }
    if (resolved.hasCompleteArtistList() && resolved.artists.isNotEmpty()) {
        Row {
            resolved.artists.forEachIndexed { index, artist ->
                Text(
                    text = if (resolved.isLocal) artist.name else artist.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    maxLines = 1,
                    modifier = Modifier
                        .basicMarquee(iterations = 1, initialDelayMillis = 5000)
                        .clickable {
                            if (!artist.id.isNullOrBlank()) {
                                navController.navigate("artist/${artist.id}")
                                onNavigate()
                            } else showInformation = true
                        },
                )
                if (index != resolved.artists.lastIndex) {
                    Text(
                        text = if (resolved.isLocal || resolved.artistCredit == null ||
                            resolved.artistCredit?.isVideoCredit() == true) ", " else artistNameSeparator(),
                        style = MaterialTheme.typography.titleMedium,
                        color = color,
                    )
                }
            }
        }
    } else {
        Text(
            text = resolved.artistDisplayText(),
            style = MaterialTheme.typography.titleMedium,
            color = color,
            maxLines = 1,
            modifier = Modifier
                .basicMarquee(iterations = 1, initialDelayMillis = 5000)
                .clickable { showInformation = true },
        )
    }
    if (showInformation) {
        ArtistInformationDialog(
            metadata = resolved,
            navController = navController,
            onDismiss = { showInformation = false },
            onNavigate = {
                showInformation = false
                onNavigate()
            },
        )
    }
}
