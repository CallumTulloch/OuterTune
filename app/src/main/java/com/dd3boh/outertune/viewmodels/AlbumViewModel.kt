package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.ArtistNavigationTarget
import com.dd3boh.outertune.models.artistNavigationId
import com.dd3boh.outertune.models.mergeArtistNavigationTargets
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.pages.AlbumPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val albumId = savedStateHandle.get<String>("albumId")!!
    val albumWithSongs = database.albumWithSongs(albumId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())

    private val pageArtistNavigationTargets =
        MutableStateFlow<List<ArtistNavigationTarget>>(emptyList())
    private val rawArtistNavigationTargets = combine(
        albumWithSongs,
        pageArtistNavigationTargets,
    ) { album, pageTargets ->
        buildList {
            album?.artists.orEmpty().forEach { artist ->
                artist.artistNavigationId()?.let { browseId ->
                    add(ArtistNavigationTarget(browseId, artist.name))
                }
            }
            addAll(pageTargets)
        }.mergeArtistNavigationTargets()
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _artistNavigationTargets =
        MutableStateFlow<List<ArtistNavigationTarget>>(emptyList())
    internal val artistNavigationTargets = _artistNavigationTargets.asStateFlow()
    private val artistNameCache = mutableMapOf<String, String>()

    internal fun updateArtistNavigationCandidates(albumPage: AlbumPage) {
        pageArtistNavigationTargets.value =
            albumPage.artistNavigationCandidates.map { endpoint ->
                ArtistNavigationTarget(endpoint.browseId)
            }.mergeArtistNavigationTargets()
    }

    val isLoading = MutableStateFlow(true)

    init {
        viewModelScope.launch {
            rawArtistNavigationTargets.collectLatest { targets ->
                // Publish typed destinations immediately. Resolving a human-readable name must
                // never delay or remove the navigation action.
                _artistNavigationTargets.value = targets
                val resolved = supervisorScope {
                    targets.map { target ->
                        async {
                            if (target.name != null) return@async target
                            val name = artistNameCache[target.browseId]
                                ?: YouTube.artist(target.browseId)
                                    .getOrNull()
                                    ?.artist
                                    ?.title
                                    ?.takeIf(String::isNotBlank)
                                    ?.also { artistNameCache[target.browseId] = it }
                            target.copy(name = name)
                        }
                    }.awaitAll()
                }
                if (rawArtistNavigationTargets.value.map { it.browseId } ==
                    resolved.map { it.browseId }
                ) {
                    _artistNavigationTargets.value = resolved
                }
            }
        }

        viewModelScope.launch {
            isLoading.value = true
            val album = database.album(albumId).first()
            if (album?.album?.isLocal == true) return@launch
            YouTube.album(albumId).onSuccess {
                updateArtistNavigationCandidates(it)
                database.transaction {
                    if (album == null) insert(it)
                    else update(album.album, it)
                }
                otherVersions.value = it.otherVersions
                isLoading.value = false
            }.onFailure {
                isLoading.value = false
                reportException(it)
                if (it.message?.contains("NOT_FOUND") == true) {
                    // This album no longer exists in YouTube Music
                    database.query {
                        album?.album?.let(::delete)
                    }
                }
            }
        }
    }
}
