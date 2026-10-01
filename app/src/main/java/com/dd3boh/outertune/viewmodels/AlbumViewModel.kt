package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.repositories.ArtistCreditRepository
import com.dd3boh.outertune.repositories.MetadataNameRepository
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.YouTubeLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import javax.inject.Inject

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val database: MusicDatabase,
    savedStateHandle: SavedStateHandle,
    private val artistCredits: ArtistCreditRepository,
    private val metadataNames: MetadataNameRepository,
) : ViewModel() {
    val albumId = savedStateHandle.get<String>("albumId")!!
    val albumWithSongs = database.albumWithSongs(albumId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val otherVersions = MutableStateFlow<List<AlbumItem>>(emptyList())

    val isLoading = MutableStateFlow(false)
    val loadFailed = MutableStateFlow(false)
    val unavailableSongIds = MutableStateFlow<Set<String>>(emptySet())

    private var fetchJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch { YouTube.localeUpdates.collect { load(it) } }
    }

    fun setForeground(active: Boolean) {
        metadataNames.setForegroundAlbum(albumId, active)
    }

    fun retry() {
        if (isLoading.value) return
        load(YouTube.locale)
    }

    private fun load(requestLocale: YouTubeLocale) {
        val expected = ++generation
        fetchJob?.cancel()
        isLoading.value = true
        loadFailed.value = false
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(30_000) {
                    val album = database.album(albumId).first()
                    if (album?.album?.isLocal == true) return@withTimeout
                    val response = YouTube.album(albumId, requestLocale = requestLocale).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (expected != generation || requestLocale != YouTube.locale) return@withTimeout
                    if (response.songs.isEmpty()) {
                        // No DB track-list update will arrive for an empty page.
                        loadFailed.value = true
                        return@withTimeout
                    }
                    if (response.hasUnresolvedTrackSources && database.albumById(albumId)?.hasTrackList == true) {
                        // A cold screen still needs restrictions for the unchanged cached IDs.
                        // Availability on an unmatched MV says nothing about a cached audio ID.
                        val cachedIds = database.albumSongs(albumId).first().mapTo(mutableSetOf()) { it.id }
                        currentCoroutineContext().ensureActive()
                        if (expected != generation || requestLocale != YouTube.locale) return@withTimeout
                        unavailableSongIds.value = refreshCachedAlbumAvailability(
                            cachedIds, unavailableSongIds.value, response.songs,
                        )
                        loadFailed.value = true
                        return@withTimeout
                    }
                    val page = response.copy(songs = response.songs.map(artistCredits::withCredit))
                    database.awaitTransaction {
                        if (expected != generation || requestLocale != YouTube.locale) return@awaitTransaction
                        if (album == null) insert(page) else update(album.album, page)
                    }
                    currentCoroutineContext().ensureActive()
                    if (expected != generation || requestLocale != YouTube.locale) return@withTimeout
                    otherVersions.value = response.otherVersions
                    unavailableSongIds.value = response.songs.filterNot { it.isPlayable }.mapTo(mutableSetOf()) { it.id }
                }
            } catch (_: TimeoutCancellationException) {
                if (expected == generation) loadFailed.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (expected == generation) {
                    loadFailed.value = true
                    reportException(error)
                }
            } finally {
                if (expected == generation) isLoading.value = false
            }
        }
    }
}
