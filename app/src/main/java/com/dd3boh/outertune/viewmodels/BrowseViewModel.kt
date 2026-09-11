package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.BrowseResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val browseId: String? = savedStateHandle.get<String>("browseId")

    private val loader = LocalizedPageLoader(viewModelScope, initial = { locale ->
        browseId?.let { YouTube.browse(it, null, requestLocale = locale) }
            ?: Result.success(BrowseResult(null, emptyList()))
    })
    val items = loader.page.map { it?.items?.flatMap { section -> section.items } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val title = loader.page.map { it?.title }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
}
