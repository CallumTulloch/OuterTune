package com.dd3boh.outertune.ui.screens.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.PreferredSearchSourceKey
import com.dd3boh.outertune.constants.SearchSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SearchScopeState(
    val isReady: Boolean = false,
    val preferredSource: SearchSource = SearchSource.ONLINE,
    val source: SearchSource = SearchSource.ONLINE,
    val sessionOpen: Boolean = false,
    val offlineFallback: Boolean = false,
    val networkAvailable: Boolean = true,
)

internal fun SearchScopeState.withConnection(available: Boolean): SearchScopeState = copy(
    networkAvailable = available,
    source = when {
        !available -> SearchSource.LOCAL
        !sessionOpen -> preferredSource
        else -> source
    },
    offlineFallback = offlineFallback || (sessionOpen && !available && source == SearchSource.ONLINE),
)

internal fun SearchScopeState.openSearch(available: Boolean): SearchScopeState =
    if (sessionOpen) withConnection(available) else copy(
        sessionOpen = true,
        networkAvailable = available,
        source = if (available) preferredSource else SearchSource.LOCAL,
        offlineFallback = !available && preferredSource == SearchSource.ONLINE,
    )

internal fun SearchScopeState.closeSearch(available: Boolean): SearchScopeState = copy(
    sessionOpen = false,
    offlineFallback = false,
    networkAvailable = available,
    source = if (available) preferredSource else SearchSource.LOCAL,
)

internal fun SearchScopeState.selectSearchSource(selected: SearchSource, available: Boolean): SearchScopeState? {
    if (!isReady || (selected == SearchSource.ONLINE && !available)) return null
    return copy(
        preferredSource = selected,
        source = selected,
        sessionOpen = true,
        offlineFallback = false,
        networkAvailable = available,
    )
}

/** One user preference and one current search, independent of the screen that opened it. */
class SharedSearchScope(
    private val preferences: DataStore<Preferences>,
    private val connectivity: StateFlow<Boolean>,
    coroutineScope: CoroutineScope,
) {
    private val lock = Any()
    private val pendingSelections = Channel<SearchSource>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(SearchScopeState(networkAvailable = connectivity.value))
    val state: StateFlow<SearchScopeState> = mutableState.asStateFlow()

    init {
        coroutineScope.launch {
            // Initialize the new key once. Do not import the old route-dependent searchSource key.
            preferences.edit { values ->
                if (values[PreferredSearchSourceKey] == null) values[PreferredSearchSourceKey] = SearchSource.ONLINE.name
            }
            val stored = preferences.data.first()[PreferredSearchSourceKey]
            val preferred = SearchSource.entries.firstOrNull { it.name == stored } ?: SearchSource.ONLINE
            synchronized(lock) {
                val current = mutableState.value
                mutableState.value = current.copy(
                    isReady = true,
                    preferredSource = preferred,
                    source = if (!connectivity.value || current.offlineFallback) SearchSource.LOCAL else preferred,
                    networkAvailable = connectivity.value,
                    offlineFallback = current.sessionOpen && preferred == SearchSource.ONLINE &&
                        (current.offlineFallback || !connectivity.value),
                )
            }
            // A single writer preserves click order, even when preferences take time to persist.
            for (selection in pendingSelections) {
                preferences.edit { it[PreferredSearchSourceKey] = selection.name }
            }
        }
        coroutineScope.launch {
            connectivity.collect { available ->
                synchronized(lock) { mutableState.value = mutableState.value.withConnection(available) }
            }
        }
    }

    fun open() = synchronized(lock) {
        mutableState.value = mutableState.value.openSearch(connectivity.value)
    }

    fun close() = synchronized(lock) {
        mutableState.value = mutableState.value.closeSearch(connectivity.value)
    }

    fun selectSource(source: SearchSource): Boolean = synchronized(lock) {
        val selected = mutableState.value.selectSearchSource(source, connectivity.value) ?: return@synchronized false
        mutableState.value = selected
        pendingSelections.trySend(source).isSuccess
    }

    /** Recheck the live connection when dispatching, including callbacks from outgoing suggestions. */
    fun currentSource(): SearchSource = synchronized(lock) {
        mutableState.value = mutableState.value.withConnection(connectivity.value)
        mutableState.value.source
    }
}
