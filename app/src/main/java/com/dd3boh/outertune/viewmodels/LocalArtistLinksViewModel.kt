package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.LocalArtistLinkSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

@HiltViewModel
class LocalArtistLinksViewModel internal constructor(private val runtime: Runtime) : ViewModel() {
    @Inject
    constructor(database: MusicDatabase) : this(Runtime(database::localArtistLinkSources))

    internal class Runtime(
        val sources: (String?) -> Flow<List<LocalArtistLinkSource>>,
        val dispatcher: CoroutineDispatcher = Dispatchers.IO,
        val scope: CoroutineScope? = null,
    )

    data class State(
        val sources: List<LocalArtistLinkSource> = emptyList(),
        val loading: Boolean = false,
        val failed: Boolean = false,
        val editing: Artist? = null,
    )

    private val scope get() = runtime.scope ?: viewModelScope
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null
    private var generation = 0L
    private var onlineArtistId: String? = null

    fun open(onlineArtistId: String?) {
        close()
        this.onlineArtistId = onlineArtistId
        val ticket = generation
        mutableState.value = State(loading = true)
        observation = scope.launch {
            try {
                runtime.sources(onlineArtistId).flowOn(runtime.dispatcher).collect { sources ->
                    if (ticket != generation) return@collect
                    // Keep the child editor's original source until it closes, even if its own
                    // successful unlink removes that row from this live list first.
                    mutableState.value = mutableState.value.copy(sources = sources, loading = false, failed = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (ticket == generation) mutableState.value = mutableState.value.copy(loading = false, failed = true)
            }
        }
    }

    fun retry() {
        if (mutableState.value.editing == null) open(onlineArtistId)
    }

    fun edit(localArtistId: String) {
        val current = mutableState.value
        if (current.loading || current.failed || current.editing != null) return
        val source = current.sources.firstOrNull { it.localArtist.id == localArtistId } ?: return
        val artist = source.localArtist
        if (!artist.artist.isLocal || artist.localLink == null) return
        mutableState.value = current.copy(editing = artist)
    }

    fun finishEditing() {
        mutableState.value = mutableState.value.copy(editing = null)
    }

    fun close() {
        generation++
        observation?.cancel()
        observation = null
        mutableState.value = State()
    }
}
