package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.ui.screens.search.SearchInputState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** The same navigation entry may move between the phone and tablet composition trees. */
@HiltViewModel
class SearchSessionViewModel @Inject constructor(savedStateHandle: SavedStateHandle) : ViewModel() {
    val inputState = SearchInputState(savedStateHandle.get<String>("query").orEmpty())
}
