package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.constants.SongFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySongsScreenTest {
    @Test
    fun `selecting the active visible filter clears the selection`() {
        listOf(SongFilter.LIKED, SongFilter.LIBRARY, SongFilter.DOWNLOADED).forEach { filter ->
            assertEquals(SongFilter.ALL, nextSongFilter(filter, filter))
        }
    }

    @Test
    fun `selecting another filter activates it`() {
        assertEquals(SongFilter.LIKED, nextSongFilter(SongFilter.ALL, SongFilter.LIKED))
        assertEquals(SongFilter.DOWNLOADED, nextSongFilter(SongFilter.LIBRARY, SongFilter.DOWNLOADED))
    }

    @Test
    fun `embedded songs screen does not expose all or liked filters`() {
        assertEquals(SongFilter.LIBRARY, normalizeEmbeddedSongFilter(SongFilter.ALL))
        assertEquals(SongFilter.LIBRARY, normalizeEmbeddedSongFilter(SongFilter.LIKED))
    }

    @Test
    fun `embedded songs screen preserves supported filters`() {
        assertEquals(SongFilter.LIBRARY, normalizeEmbeddedSongFilter(SongFilter.LIBRARY))
        assertEquals(SongFilter.DOWNLOADED, normalizeEmbeddedSongFilter(SongFilter.DOWNLOADED))
    }
}
