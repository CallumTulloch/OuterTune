package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Artist
import com.dd3boh.outertune.db.entities.LocalArtistLink
import com.dd3boh.outertune.repositories.LocalArtistLinkCandidate
import com.dd3boh.outertune.repositories.LocalArtistLinkException
import com.dd3boh.outertune.repositories.LocalArtistLinkFailure
import com.dd3boh.outertune.repositories.LocalArtistLinkRepository
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.ArtistItem
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@HiltViewModel
class LocalArtistLinkViewModel internal constructor(private val runtime: Runtime) : ViewModel() {
    @Inject
    constructor(database: MusicDatabase, repository: LocalArtistLinkRepository) : this(Runtime(
        links = database::localArtistLink,
        contextToken = repository::contextToken,
        search = repository::findCandidates,
        preview = repository::preview,
        confirm = repository::confirm,
        unlink = repository::unlink,
    ))

    internal class Runtime(
        val links: (String) -> Flow<LocalArtistLink?>,
        val contextToken: () -> String,
        val search: suspend (String) -> List<ArtistItem>,
        val preview: suspend (String) -> LocalArtistLinkCandidate,
        val confirm: suspend (String, LocalArtistLinkCandidate, String?) -> Unit,
        val unlink: suspend (String, String) -> Boolean,
        val contextChanges: Flow<Unit> = combine(YouTube.localeUpdates, YouTube.authUpdates) { _, _ -> Unit },
        val dispatcher: CoroutineDispatcher = Dispatchers.IO,
        val scope: CoroutineScope? = null,
    )

    enum class Busy { NONE, LOADING, SEARCHING, PREVIEWING, SAVING, UNLINKING }
    enum class Failure { LOAD, SEARCH, PREVIEW, SAVE, UNLINK, INVALID_INPUT, INVALID_ARTIST, CONTEXT_CHANGED, LINK_CHANGED }
    data class State(
        val artist: Artist? = null,
        val link: LocalArtistLink? = null,
        val loaded: Boolean = false,
        val query: String = "",
        val candidates: List<ArtistItem> = emptyList(),
        val preview: LocalArtistLinkCandidate? = null,
        val searched: Boolean = false,
        val busy: Busy = Busy.NONE,
        val failure: Failure? = null,
        val confirmUnlink: Boolean = false,
        val completed: Boolean = false,
    ) {
        val saving: Boolean get() = busy == Busy.SAVING || busy == Busy.UNLINKING
    }

    private val scope get() = runtime.scope ?: viewModelScope
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var request: Job? = null
    private var links: Job? = null
    private var contexts: Job? = null
    private var generation = 0L
    private var opening = 0L
    private var observedContext: String? = null
    private var previewRevision: String? = null
    private var unlinkRevision: String? = null

