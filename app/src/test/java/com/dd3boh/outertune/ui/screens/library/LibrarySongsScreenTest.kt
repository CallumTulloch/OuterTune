package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.constants.SongFilter
import com.dd3boh.outertune.constants.SongContentFilter
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LibrarySongsScreenTest {
    @Test
    fun `content selections toggle independently and preserve liked selection`() {
        val libraryOnly = nextSongFilterSelection(
            currentContentFilterMask = 0,
            likedOnly = true,
            selectedFilter = SongFilter.LIBRARY,
        )
        assertEquals(
            SongFilterSelection(SongContentFilter.LIBRARY.mask, likedOnly = true),
            libraryOnly,
        )

        val libraryAndDownloaded = nextSongFilterSelection(
            currentContentFilterMask = libraryOnly.contentFilterMask,
            likedOnly = libraryOnly.likedOnly,
            selectedFilter = SongFilter.DOWNLOADED,
        )
        assertEquals(
            SongFilterSelection(
                SongContentFilter.LIBRARY.mask or SongContentFilter.DOWNLOADED.mask,
                likedOnly = true,
            ),
            libraryAndDownloaded,
        )

        assertEquals(
            SongFilterSelection(SongContentFilter.DOWNLOADED.mask, likedOnly = true),
            nextSongFilterSelection(
                currentContentFilterMask = libraryAndDownloaded.contentFilterMask,
                likedOnly = libraryAndDownloaded.likedOnly,
                selectedFilter = SongFilter.LIBRARY,
            ),
        )
    }

    @Test
    fun `liked selection toggles without changing content selections`() {
        assertEquals(
            SongFilterSelection(SongContentFilter.FOLDER.mask, likedOnly = true),
            nextSongFilterSelection(
                currentContentFilterMask = SongContentFilter.FOLDER.mask,
                likedOnly = false,
                selectedFilter = SongFilter.LIKED,
            ),
        )
        assertEquals(
            SongFilterSelection(SongContentFilter.FOLDER.mask, likedOnly = false),
            nextSongFilterSelection(
                currentContentFilterMask = SongContentFilter.FOLDER.mask,
                likedOnly = true,
                selectedFilter = SongFilter.LIKED,
            ),
        )
    }

    @Test
    fun `legacy single selection migrates to content mask`() {
        assertEquals(
            SongFilterSelection(SongContentFilter.LIBRARY.mask, likedOnly = false),
            migrateLegacySongFilterSelection(SongFilter.LIBRARY, likedOnly = false),
        )
        assertEquals(
            SongFilterSelection(contentFilterMask = 0, likedOnly = true),
            migrateLegacySongFilterSelection(SongFilter.LIKED, likedOnly = false),
        )
        assertEquals(
            SongFilterSelection(contentFilterMask = 0, likedOnly = false),
            migrateLegacySongFilterSelection(null, likedOnly = false),
        )
    }

    @Test
    fun `liked and content filters follow the specified truth table`() {
        val likedLibrarySong = song(id = "liked-library", liked = true, inLibrary = true)
        val unlikedLibrarySong = song(id = "unliked-library", liked = false, inLibrary = true)
        val likedOutsideLibrary = song(id = "liked-outside", liked = true, inLibrary = false)
        val unlikedOutsideLibrary = song(id = "unliked-outside", liked = false, inLibrary = false)
        val libraryFilter = setOf(SongContentFilter.LIBRARY)

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
    fun `library and folder content filters use distinct song sources`() {
        val onlineLibrarySong = song(id = "online-library", inLibrary = true)
        val folderSong = song(id = "folder", local = true, inLibrary = true)
        val downloadedOnlineSong = song(id = "online-download", downloaded = true)
        val localSongWithDownloadDate = song(
            id = "folder-with-download-date",
            downloaded = true,
            local = true,
            inLibrary = true,
        )

        assertTrue(
            songMatchesFilters(
                onlineLibrarySong,
                setOf(SongContentFilter.LIBRARY),
                likedOnly = false,
            )
        )
        assertFalse(
            songMatchesFilters(
                folderSong,
                setOf(SongContentFilter.LIBRARY),
                likedOnly = false,
            )
        )
        assertTrue(
            songMatchesFilters(
                folderSong,
                setOf(SongContentFilter.FOLDER),
                likedOnly = false,
            )
        )
        assertFalse(
            songMatchesFilters(
                onlineLibrarySong,
                setOf(SongContentFilter.FOLDER),
                likedOnly = false,
            )
        )
        assertTrue(
            songMatchesFilters(
                downloadedOnlineSong,
                setOf(SongContentFilter.DOWNLOADED),
                likedOnly = false,
            )
        )
        assertFalse(
            songMatchesFilters(
                localSongWithDownloadDate,
                setOf(SongContentFilter.DOWNLOADED),
                likedOnly = false,
            )
        )
    }

    @Test
    fun `multiple content selections use or semantics`() {
        val contentFilters = setOf(SongContentFilter.DOWNLOADED, SongContentFilter.FOLDER)

        assertTrue(
            songMatchesFilters(
                song(id = "downloaded", downloaded = true),
                contentFilters,
                likedOnly = false,
            )
        )
        assertTrue(
            songMatchesFilters(
                song(id = "folder", local = true, inLibrary = true),
                contentFilters,
                likedOnly = false,
            )
        )
        assertFalse(
            songMatchesFilters(
                song(id = "neither"),
                contentFilters,
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
