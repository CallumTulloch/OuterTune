package com.dd3boh.outertune.ui.dialog

import com.dd3boh.outertune.utils.displayName

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.utils.rememberResolvedArtistMetadata
import com.dd3boh.outertune.utils.artistDisplayText
import com.zionhuang.innertube.models.ArtistCreditStatus

/** This dialog remains useful even when no individual page target is known yet. */
@Composable
fun ArtistInformationDialog(
    metadata: MediaMetadata,
    navController: NavController,
    onDismiss: () -> Unit,
    onNavigate: () -> Unit = onDismiss,
) {
    val resolved = rememberResolvedArtistMetadata(metadata, request = true, priority = true)
    val credit = resolved.artistCredit
    val artists = if (credit == null) resolved.artists else credit.artists.map { artist ->
        MediaMetadata.Artist(id = artist.ref ?: artist.id, name = artist.name, onlineId = artist.id)
    }
    ListDialog(onDismiss = onDismiss) {
        item {
            Text(
                text = stringResource(R.string.artist_information),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                text = credit?.rawText?.takeIf { it.isNotBlank() } ?: resolved.artistDisplayText(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (!resolved.isLocal && (artists.isEmpty() ||
                    credit != null && credit.status != ArtistCreditStatus.COMPLETE)) {
                Text(
                    text = stringResource(when (credit?.status) {
                        ArtistCreditStatus.PARTIAL -> R.string.artist_credit_partial
                        ArtistCreditStatus.CONFLICT -> R.string.artist_credit_conflict
                        else -> R.string.artist_credit_unresolved
                    }),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (credit != null && artists.isNotEmpty()) {
                Text(
                    text = stringResource(
                        if (credit.source.contains("credit", ignoreCase = true) ||
                            credit.evidence.any { it.contains("credit", ignoreCase = true) })
                            R.string.artist_credit_from_credits else R.string.artist_credit_from_song
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
        items(artists) { artist ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !artist.id.isNullOrBlank()) {
                        artist.id?.let { navController.navigate("artist/$it") }
                        onNavigate()
                    }
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    if (resolved.isLocal) artist.name else artist.displayName,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
