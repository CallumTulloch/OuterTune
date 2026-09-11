package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Album
import com.dd3boh.outertune.db.entities.LocalItem
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.SimilarRecommendation
import com.dd3boh.outertune.utils.SyncUtils
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.syncCoroutine
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.PlaylistItem
import com.zionhuang.innertube.models.WatchEndpoint
import com.zionhuang.innertube.models.YTItem
import com.zionhuang.innertube.models.YouTubeLocale
import com.zionhuang.innertube.pages.ExplorePage
import com.zionhuang.innertube.pages.HomePage
import com.zionhuang.innertube.utils.completed
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val syncUtils: SyncUtils
) : ViewModel() {
    val isRefreshing = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)

    val quickPicks = MutableStateFlow<List<Song>?>(null)
    val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
    val keepListening = MutableStateFlow<List<LocalItem>?>(null)
    val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
    val accountPlaylists = MutableStateFlow<List<PlaylistItem>?>(null)
    private var contentLocale = YouTube.locale
    private var refreshJob: Job? = null
    private var refreshGeneration = 0L
    private val feed = HomeFeedLoader(viewModelScope, { token, params ->
        val requestLocale = contentLocale
        withContext(Dispatchers.IO) { YouTube.home(token, params, requestLocale = requestLocale) }
    })
    val homePage = feed.page
    val selectedChip = feed.selectedChip
    val isLoadingHome = feed.loading
    val isLoadingMore = feed.loadingMore
    val homeLoadFailed = feed.failed
    val loadFailed = MutableStateFlow(false)
    val explorePage = MutableStateFlow<ExplorePage?>(null)
    val playlists = database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.NAME, true)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)
    val recentActivity = database.recentActivity()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
    val allYtItems = MutableStateFlow<List<YTItem>>(emptyList())

    private suspend fun load(requestLocale: YouTubeLocale) {
        isLoading.value = true

        quickPicks.value = database.quickPicks()
            .first().shuffled().take(20)

        forgottenFavorites.value = database.forgottenFavorites()
            .first().shuffled().take(20)

        val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2
        val keepListeningSongs = database.mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5)
            .first().shuffled().take(10)
        val keepListeningAlbums = database.mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2)
            .first().filter { it.album.thumbnailUrl != null }.shuffled().take(5)
        val keepListeningArtists = database.mostPlayedArtists(0, 1)
            .first().filter { it.artist.isYouTubeArtist && it.artist.thumbnailUrl != null }.shuffled().take(5)
        keepListening.value = (keepListeningSongs + keepListeningAlbums + keepListeningArtists).shuffled()

        allLocalItems.value =
            (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                .filter { it is Song || it is Album }

        if (YouTube.cookie != null) { // if logged in
            // InnerTune way is YouTube.likedPlaylists().onSuccess { ... }
            // OuterTune uses YouTube.library("FEmusic_liked_playlists").completedL().onSuccess { ... }
            YouTube.library("FEmusic_liked_playlists", requestLocale = requestLocale).completed().onSuccess {
                currentCoroutineContext().ensureActive()
                accountPlaylists.value = it.items.filterIsInstance<PlaylistItem>()
            }.onFailure {
                reportException(it)
            }
        }

        // Similar to artists
        val artistRecommendations =
            database.mostPlayedArtists(0, 1, limit = 10).first()
                .filter { it.artist.isYouTubeArtist }
                .shuffled().take(3)
                .mapNotNull {
                    val items = mutableListOf<YTItem>()
                    val onlineId = it.artist.onlineArtistId ?: return@mapNotNull null
                    YouTube.artist(onlineId, requestLocale = requestLocale).onSuccess { page ->
                        currentCoroutineContext().ensureActive()
                        if (page.artist.id == onlineId) database.awaitTransaction { saveArtistProfile(page.artist) }
                        items += page.sections.getOrNull(page.sections.size - 2)?.items.orEmpty()
                        items += page.sections.lastOrNull()?.items.orEmpty()
                    }
                    SimilarRecommendation(
                        title = it,
                        items = items
                            .shuffled()
                            .ifEmpty { return@mapNotNull null }
                    )
                }
        // Similar to songs
        val songRecommendations =
            database.mostPlayedSongs(fromTimeStamp, limit = 10).first()
                .filter { it.album != null }
                .shuffled().take(2)
                .mapNotNull { song ->
                    val endpoint = YouTube.next(WatchEndpoint(videoId = song.id), requestLocale = requestLocale).getOrNull()?.relatedEndpoint
                        ?: return@mapNotNull null
                    val page = YouTube.related(endpoint, requestLocale = requestLocale).getOrNull() ?: return@mapNotNull null
                    currentCoroutineContext().ensureActive()
                    SimilarRecommendation(
                        title = song,
                        items = (page.songs.shuffled().take(8) +
                                page.albums.shuffled().take(4) +
                                page.artists.shuffled().take(4) +
                                page.playlists.shuffled().take(4))
                            .shuffled()
                            .ifEmpty { return@mapNotNull null }
                    )
                }
        currentCoroutineContext().ensureActive()
        similarRecommendations.value = (artistRecommendations + songRecommendations).shuffled()

        YouTube.explore(requestLocale = requestLocale).onSuccess { page ->
            currentCoroutineContext().ensureActive()
            explorePage.value = page
        }.onFailure {
            reportException(it)
        }

        syncUtils.syncRecentActivity()
        currentCoroutineContext().ensureActive()

        allYtItems.value = similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                homePage.value?.sections?.flatMap { it.items }.orEmpty()

        isLoading.value = false
    }

    fun loadMoreYouTubeItems() = feed.loadMore()
    fun toggleChip(chip: HomePage.Chip?) = feed.toggleChip(chip)
    fun retryHome() = feed.retry()

    fun refresh() {
        if (isRefreshing.value) return
        refresh(contentLocale, languageChanged = false)
    }

    private fun refresh(requestLocale: YouTubeLocale, languageChanged: Boolean) {
        val expected = ++refreshGeneration
        refreshJob?.cancel()
        contentLocale = requestLocale
        if (languageChanged) {
            similarRecommendations.value = null
            accountPlaylists.value = null
            explorePage.value = null
            allYtItems.value = emptyList()
        }
        isRefreshing.value = true
        loadFailed.value = false
        feed.refresh(clearExisting = languageChanged) // Recommendations must not hold up the home feed.
        refreshJob = viewModelScope.launch(syncCoroutine) {
            try {
                withTimeout(60_000) { load(requestLocale) }
            } catch (_: TimeoutCancellationException) {
                if (expected == refreshGeneration) loadFailed.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (expected == refreshGeneration) {
                    loadFailed.value = true
                    reportException(error)
                }
            } finally {
                if (expected == refreshGeneration) {
                    isLoading.value = false
                    isRefreshing.value = false
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            YouTube.localeUpdates.collect { locale -> refresh(locale, languageChanged = locale != contentLocale) }
        }
        viewModelScope.launch {
            homePage.collect { page ->
                allYtItems.value = similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                    page?.sections?.flatMap { it.items }.orEmpty()
            }
        }
        viewModelScope.launch(syncCoroutine) {
            syncUtils.tryAutoSync()
        }
    }
}
