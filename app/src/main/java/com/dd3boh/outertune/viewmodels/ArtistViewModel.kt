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
import com.zionhuang.innertube.models.YouTubeLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArtistViewModel internal constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
    private val artistCreditRepository: ArtistCreditRepository,
    private val runtime: Runtime,
) : ViewModel() {
    @Inject
    constructor(database: MusicDatabase, savedStateHandle: SavedStateHandle,
                artistCreditRepository: ArtistCreditRepository) :
        this(database, savedStateHandle, artistCreditRepository, Runtime())

    internal class Runtime(
        val fetch: suspend (String, YouTubeLocale) -> Result<ArtistPage> = { id, locale ->
            YouTube.artist(id, requestLocale = locale)
        },
    )
    val artistId = savedStateHandle.get<String>("artistId")!!
    val artistContext = MutableStateFlow(artistCreditRepository.artistContext(artistId).value)
    var artistPage by mutableStateOf<ArtistPage?>(null)
    val libraryArtist = database.artist(artistId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val localArtistLink = database.localArtistLink(artistId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val librarySongs = database.artistSongsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val libraryAlbums = database.artistAlbumsPreview(artistId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val routeOnlineId = artistId.takeIf {
        it.startsWith("UC") || it.startsWith("FEmusic_library_privately_owned_artist")
    }
    val onlineArtistId = combine(libraryArtist, artistContext, localArtistLink) { library, context, link ->
        if (library?.artist?.isLocal == true) link?.onlineArtistId
        else library?.artist?.onlineArtistId ?: context?.onlineId ?: routeOnlineId
    }.stateIn(viewModelScope, SharingStarted.Eagerly, artistContext.value?.onlineId ?: routeOnlineId)

    val initiallyInternal = onlineArtistId.value == null
    val isLoading = MutableStateFlow(false)
    private var fetchJob: Job? = null
    private var fetchedOnlineId: String? = null
    private var fetchedLinkRevision: String? = null
    private var contextJob: Job? = null
    private var observedContextToken: String? = null
    private var fetchGeneration = 0L

    init {
        viewModelScope.launch {
            YouTube.localeUpdates.collect { refreshArtistContext() }
        }
        viewModelScope.launch {
            YouTube.authUpdates.collect { refreshArtistContext() }
        }
        viewModelScope.launch {
            combine(onlineArtistId, localArtistLink) { id, link -> id to link?.revision }
                .distinctUntilChanged().collect {
                fetchGeneration++
                fetchJob?.cancel()
                artistPage = null
                isLoading.value = false
                fetchArtistsFromYTM()
            }
        }
    }

    fun currentContextToken(): String = artistCreditRepository.contextToken()

    fun refreshArtistContext() {
        val token = currentContextToken()
        if (observedContextToken == token) return
        observedContextToken = token
        fetchGeneration++
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
        val local = libraryArtist.value?.artist?.isLocal == true
        val link = localArtistLink.value.takeIf { local }
        val onlineId = if (local) link?.onlineArtistId
            else libraryArtist.value?.artist?.onlineArtistId ?: artistContext.value?.onlineId ?: routeOnlineId
        if (onlineId == null) return
        if (fetchJob?.isActive == true && fetchedOnlineId == onlineId && fetchedLinkRevision == link?.revision) return
        fetchJob?.cancel()
        fetchedOnlineId = onlineId
        fetchedLinkRevision = link?.revision
        val generation = ++fetchGeneration
        val requestLocale = YouTube.locale
        val requestContext = currentContextToken()
        fetchJob = viewModelScope.launch {
            isLoading.value = true
            try {
                val page = runtime.fetch(onlineId, requestLocale).getOrThrow()
                currentCoroutineContext().ensureActive()
                if (generation != fetchGeneration || requestContext != currentContextToken() ||
                    (local && link?.revision != localArtistLink.value?.revision)) return@launch
                if (page.artist.id != onlineId) return@launch
                artistPage = page
                // A manual link only supplies a page; it never rewrites the local artist profile.
                if (!local) database.awaitTransaction {
                    if (generation == fetchGeneration && requestContext == currentContextToken()) saveArtistProfile(page.artist)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (generation == fetchGeneration) reportException(failure)
            } finally {
                if (generation == fetchGeneration) isLoading.value = false
            }
        }
    }
}
