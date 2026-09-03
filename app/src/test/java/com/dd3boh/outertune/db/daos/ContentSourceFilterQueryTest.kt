package com.dd3boh.outertune.db.daos

import com.dd3boh.outertune.constants.AlbumFilter
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.LibraryContentFilter
import com.dd3boh.outertune.constants.PlaylistFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSourceFilterQueryTest {
    @Test
    fun `downloaded content excludes folder songs`() {
        assertTrue(albumContentCondition(AlbumFilter.DOWNLOADED).contains("song.isLocal = 0"))
        assertTrue(artistContentCondition(ArtistFilter.DOWNLOADED).contains("song.isLocal = 0"))
        assertTrue(playlistContentHaving(PlaylistFilter.DOWNLOADED).contains("s.isLocal = 0"))
    }

    @Test
    fun `folder album and artist filters use song source`() {
        assertTrue(albumContentCondition(AlbumFilter.FOLDER).contains("song.isLocal = 1"))
        assertTrue(artistContentCondition(ArtistFilter.FOLDER).contains("song.isLocal = 1"))
    }

    @Test
    fun `folder playlist filter uses member songs not playlist ownership`() {
        val having = playlistContentHaving(PlaylistFilter.FOLDER)

        assertTrue(having.contains("s.isLocal = 1"))
        assertFalse(having.contains("p.isLocal"))
    }

    @Test
    fun `library selection keeps inclusive playlist behavior`() {
        assertEquals(
            "",
            libraryPlaylistContentHaving(
                setOf(LibraryContentFilter.LIBRARY, LibraryContentFilter.FOLDER),
            ),
        )
    }

    @Test
    fun `empty secondary selection matches no content`() {
        assertEquals("0", libraryAlbumContentCondition(emptySet()))
        assertEquals("0", libraryArtistContentCondition(emptySet()))
        assertEquals("HAVING 0", libraryPlaylistContentHaving(emptySet()))
    }
}
