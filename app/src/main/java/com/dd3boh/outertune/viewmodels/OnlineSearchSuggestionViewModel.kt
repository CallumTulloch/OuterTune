package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SearchHistory
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YTItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class OnlineSearchSuggestionViewModel @Inject constructor(
    database: MusicDatabase,
) : ViewModel() {
    val query = MutableStateFlow("")
    private val _viewState = MutableStateFlow(SearchSuggestionViewState())
    val viewState = _viewState.asStateFlow()
    private val searchActive = MutableStateFlow(false)

    fun setSearchActive(active: Boolean, currentQuery: String = query.value) {
        if (active && query.value != currentQuery) {
            _viewState.value = SearchSuggestionViewState(query = currentQuery)
            query.value = currentQuery
        }
        searchActive.value = active
    }

    init {
        viewModelScope.launch {
            searchActive.flatMapLatest { active ->
                if (!active) return@flatMapLatest emptyFlow<SearchSuggestionViewState>()
                query.flatMapLatest { query ->
                    if (query.isEmpty()) {
                        database.searchHistory().map { history ->
                            SearchSuggestionViewState(
                                history = history
                            )
                        }
                    } else {
                        val result = YouTube.searchSuggestions(query).getOrNull()
                        currentCoroutineContext().ensureActive()
                        database.searchHistory(query)
                            .map { it.take(3) }
                            .map { history ->
                                SearchSuggestionViewState(
                                    query = query,
                                    history = history,
                                    suggestions = result?.queries?.filter { query ->
                                        history.none { it.query == query }
                                    }.orEmpty(),
                                    items = result?.recommendedItems.orEmpty().distinctBy { it.id }
                                )
                            }
                    }
                }
            }.collect {
                if (!searchActive.value) return@collect
                _viewState.value = it
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
