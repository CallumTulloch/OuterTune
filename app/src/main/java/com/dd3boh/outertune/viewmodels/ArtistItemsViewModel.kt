package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.ItemsPage
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.BrowseEndpoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class ArtistItemsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val browseId = savedStateHandle.get<String>("browseId")!!
    private val params = savedStateHandle.get<String>("params")

    private val loader = LocalizedPageLoader(viewModelScope,
        initial = { locale ->
            YouTube.artistItems(BrowseEndpoint(browseId = browseId, params = params), requestLocale = locale)
                .map { it.copy(items = it.items.distinctBy { item -> item.id }) }
        },
        continuation = { it.continuation },
        append = { previous, token, locale ->
            YouTube.artistItemsContinuation(token, requestLocale = locale).map { next ->
                previous.copy(items = (previous.items + next.items).distinctBy { it.id }, continuation = next.continuation)
            }
        },
        stopPagination = { it.copy(continuation = null) },
    )
    val title = loader.page.map { it?.title.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val itemsPage = loader.page.map { page -> page?.let { ItemsPage(it.items, it.continuation) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun loadMore() = loader.loadMore()
}
