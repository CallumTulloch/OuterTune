package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.constants.SongFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySongsScreenTest {
    @Test
    fun `selecting the active source clears only the source selection`() {
        listOf(
            SongFilter.LIBRARY,
            SongFilter.DOWNLOADED,
            SongFilter.FOLDER,
        ).forEach { filter ->
            assertEquals(
                SongFilterSelection(SongFilter.ALL, likedOnly = true),
                nextSongFilterSelection(filter, likedOnly = true, selectedFilter = filter),
            )
        }
    }

    @Test
    fun `selecting another source preserves liked selection`() {
        assertEquals(
            SongFilterSelection(SongFilter.DOWNLOADED, likedOnly = true),
            nextSongFilterSelection(SongFilter.LIBRARY, likedOnly = true, SongFilter.DOWNLOADED),
        )
    }

    @Test
    fun `liked selection toggles independently from source`() {
        assertEquals(
            SongFilterSelection(SongFilter.LIBRARY, likedOnly = true),
            nextSongFilterSelection(SongFilter.LIBRARY, likedOnly = false, SongFilter.LIKED),
        )
        assertEquals(
            SongFilterSelection(SongFilter.LIBRARY, likedOnly = false),
            nextSongFilterSelection(SongFilter.LIBRARY, likedOnly = true, SongFilter.LIKED),
        )
    }

    @Test
    fun `legacy liked source is normalized to all sources`() {
        assertEquals(SongFilter.ALL, normalizeSongSourceFilter(SongFilter.LIKED))
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
        assertEquals(SongFilter.FOLDER, normalizeEmbeddedSongFilter(SongFilter.FOLDER))
    }
}
