package com.dd3boh.outertune.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.models.ArtistIdentity
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
        val authRevision: () -> Long = { YouTube.authRevision },
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

    private val routeOnlineId = ArtistIdentity.onlineId(artistId)
    val onlineArtistId = combine(libraryArtist, artistContext) { library, context ->
        // The display row resolves legacy source routes and canonical online routes alike.
        // Local rows and provisional album groups must not fall back to an old network context.
        if (library != null) library.artist.onlineArtistId
        else ArtistIdentity.onlineId(context?.onlineId) ?: routeOnlineId
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        ArtistIdentity.onlineId(artistContext.value?.onlineId) ?: routeOnlineId)

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

    fun currentContextToken(): String = "${artistCreditRepository.contextToken()}:${runtime.authRevision()}"

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
        val library = libraryArtist.value
        val link = localArtistLink.value
        val onlineId = if (library != null) library.artist.onlineArtistId
            else ArtistIdentity.onlineId(artistContext.value?.onlineId) ?: routeOnlineId
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
                    link?.revision != localArtistLink.value?.revision) return@launch
                if (page.artist.id != onlineId) return@launch
                artistPage = page
                // Update existing online profile fields only; source tags and bookmarks stay intact.
                database.awaitTransaction {
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
