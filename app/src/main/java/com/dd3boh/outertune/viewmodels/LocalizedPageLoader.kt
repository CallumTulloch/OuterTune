package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A continuation belongs to the locale and request generation that produced its first page. */
internal class LocalizedPageLoader<T>(
    private val scope: CoroutineScope,
    private val locales: StateFlow<YouTubeLocale> = YouTube.localeUpdates,
    private val initial: suspend (YouTubeLocale) -> Result<T>,
    private val continuation: (T) -> String? = { null },
    private val append: (suspend (T, String, YouTubeLocale) -> Result<T>)? = null,
    private val stopPagination: (T) -> T = { it },
    private val onFailure: (Throwable) -> Unit = ::reportException,
) {
    val page = MutableStateFlow<T?>(null)
    val loading = MutableStateFlow(false)
    private var generation = 0L
    private var pageLocale: YouTubeLocale? = null
    private var continuationJob: Job? = null
    private val consumedTokens = mutableSetOf<String>()
    private val refreshes = MutableStateFlow(0L)

    init {
        scope.launch {
            combine(locales, refreshes) { locale, revision -> locale to revision }.collectLatest { (locale, _) ->
                val expected = ++generation
                continuationJob?.cancel()
                consumedTokens.clear()
                pageLocale = null
                page.value = null
                loading.value = true
                try {
                    val result = initial(locale).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (expected == generation && locales.value == locale) {
                        pageLocale = locale
                        page.value = result
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (expected == generation && locales.value == locale) onFailure(failure)
                } finally {
                    if (expected == generation) loading.value = false
                }
            }
        }
    }

    fun refresh() {
        // Invalidate completed responses immediately, before the collector resumes.
        generation++
        continuationJob?.cancel()
        pageLocale = null
        refreshes.value += 1
    }

    fun loadMore(remaining: Boolean = false) {
        val fetch = append ?: return
        val locale = pageLocale ?: return
        val current = page.value ?: return
        if (loading.value || continuationJob?.isActive == true || locale != locales.value || continuation(current) == null) return
        val expected = generation
        // Claim the request before dispatch; repeated scroll callbacks share one request.
        loading.value = true
        continuationJob = scope.launch {
            try {
                do {
                    if (expected != generation || locale != locales.value) return@launch
                    val previous = page.value ?: return@launch
                    val token = continuation(previous) ?: return@launch
                    if (token in consumedTokens) {
                        page.value = stopPagination(previous)
                        return@launch
                    }
                    val updated = fetch(previous, token, locale).getOrThrow()
                    currentCoroutineContext().ensureActive()
                    if (expected != generation || locale != locales.value || page.value !== previous) return@launch
                    consumedTokens += token
                    page.value = if (continuation(updated)?.let(consumedTokens::contains) == true) stopPagination(updated) else updated
                } while (remaining)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // Leave the failed token available for an explicit retry; never loop on failure.
                if (expected == generation && locale == locales.value) onFailure(failure)
            } finally {
                if (expected == generation) loading.value = false
            }
        }
    }
}
