package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.constants.SongFilter
import com.dd3boh.outertune.constants.SongSourceFilter
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LibrarySongsScreenTest {
    @Test
    fun `source selections toggle independently and preserve liked selection`() {
        val libraryOnly = nextSongFilterSelection(
            currentSourceFilterMask = 0,
            likedOnly = true,
            selectedFilter = SongFilter.LIBRARY,
        )
        assertEquals(
            SongFilterSelection(SongSourceFilter.LIBRARY.mask, likedOnly = true),
            libraryOnly,
        )

        val libraryAndDownloaded = nextSongFilterSelection(
            currentSourceFilterMask = libraryOnly.sourceFilterMask,
            likedOnly = libraryOnly.likedOnly,
            selectedFilter = SongFilter.DOWNLOADED,
        )
        assertEquals(
            SongFilterSelection(
                SongSourceFilter.LIBRARY.mask or SongSourceFilter.DOWNLOADED.mask,
                likedOnly = true,
            ),
            libraryAndDownloaded,
        )

        assertEquals(
            SongFilterSelection(SongSourceFilter.DOWNLOADED.mask, likedOnly = true),
            nextSongFilterSelection(
                currentSourceFilterMask = libraryAndDownloaded.sourceFilterMask,
                likedOnly = libraryAndDownloaded.likedOnly,
                selectedFilter = SongFilter.LIBRARY,
            ),
        )
    }

    @Test
    fun `liked selection toggles without changing source selections`() {
        assertEquals(
            SongFilterSelection(SongSourceFilter.FOLDER.mask, likedOnly = true),
            nextSongFilterSelection(
                currentSourceFilterMask = SongSourceFilter.FOLDER.mask,
                likedOnly = false,
                selectedFilter = SongFilter.LIKED,
            ),
        )
        assertEquals(
            SongFilterSelection(SongSourceFilter.FOLDER.mask, likedOnly = false),
            nextSongFilterSelection(
                currentSourceFilterMask = SongSourceFilter.FOLDER.mask,
                likedOnly = true,
                selectedFilter = SongFilter.LIKED,
            ),
        )
    }

    @Test
    fun `legacy single source selection migrates to source mask`() {
        assertEquals(
            SongFilterSelection(SongSourceFilter.LIBRARY.mask, likedOnly = false),
            migrateLegacySongFilterSelection(SongFilter.LIBRARY, likedOnly = false),
        )
        assertEquals(
            SongFilterSelection(sourceFilterMask = 0, likedOnly = true),
            migrateLegacySongFilterSelection(SongFilter.LIKED, likedOnly = false),
        )
        assertEquals(
            SongFilterSelection(sourceFilterMask = 0, likedOnly = false),
            migrateLegacySongFilterSelection(null, likedOnly = false),
        )
    }

    @Test
    fun `liked and source filters follow the specified truth table`() {
        val likedLibrarySong = song(id = "liked-library", liked = true, inLibrary = true)
        val unlikedLibrarySong = song(id = "unliked-library", liked = false, inLibrary = true)
        val likedOutsideLibrary = song(id = "liked-outside", liked = true, inLibrary = false)
        val unlikedOutsideLibrary = song(id = "unliked-outside", liked = false, inLibrary = false)
        val libraryFilter = setOf(SongSourceFilter.LIBRARY)

        listOf(likedLibrarySong, unlikedLibrarySong, likedOutsideLibrary, unlikedOutsideLibrary)
            .forEach { candidate ->
                assertTrue(songMatchesFilters(candidate, emptySet(), likedOnly = false))
            }

        assertTrue(songMatchesFilters(likedLibrarySong, libraryFilter, likedOnly = false))
        assertTrue(songMatchesFilters(unlikedLibrarySong, libraryFilter, likedOnly = false))
        assertFalse(songMatchesFilters(likedOutsideLibrary, libraryFilter, likedOnly = false))

        assertTrue(songMatchesFilters(likedLibrarySong, emptySet(), likedOnly = true))
        assertTrue(songMatchesFilters(likedOutsideLibrary, emptySet(), likedOnly = true))
        assertFalse(songMatchesFilters(unlikedLibrarySong, emptySet(), likedOnly = true))

        assertTrue(songMatchesFilters(likedLibrarySong, libraryFilter, likedOnly = true))
        assertFalse(songMatchesFilters(unlikedLibrarySong, libraryFilter, likedOnly = true))
        assertFalse(songMatchesFilters(likedOutsideLibrary, libraryFilter, likedOnly = true))
    }

    @Test
    fun `multiple source selections use or semantics`() {
        val sourceFilters = setOf(SongSourceFilter.DOWNLOADED, SongSourceFilter.FOLDER)

        assertTrue(
            songMatchesFilters(
                song(id = "downloaded", downloaded = true),
                sourceFilters,
                likedOnly = false,
            )
        )
        assertTrue(
            songMatchesFilters(
                song(id = "folder", local = true, inLibrary = true),
                sourceFilters,
                likedOnly = false,
            )
        )
        assertFalse(
            songMatchesFilters(
                song(id = "neither"),
                sourceFilters,
                likedOnly = false,
            )
        )
    }

    private fun song(
        id: String,
        liked: Boolean = false,
        inLibrary: Boolean = false,
        downloaded: Boolean = false,
        local: Boolean = false,
    ) = SongEntity(
        id = id,
        title = id,
        inLibrary = TEST_DATE.takeIf { inLibrary },
        isLocal = local,
        localPath = null,
        dateDownload = TEST_DATE.takeIf { downloaded },
        liked = liked,
        likedDate = TEST_DATE.takeIf { liked },
    )

    companion object {
        private val TEST_DATE = LocalDateTime.of(2026, 1, 1, 0, 0)
    }
}
