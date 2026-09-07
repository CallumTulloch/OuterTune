package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderSongsTest {
    private fun song(id: String, title: String, track: Int) = Song(
        SongEntity(id, title, isLocal = true, localPath = "/storage/emulated/0/Music/child/$id.wav", trackNumber = track),
        emptyList(),
    )

    @Test
    fun `nested search result plays the selected id in displayed order`() {
        val first = song("a", "Alpha", 2)
        val second = song("z", "Zulu", 1)
        val visible = sortedFolderSongs(listOf(second, first, second), FolderSongSortType.NAME, false)
        val queue = folderSongQueue(visible, second.id, "Search")!!
        assertEquals(listOf("a", "z"), queue.items.map { it.id })
        assertEquals(1, queue.startIndex)
        assertEquals(second.song.localPath, queue.items[queue.startIndex].localPath)
    }

    @Test
    fun `search results follow track sort and descending direction`() {
        val songs = listOf(song("a", "Alpha", 2), song("z", "Zulu", 1))
        assertEquals(listOf("z", "a"), sortedFolderSongs(songs, FolderSongSortType.TRACK_NUMBER, false).map { it.id })
        assertEquals(listOf("a", "z"), sortedFolderSongs(songs, FolderSongSortType.TRACK_NUMBER, true).map { it.id })
    }

    @Test
    fun `disappeared selection never starts an unrelated song or an empty queue`() {
        assertNull(folderSongQueue(emptyList(), "gone", "Search"))
        assertNull(folderSongQueue(listOf(song("a", "Alpha", 1)), "gone", "Search"))
    }
}
