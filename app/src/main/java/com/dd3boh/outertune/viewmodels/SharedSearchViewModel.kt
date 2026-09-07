package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.ui.screens.search.SharedSearchScope
import com.dd3boh.outertune.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SharedSearchViewModel @Inject constructor(@ApplicationContext context: Context) : ViewModel() {
    private val connectivity = MutableStateFlow(false)
    val searchScope = SharedSearchScope(context.dataStore, connectivity, viewModelScope)
    private var observedConnection: StateFlow<Boolean>? = null
    private var connectionJob: Job? = null

    /** Reuse the Activity's sole network observer, retaining the search through configuration changes. */
    fun bindConnectivity(status: StateFlow<Boolean>) {
        if (observedConnection === status) return
        connectionJob?.cancel()
        observedConnection = status
        connectivity.value = status.value
        connectionJob = viewModelScope.launch { status.collect { connectivity.value = it } }
    }

    fun unbindConnectivity(status: StateFlow<Boolean>) {
        if (observedConnection !== status) return
        connectionJob?.cancel()
        connectionJob = null
        observedConnection = null
    }
}
