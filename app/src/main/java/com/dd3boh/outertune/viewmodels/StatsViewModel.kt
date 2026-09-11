package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.utils.reportException
import com.zionhuang.innertube.YouTube
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// redoing this whole feature later, plz ignore the slop code
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    val database: MusicDatabase,
) : ViewModel() {
    val statPeriod = MutableStateFlow(StatPeriod.`1_WEEK`)

    val mostPlayedSongs = statPeriod.flatMapLatest { period ->
        database.mostPlayedSongs(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mostPlayedArtists = statPeriod.flatMapLatest { period ->
        val time = period.toLocalDateTime()
        database.mostPlayedArtists(time.year, time.month.value).map { artists ->
            artists.filter { it.artist.isYouTubeArtist }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())


    val mostPlayedAlbums = statPeriod.flatMapLatest { period ->
        database.mostPlayedAlbums(period.toTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        // Saved artist images are repaired independently of this screen's subscriptions.
        // fetch missing album metadata
        viewModelScope.launch {
            combine(mostPlayedAlbums, YouTube.localeUpdates) { albums, locale -> albums to locale }
                .collectLatest { (albums, requestLocale) ->
                    albums.filter {
                        it.album.songCount == 0
                    }.forEach { album ->
                        val response = YouTube.album(album.id, requestLocale = requestLocale)
                        currentCoroutineContext().ensureActive()
                        if (YouTube.locale != requestLocale) return@forEach
                        response.onSuccess { albumPage ->
                            database.awaitTransaction {
                                if (YouTube.locale != requestLocale) return@awaitTransaction
                                update(album.album, albumPage)
                            }
                        }.onFailure {
                            if (it is CancellationException) throw it
                            reportException(it)
                            if (it.message?.contains("NOT_FOUND") == true) {
                                database.awaitTransaction {
                                    if (YouTube.locale != requestLocale) return@awaitTransaction
                                    delete(album.album)
                                }
                            }
                        }
                    }
                }
        }
    }
}
