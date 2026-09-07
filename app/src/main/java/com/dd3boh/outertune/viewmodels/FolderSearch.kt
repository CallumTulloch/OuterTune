package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.db.entities.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

internal data class FolderSearchRequest(val query: String, val immediate: Boolean = false)

data class FolderSearchResult(val query: String = "", val songs: List<Song> = emptyList())

/** Cancel the previous database observation as soon as the text changes, before debouncing. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun folderSearchResults(
    requests: Flow<FolderSearchRequest>,
    search: (String) -> Flow<List<Song>>,
): Flow<FolderSearchResult> = requests.flatMapLatest { request ->
    flow {
        emit(FolderSearchResult(request.query))
        if (request.query.isNotBlank()) {
            if (!request.immediate) delay(300)
            emitAll(search(request.query).map { FolderSearchResult(request.query, it) })
        }
    }
}
