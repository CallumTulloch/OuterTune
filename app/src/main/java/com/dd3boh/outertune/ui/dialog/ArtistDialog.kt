package com.dd3boh.outertune.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.constants.ListItemHeight
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.artistNavigationId

@JvmName("ArtistDialogMediaMetadataArtist")
@Composable
fun ArtistDialog(
    navController: NavController,
    artists: List<MediaMetadata.Artist>,
    allowLocalArtistIds: Boolean = false,
    onDismiss: () -> Unit,
) {
    val navigableArtists = remember(artists, allowLocalArtistIds) {
        artists.mapNotNull { artist ->
            artist.id.artistNavigationId(isLocal = allowLocalArtistIds)?.let { artistId ->
                artist to artistId
            }
        }
    }

    ListDialog(
        onDismiss = onDismiss
    ) {
        items(navigableArtists) { (artist, artistId) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(ListItemHeight)
                    .clickable {
                        navController.navigate("artist/$artistId")
                        onDismiss()
                    }
                    .padding(horizontal = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillParentMaxWidth()
                        .height(ListItemHeight)
                        .clickable {
                            navController.navigate("artist/$artistId")
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = artist.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@JvmName("ArtistDialogArtistEntity")
@Composable
fun ArtistDialog(
    navController: NavController,
    artists: List<ArtistEntity>,
    onDismiss: () -> Unit,
) {
    val navigableArtists = remember(artists) {
        artists.mapNotNull { artist ->
            artist.artistNavigationId()?.let { artistId ->
                artist to artistId
            }
        }
    }

    ListDialog(
        onDismiss = onDismiss
    ) {
        items(
            items = navigableArtists,
            key = { (_, artistId) -> artistId }
        ) { (artist, artistId) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(ListItemHeight)
                    .clickable {
                        navController.navigate("artist/$artistId")
                        onDismiss()
                    }
                    .padding(horizontal = 12.dp),
            ) {
                Box(
                    modifier = Modifier.padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = artist.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(ListThumbnailSize)
                            .clip(CircleShape)
                    )
                }
                Text(
                    text = artist.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
            }
        }
    }
}
