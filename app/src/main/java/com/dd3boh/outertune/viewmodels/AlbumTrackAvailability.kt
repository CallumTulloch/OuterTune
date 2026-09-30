package com.dd3boh.outertune.viewmodels

import com.zionhuang.innertube.models.SongItem

/** Refresh restrictions for exact cached identities without transferring them between recordings. */
internal fun refreshCachedAlbumAvailability(
    cachedSongIds: Set<String>,
    previousUnavailable: Set<String>,
    incomingSongs: List<SongItem>,
): Set<String> = previousUnavailable.intersect(cachedSongIds).toMutableSet().apply {
    incomingSongs.forEach { song ->
        if (song.id in cachedSongIds) {
            if (song.isPlayable) remove(song.id) else add(song.id)
        }
    }
}
