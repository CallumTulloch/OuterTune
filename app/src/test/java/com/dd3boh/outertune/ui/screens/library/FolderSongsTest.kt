package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.constants.FolderSongSortType
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.db.entities.ArtistDisplayMapping
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.models.metadata.OriginalNameKind
import com.dd3boh.outertune.models.metadata.OriginalNameTarget
import com.dd3boh.outertune.utils.ArtistDisplayProjection
import com.dd3boh.outertune.utils.MetadataNames
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderSongsTest {
    @After
    fun clearDisplayOverrides() {
        ArtistDisplayProjection.publish(emptyList())
        MetadataNames.publish(emptyMap())
    }
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

    @Test
    fun `artist sort follows the linked display name and language changes without rewriting tags`() {
        val first = song("first", "First", 1).copy(artists = listOf(ArtistEntity("LA-first", "Alpha file", isLocal = true)))
        val second = song("second", "Second", 2).copy(artists = listOf(ArtistEntity("LA-second", "Middle file", isLocal = true)))
        val source = listOf(first, second)
        val onlineId = "UCabcdefghijklmnopqrstuv"
        assertEquals(listOf("first", "second"), sortedFolderSongs(source, FolderSongSortType.ARTIST, false).map { it.id })
        ArtistDisplayProjection.publish(listOf(ArtistDisplayMapping("LA-first", onlineId, "Zulu linked", null)))
        assertEquals(listOf("second", "first"), sortedFolderSongs(source, FolderSongSortType.ARTIST, false).map { it.id })
        MetadataNames.publish(mapOf(OriginalNameTarget(OriginalNameKind.ARTIST, onlineId) to "Aardvark localized"))
        assertEquals(listOf("first", "second"), sortedFolderSongs(source, FolderSongSortType.ARTIST, false).map { it.id })
        val metadata = folderSongQueue(source, "first", "Folder")!!.items.first()
        assertEquals("Alpha file", metadata.artists.single().name)
        assertEquals("LA-first", metadata.artists.single().id)
        ArtistDisplayProjection.publish(emptyList())
        assertEquals(listOf("first", "second"), sortedFolderSongs(source, FolderSongSortType.ARTIST, false).map { it.id })
    }
}
