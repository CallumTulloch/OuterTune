package com.dd3boh.outertune.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.repositories.ArtistCreditRepository
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ArtistCreditEntryPoint {
    fun artistCreditRepository(): ArtistCreditRepository
}

@Composable
fun rememberArtistCreditRepository(): ArtistCreditRepository {
    val application = LocalContext.current.applicationContext
    return remember(application) {
        EntryPointAccessors.fromApplication(application, ArtistCreditEntryPoint::class.java)
            .artistCreditRepository()
    }
}

@Composable
fun rememberResolvedArtistSong(song: SongItem, priority: Boolean = false): SongItem {
    val repository = rememberArtistCreditRepository()
    val requestLocale by YouTube.localeUpdates.collectAsState()
    val contextToken = repository.contextToken()
    val credit by remember(repository, requestLocale, contextToken, song.id) { repository.observe(song.id) }.collectAsState()
    val album by remember(repository, requestLocale, contextToken, song.id) { repository.observeAlbum(song.id) }.collectAsState()
    val latestSong by rememberUpdatedState(song)
    // The same row can receive stronger source information without changing its video ID.
    LaunchedEffect(repository, requestLocale, contextToken, song.id, song.artistCredit, song.artists, priority) {
        if (!priority) delay(350)
        repository.request(latestSong, priority)
    }
    return remember(song, credit, album, contextToken) { repository.withCredit(song) }
}

@Composable
fun rememberResolvedArtistMetadata(
    metadata: MediaMetadata,
    request: Boolean = false,
    priority: Boolean = false,
): MediaMetadata {
    if (metadata.isLocal) return metadata
    val repository = rememberArtistCreditRepository()
    val requestLocale by YouTube.localeUpdates.collectAsState()
    val contextToken = repository.contextToken()
    val credit by remember(repository, requestLocale, contextToken, metadata.id) { repository.observe(metadata.id) }.collectAsState()
    val album by remember(repository, requestLocale, contextToken, metadata.id) { repository.observeAlbum(metadata.id) }.collectAsState()
    val latestMetadata by rememberUpdatedState(metadata)
    LaunchedEffect(repository, requestLocale, contextToken, metadata.id, metadata.artistCredit, metadata.artists, request, priority) {
        val sourceLanguage = metadata.artistCredit?.language
        if (request || (!sourceLanguage.isNullOrBlank() && sourceLanguage != requestLocale.hl)) {
            if (!priority) delay(350)
            repository.request(latestMetadata, priority)
        }
    }
    return remember(metadata, credit, album, contextToken) { repository.withCredit(metadata) }
}
