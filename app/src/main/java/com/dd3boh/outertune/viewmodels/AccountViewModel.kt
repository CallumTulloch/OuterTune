package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.ArtistItem
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.LibraryPage
import com.zionhuang.innertube.utils.completed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AccountViewModel internal constructor(private val runtime: Runtime) : ViewModel() {
    @Inject constructor() : this(Runtime())

    internal class Runtime(
        val scope: CoroutineScope? = null,
        val locales: StateFlow<YouTubeLocale> = YouTube.localeUpdates,
        val library: suspend (String, YouTubeLocale) -> Result<LibraryPage> = { id, locale ->
            YouTube.library(id, requestLocale = locale).completed()
        },
        val onFailure: (Throwable) -> Unit = ::reportException,
    )

    private val scope = runtime.scope ?: viewModelScope
    private fun loader(id: String) = LocalizedPageLoader(scope, runtime.locales,
        initial = { locale -> runtime.library(id, locale) }, onFailure = runtime.onFailure)
    private val playlistLoader = loader("FEmusic_liked_playlists")
    private val albumLoader = loader("FEmusic_liked_albums")
    private val artistLoader = loader("FEmusic_library_corpus_artists")
    val playlists = playlistLoader.page.map { it?.items?.filterIsInstance<PlaylistItem>() }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val albums = albumLoader.page.map { it?.items?.filterIsInstance<AlbumItem>() }
        .stateIn(scope, SharingStarted.Eagerly, null)
    val artists = artistLoader.page.map { it?.items?.filterIsInstance<ArtistItem>() }
        .stateIn(scope, SharingStarted.Eagerly, null)
    // Existing UI treats this as the number of completed categories, including failed ones.
    val isLoading = combine(playlistLoader.loading, albumLoader.loading, artistLoader.loading) { playlist, album, artist ->
        listOf(playlist, album, artist).count { !it }
    }.stateIn(scope, SharingStarted.Eagerly, 0)
}
