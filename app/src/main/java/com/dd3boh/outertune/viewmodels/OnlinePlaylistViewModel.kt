package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.PlaylistPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase
) : ViewModel() {
    private val playlistId = savedStateHandle.get<String>("playlistId")!!

    private val loader: LocalizedPageLoader<PlaylistPage> = LocalizedPageLoader(viewModelScope,
        initial = { locale -> withContext(Dispatchers.IO) { YouTube.playlist(playlistId, requestLocale = locale) } },
        continuation = { it.songsContinuation },
        append = { previous, token, locale ->
            withContext(Dispatchers.IO) { YouTube.playlistContinuation(token, requestLocale = locale) }.map { next ->
                previous.copy(songs = previous.songs + next.songs, songsContinuation = next.continuation)
            }
        },
        stopPagination = { it.copy(songsContinuation = null) },
    )
    val playlist = loader.page.map { it?.playlist }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val playlistSongs = loader.page.map { it?.songs.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val continuation: String? get() = loader.page.value?.songsContinuation
    val dbPlaylist = database.playlistByBrowseId(playlistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val isLoading = loader.loading

    fun loadMoreSongs() = loader.loadMore()

    fun loadRemainingSongs() = loader.loadMore(remaining = true)
}
