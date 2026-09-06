package com.dd3boh.outertune.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.repositories.ArtistCreditRepository
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.ArtistPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel @Inject constructor(
    database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
    private val artistCreditRepository: ArtistCreditRepository,
) : ViewModel() {
    val artistId = savedStateHandle.get<String>("artistId")!!
    val artistContext = MutableStateFlow(artistCreditRepository.artistContext(artistId).value)
    var artistPage by mutableStateOf<ArtistPage?>(null)
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val librarySongs = database.artistSongsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = database.artistAlbumsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val routeOnlineId = artistId.takeIf {
        it.startsWith("UC") || it.startsWith("FEmusic_library_privately_owned_artist")
    }
    val onlineArtistId = combine(libraryArtist, artistContext) { library, context ->
        library?.artist?.onlineArtistId ?: context?.onlineId ?: routeOnlineId
    }.stateIn(viewModelScope, SharingStarted.Eagerly, artistContext.value?.onlineId ?: routeOnlineId)

    val initiallyInternal = onlineArtistId.value == null
    val isLoading = MutableStateFlow(false)
    private var fetchJob: Job? = null
    private var fetchedOnlineId: String? = null
    private var contextJob: Job? = null
    private var observedContextToken: String? = null

    init {
        refreshArtistContext()
        viewModelScope.launch {
            onlineArtistId.filterNotNull().distinctUntilChanged().collect {
                fetchArtistsFromYTM()
            }
        }
    }

    fun currentContextToken(): String = artistCreditRepository.contextToken()

    fun refreshArtistContext() {
        val token = currentContextToken()
        if (observedContextToken == token) return
        observedContextToken = token
        contextJob?.cancel()
        fetchJob?.cancel()
        artistPage = null
        isLoading.value = false
        val source = artistCreditRepository.artistContext(artistId)
        artistContext.value = source.value
        contextJob = viewModelScope.launch { source.collect { artistContext.value = it } }
        fetchArtistsFromYTM()
    }

    fun fetchArtistsFromYTM() {
        val onlineId = libraryArtist.value?.artist?.onlineArtistId ?: artistContext.value?.onlineId ?: routeOnlineId ?: return
        if (fetchJob?.isActive == true && fetchedOnlineId == onlineId) return
        fetchJob?.cancel()
        fetchedOnlineId = onlineId
        fetchJob = viewModelScope.launch {
            isLoading.value = true
            YouTube.artist(onlineId)
                .onSuccess {
                    artistPage = it
                }.onFailure {
                    reportException(it)
                }

            isLoading.value = false
        }
    }
}
