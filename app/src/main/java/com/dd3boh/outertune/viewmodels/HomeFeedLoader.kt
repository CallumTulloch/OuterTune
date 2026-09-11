package com.dd3boh.outertune.viewmodels

import com.zionhuang.innertube.pages.HomePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Owns one home request generation. Call entry points from the UI dispatcher. */
internal class HomeFeedLoader(
    private val scope: CoroutineScope,
    private val fetch: suspend (continuation: String?, params: String?) -> Result<HomePage>,
    private val timeoutMillis: Long = 20_000,
) {
    val page = MutableStateFlow<HomePage?>(null)
    val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
    val loading = MutableStateFlow(false)
    val loadingMore = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    private var generation = 0L
    private var request: Job? = null
    private var previousPage: HomePage? = null
    private val consumedTokens = mutableSetOf<String>()
    private var retryInitial = true

    fun refresh(clearExisting: Boolean = false) {
        selectedChip.value = null
        previousPage = null
        if (clearExisting) page.value = null
        startInitial(null)
    }

    fun toggleChip(chip: HomePage.Chip?) {
        if (chip == null || chip == selectedChip.value) {
            invalidate()
            selectedChip.value = null
            val previous = previousPage
            previousPage = null
            if (previous != null) page.value = previous else startInitial(null)
        } else {
            if (selectedChip.value == null) previousPage = page.value
            selectedChip.value = chip
            startInitial(chip.endpoint?.params)
        }
    }

    private fun invalidate(): Long {
        generation++
        request?.cancel()
        consumedTokens.clear()
        loading.value = false
        loadingMore.value = false
        failed.value = false
        return generation
    }

    private suspend fun fetchPage(token: String?, params: String?) = withTimeout(timeoutMillis) {
        val result = fetch(token, params)
        currentCoroutineContext().ensureActive() // The service may wrap cancellation in Result.
        result.getOrThrow()
    }

    private fun startInitial(params: String?) {
        val expected = invalidate()
        retryInitial = true
        loading.value = true
        request = scope.launch {
            try {
                val result = fetchPage(null, params)
                currentCoroutineContext().ensureActive()
                if (expected == generation) page.value = result.copy(chips = result.chips ?: page.value?.chips)
            } catch (_: TimeoutCancellationException) {
                if (expected == generation) failed.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expected == generation) failed.value = true
            } finally {
                if (expected == generation) loading.value = false
            }
        }
    }

    fun retry() {
        if (loading.value || loadingMore.value) return
        if (retryInitial) startInitial(selectedChip.value?.endpoint?.params)
        else {
            failed.value = false
            loadMore()
        }
    }

    fun loadMore() {
        if (loading.value || loadingMore.value || failed.value) return
        val initialToken = page.value?.continuation ?: return
        val expected = generation
        retryInitial = false
        loadingMore.value = true // Set before launching, so rapid scroll events cannot double-submit.
        request = scope.launch {
            try {
                var token = initialToken
                // Skip a few unsupported/duplicate shelves, then require a deliberate retry.
                repeat(3) {
                    if (token in consumedTokens) {
                        page.value = page.value?.copy(continuation = null)
                        return@launch
                    }
                    val result = fetchPage(token, null)
                    currentCoroutineContext().ensureActive()
                    if (expected != generation) return@launch
                    consumedTokens += token
                    val old = page.value ?: return@launch
                    val known = old.sections.mapTo(mutableSetOf()) { it.identity() }
                    val added = result.sections.filter { known.add(it.identity()) }
                    val next = result.continuation?.takeUnless { it in consumedTokens }
                    page.value = old.copy(sections = old.sections + added, continuation = next)
                    if (added.isNotEmpty() || next == null) return@launch
                    token = next
                }
                failed.value = true
            } catch (_: TimeoutCancellationException) {
                if (expected == generation) failed.value = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (expected == generation) failed.value = true
            } finally {
                if (expected == generation) loadingMore.value = false
            }
        }
    }

    private fun HomePage.Section.identity() = listOf(title, label, endpoint?.browseId,
        items.map { it.javaClass.simpleName to it.id })
}
