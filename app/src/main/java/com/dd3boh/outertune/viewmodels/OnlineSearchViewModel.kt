package com.dd3boh.outertune.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.ItemsPage
import com.dd3boh.outertune.repositories.ArtistCreditRepository
import com.zionhuang.innertube.models.SongItem
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.SearchResult
import com.zionhuang.innertube.pages.SearchSummaryPage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel internal constructor(
    initialQuery: String,
    private val runtime: Runtime,
) : ViewModel() {
    @Inject
    constructor(savedStateHandle: SavedStateHandle, artistCredits: ArtistCreditRepository) : this(
        initialQuery = savedStateHandle.get<String>("query").orEmpty(),
        runtime = Runtime(
            creditUpdates = artistCredits.updates.map { it.first },
            withCredit = artistCredits::withCredit,
        ),
    )

    /** Search boundaries can be exercised without a live service or Android dispatcher. */
    internal class Runtime(
        val scope: CoroutineScope? = null,
        val searchSummary: suspend (String) -> Result<SearchSummaryPage> = YouTube::searchSummary,
        val search: suspend (String, YouTube.SearchFilter) -> Result<SearchResult> = YouTube::search,
        val searchContinuation: suspend (String) -> Result<SearchResult> = YouTube::searchContinuation,
        val creditUpdates: Flow<String> = emptyFlow(),
        val withCredit: (SongItem) -> SongItem = { it },
        val onFailure: (Throwable) -> Unit = ::reportException,
    )

    private data class Request(val query: String, val revision: Long = 0, val active: Boolean = false)

    private val scope = runtime.scope ?: viewModelScope
    private val request = MutableStateFlow(Request(initialQuery))
    val query: String get() = request.value.query
    val filter = MutableStateFlow<YouTube.SearchFilter?>(null)
    var summaryPage by mutableStateOf<SearchSummaryPage?>(null)
        private set
    val viewStateMap = mutableStateMapOf<String, ItemsPage?>()
    private var loadMoreJob: Job? = null

    fun withArtistCredit(song: SongItem): SongItem = runtime.withCredit(song)

    /** Keep the category, but never reuse results belonging to a previous query. */
    fun submitQuery(query: String, refresh: Boolean = false) {
        val previous = request.value
        if (query == previous.query && !refresh) return
        loadMoreJob?.cancel()
        summaryPage = null
        viewStateMap.clear()
        request.value = previous.copy(query = query, revision = previous.revision + 1)
    }

    fun setSearchActive(active: Boolean) {
        val previous = request.value
        if (active == previous.active) return
        // A rapid local/online round trip must also invalidate in-flight requests, even
        // if StateFlow conflates the intermediate inactive value before collection.
        request.value = previous.copy(active = active, revision = previous.revision + 1)
        if (!active) loadMoreJob?.cancel()
    }

    private fun isCurrent(expected: Request, expectedFilter: YouTube.SearchFilter?): Boolean =
        expected.active && request.value == expected && filter.value == expectedFilter

    init {
        scope.launch {
            runtime.creditUpdates.collect { videoId ->
                summaryPage = summaryPage?.let { page ->
                    page.copy(summaries = page.summaries.map { group ->
                        group.copy(items = group.items.map {
                            if (it is SongItem && it.id == videoId) withArtistCredit(it) else it
                        })
                    })
                }
                viewStateMap.keys.toList().forEach { key ->
                    viewStateMap[key]?.let { page ->
                        viewStateMap[key] = page.copy(items = page.items.map {
                            if (it is SongItem && it.id == videoId) withArtistCredit(it) else it
                        })
                    }
                }
            }
        }
        scope.launch {
            combine(request, filter) { request, filter -> request to filter }
                .collectLatest { (request, filter) ->
                    loadMoreJob?.cancel()
                    if (!request.active || request.query.isBlank()) return@collectLatest
                    if (filter == null) {
                        if (summaryPage == null) {
                            val result = runtime.searchSummary(request.query)
                            // The service wraps cancellation in Result. Check both job and
                            // request identity before publishing a late response.
                            currentCoroutineContext().ensureActive()
                            if (!isCurrent(request, filter)) return@collectLatest
                            result.onSuccess { summaryPage = it }.onFailure(runtime.onFailure)
                        }
                    } else if (viewStateMap[filter.value] == null) {
                        val result = runtime.search(request.query, filter)
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent(request, filter)) return@collectLatest
                        result.onSuccess {
                            viewStateMap[filter.value] = ItemsPage(it.items.distinctBy { item -> item.id }, it.continuation)
                        }.onFailure(runtime.onFailure)
                    }
                }
        }
    }

    fun loadMore() {
        val request = request.value
        val filter = filter.value ?: return
        if (!request.active || loadMoreJob?.isActive == true) return
        val viewState = viewStateMap[filter.value] ?: return
        val continuation = viewState.continuation ?: return
        loadMoreJob = scope.launch {
            val result = runtime.searchContinuation(continuation)
            currentCoroutineContext().ensureActive()
            if (!isCurrent(request, filter)) return@launch
            result.onSuccess {
                viewStateMap[filter.value] = ItemsPage(
                    (viewState.items + it.items).distinctBy { item -> item.id },
                    it.continuation,
                )
            }.onFailure(runtime.onFailure)
        }
    }
}
