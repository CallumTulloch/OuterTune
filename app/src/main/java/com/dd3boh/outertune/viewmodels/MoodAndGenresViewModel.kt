package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.pages.MoodAndGenres
import com.zionhuang.innertube.models.YouTubeLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MoodAndGenresViewModel internal constructor(runtime: Runtime) : ViewModel() {
    @Inject constructor() : this(Runtime())

    internal class Runtime(
        val scope: CoroutineScope? = null,
        val locales: StateFlow<YouTubeLocale> = YouTube.localeUpdates,
        val fetch: suspend (YouTubeLocale) -> Result<List<MoodAndGenres>> = { YouTube.moodAndGenres(requestLocale = it) },
        val onFailure: (Throwable) -> Unit = ::reportException,
    )

    private val loader = LocalizedPageLoader(runtime.scope ?: viewModelScope, runtime.locales,
        initial = runtime.fetch, onFailure = runtime.onFailure)
    val moodAndGenres = loader.page
}
