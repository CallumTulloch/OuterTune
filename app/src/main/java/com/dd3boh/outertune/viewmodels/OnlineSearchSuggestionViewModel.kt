package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SearchHistory
import com.dd3boh.outertune.repositories.BilingualSearch
import com.dd3boh.outertune.repositories.searchIdentity
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.SearchSuggestions
import com.zionhuang.innertube.models.YTItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlineSearchSuggestionViewModel internal constructor(private val runtime: Runtime) : ViewModel() {
    @Inject
    constructor(database: MusicDatabase, bilingualSearch: BilingualSearch) : this(Runtime(
        history = { query -> if (query.isEmpty()) database.searchHistory() else database.searchHistory(query) },
        searchSuggestions = bilingualSearch::searchSuggestions,
        configurationChanges = bilingualSearch.configurationChanges,
    ))

    internal class Runtime(
        val scope: CoroutineScope? = null,
        val history: (String) -> Flow<List<SearchHistory>>,
        val searchSuggestions: suspend (String) -> Result<SearchSuggestions> = { YouTube.searchSuggestions(it) },
        val configurationChanges: Flow<Unit> = emptyFlow(),
    )

    private data class Activation(val active: Boolean = false, val revision: Long = 0)
    private data class Request(val query: String, val activation: Activation)
    private val scope = runtime.scope ?: viewModelScope
    val query = MutableStateFlow("")
    private val _viewState = MutableStateFlow(SearchSuggestionViewState())
    val viewState = _viewState.asStateFlow()
    private val activation = MutableStateFlow(Activation())

    fun setSearchActive(active: Boolean, currentQuery: String = query.value) {
        if (active && query.value != currentQuery) {
            _viewState.value = SearchSuggestionViewState(query = currentQuery)
            query.value = currentQuery
        }
        val previous = activation.value
        if (previous.active != active) activation.value = Activation(active, previous.revision + 1)
    }

    init {
        scope.launch {
            runtime.configurationChanges.collect {
                _viewState.value = SearchSuggestionViewState(query = query.value)
                activation.value = activation.value.copy(revision = activation.value.revision + 1)
            }
        }
        scope.launch {
            combine(query, activation) { query, activation -> Request(query, activation) }
                .flatMapLatest { request ->
                    if (!request.activation.active) return@flatMapLatest emptyFlow<Pair<Request, SearchSuggestionViewState>>()
                    val query = request.query
                    if (query.isEmpty()) {
                        runtime.history(query).map { history ->
                            request to SearchSuggestionViewState(
                                history = history
                            )
                        }
                    } else {
                        val result = runtime.searchSuggestions(query).getOrNull()
                        currentCoroutineContext().ensureActive()
                        runtime.history(query)
                            .map { it.take(3) }
                            .map { history ->
                                request to SearchSuggestionViewState(
                                    query = query,
                                    history = history,
                                    suggestions = result?.queries?.filter { query ->
                                        history.none { it.query == query }
                                    }.orEmpty(),
                                    items = result?.recommendedItems.orEmpty().distinctBy { it.searchIdentity() }
                                )
                            }
                    }
                }.collect { (request, state) ->
                if (!activation.value.active || request.activation != activation.value || request.query != query.value) return@collect
                _viewState.value = state
            }
        }
    }
}

data class SearchSuggestionViewState(
    val query: String = "",
    val history: List<SearchHistory> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val items: List<YTItem> = emptyList(),
)