    fun open(artist: Artist) {
        close()
        if (!artist.artist.isLocal) return
        val session = opening
        observedContext = runtime.contextToken()
        mutableState.value = State(artist = artist, query = artist.title, busy = Busy.LOADING)
        links = scope.launch {
            try {
                runtime.links(artist.id).collect { link ->
                    if (session != opening) return@collect
                    val previous = mutableState.value
                    if (previous.loaded && previous.link?.revision != link?.revision && !previous.saving) {
                        invalidate(Failure.LINK_CHANGED)
                    }
                    mutableState.value = mutableState.value.copy(link = link, loaded = true,
                        busy = if (mutableState.value.busy == Busy.LOADING) Busy.NONE else mutableState.value.busy)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (session == opening) mutableState.value = mutableState.value.copy(busy = Busy.NONE, failure = Failure.LOAD)
            }
        }
        contexts = scope.launch {
            runtime.contextChanges.collect {
                if (session == opening && observedContext != runtime.contextToken()) {
                    observedContext = runtime.contextToken()
                    if (mutableState.value.busy != Busy.UNLINKING) invalidate(Failure.CONTEXT_CHANGED)
                }
            }
        }
    }

    fun close() {
        opening++
        generation++
        request?.cancel()
        links?.cancel()
        contexts?.cancel()
        request = null
        mutableState.value = State()
    }

    private fun invalidate(failure: Failure? = null) {
        generation++
        request?.cancel()
        request = null
        mutableState.value = mutableState.value.copy(preview = null, busy = Busy.NONE,
            failure = failure, confirmUnlink = false)
    }

    fun changeQuery(query: String) {
        if (mutableState.value.saving) return
        invalidate()
        mutableState.value = mutableState.value.copy(query = query, candidates = emptyList(), searched = false)
    }

    fun search() {
        val before = mutableState.value
        if (!before.loaded || before.saving || before.query.isBlank()) return
        invalidate()
        mutableState.value = mutableState.value.copy(candidates = emptyList(), searched = false)
        runRequest(Busy.SEARCHING, Failure.SEARCH) {
            val found = runtime.search(before.query.trim())
            return@runRequest { current -> current.copy(candidates = found, searched = true) }
        }
    }

    fun select(artistId: String) {
        val before = mutableState.value
        if (!before.loaded || before.saving || before.candidates.none { it.id == artistId }) return
        invalidate()
        previewRevision = before.link?.revision
        runRequest(Busy.PREVIEWING, Failure.PREVIEW) {
            val candidate = runtime.preview(artistId)
            if (candidate.onlineId != artistId) throw LocalArtistLinkException(LocalArtistLinkFailure.INVALID_ARTIST)
            return@runRequest { current -> current.copy(preview = candidate) }
        }
    }

    fun confirm() {
        val before = mutableState.value
        val candidate = before.preview ?: return
        if (!before.loaded || before.busy != Busy.NONE) return
        if (before.link?.revision != previewRevision) return invalidate(Failure.LINK_CHANGED)
        if (candidate.contextToken != runtime.contextToken()) return invalidate(Failure.CONTEXT_CHANGED)
        val localId = before.artist?.takeIf { it.artist.isLocal }?.id ?: return
        val expected = previewRevision
        runRequest(Busy.SAVING, Failure.SAVE) {
            runtime.confirm(localId, candidate, expected)
            return@runRequest { current -> current.copy(completed = true) }
        }
    }

    fun askUnlink() {
        val before = mutableState.value
        if (!before.loaded || before.saving || before.link == null) return
        invalidate()
        unlinkRevision = before.link.revision
        mutableState.value = mutableState.value.copy(confirmUnlink = true)
    }

    fun cancelUnlink() {
        if (!mutableState.value.saving) mutableState.value = mutableState.value.copy(confirmUnlink = false)
    }

    fun unlink() {
        val before = mutableState.value
        if (!before.confirmUnlink || before.saving) return
        if (before.link?.revision != unlinkRevision) return invalidate(Failure.LINK_CHANGED)
        val localId = before.artist?.takeIf { it.artist.isLocal }?.id ?: return
        val expected = unlinkRevision ?: return
        // Removing a saved link does not depend on the network or current account.
        runRequest(Busy.UNLINKING, Failure.UNLINK, checkContext = false) {
            if (!runtime.unlink(localId, expected)) throw LocalArtistLinkException(LocalArtistLinkFailure.LINK_CHANGED)
            return@runRequest { current -> current.copy(completed = true, confirmUnlink = false) }
        }
    }

    private fun runRequest(
        busy: Busy,
        fallback: Failure,
        checkContext: Boolean = true,
        operation: suspend () -> (State) -> State,
    ) {
        val ticket = ++generation
        val context = runtime.contextToken()
        mutableState.value = mutableState.value.copy(busy = busy, failure = null)
        request = scope.launch {
            try {
                val update = withTimeout(30_000) { withContext(runtime.dispatcher) { operation() } }
                if (ticket != generation) return@launch
                if (checkContext && context != runtime.contextToken()) return@launch invalidate(Failure.CONTEXT_CHANGED)
                mutableState.value = update(mutableState.value).copy(busy = Busy.NONE)
            } catch (cancelled: CancellationException) {
                if (ticket == generation) mutableState.value = mutableState.value.copy(busy = Busy.NONE, failure = fallback)
                throw cancelled
            } catch (error: Exception) {
                if (ticket == generation) {
                    val failure = when ((error as? LocalArtistLinkException)?.reason) {
                        LocalArtistLinkFailure.INVALID_INPUT -> Failure.INVALID_INPUT
                        LocalArtistLinkFailure.INVALID_ARTIST -> Failure.INVALID_ARTIST
                        LocalArtistLinkFailure.STALE_CONTEXT -> Failure.CONTEXT_CHANGED
                        LocalArtistLinkFailure.LINK_CHANGED -> Failure.LINK_CHANGED
                        else -> fallback
                    }
                    val stale = failure == Failure.CONTEXT_CHANGED || failure == Failure.LINK_CHANGED
                    mutableState.value = mutableState.value.copy(busy = Busy.NONE, failure = failure,
                        preview = if (stale) null else mutableState.value.preview,
                        confirmUnlink = !stale && mutableState.value.confirmUnlink)
                }
            }
        }
    }
}
