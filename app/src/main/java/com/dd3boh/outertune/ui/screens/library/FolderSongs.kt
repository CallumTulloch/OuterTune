package com.dd3boh.outertune.ui.screens.library

import androidx.compose.ui.util.fastSumBy
import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.utils.numberToAlpha
import java.time.ZoneOffset

internal fun sortedFolderSongs(songs: List<Song>, sortType: FolderSongSortType, descending: Boolean): List<Song> {
    val sorted = songs.distinctBy { it.id }.sortedBy {
        when (sortType) {
            FolderSongSortType.CREATE_DATE -> numberToAlpha(it.song.inLibrary?.toEpochSecond(ZoneOffset.UTC) ?: -1L)
            FolderSongSortType.MODIFIED_DATE -> numberToAlpha(it.song.getDateModifiedLong() ?: -1L)
            FolderSongSortType.RELEASE_DATE -> numberToAlpha(it.song.getDateLong() ?: -1L)
            FolderSongSortType.NAME -> it.song.title.lowercase()
            FolderSongSortType.ARTIST -> it.artists.joinToString { artist -> artist.name }.lowercase()
            FolderSongSortType.PLAY_COUNT -> numberToAlpha((it.playCount?.fastSumBy { count -> count.count })?.toLong() ?: 0L)
            FolderSongSortType.TRACK_NUMBER -> numberToAlpha(it.song.trackNumber?.toLong() ?: Long.MAX_VALUE)
        }
    }
    return if (descending) sorted.asReversed() else sorted
}

internal fun folderSongQueue(songs: List<Song>, songId: String, title: String): ListQueue? {
    val index = songs.indexOfFirst { it.id == songId }
    if (index < 0) return null
    return ListQueue(title = title, items = songs.map { it.toMediaMetadata() }, startIndex = index)
}
