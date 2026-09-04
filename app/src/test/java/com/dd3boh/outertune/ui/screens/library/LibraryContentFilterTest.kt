package com.dd3boh.outertune.ui.screens.library

import com.dd3boh.outertune.constants.AlbumFilter
import com.dd3boh.outertune.constants.ArtistFilter
import com.dd3boh.outertune.constants.LibraryContentFilter
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.ui.screens.Screens.LibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryContentFilterTest {
    @Test
    fun `unfiltered library shows enabled category chips in configured order`() {
        val enabled = listOf(
            LibraryFilter.ARTISTS,
            LibraryFilter.ALBUMS,
            LibraryFilter.PLAYLISTS,
        )

        assertEquals(
            enabled.map(::LibraryChip),
            libraryChips(LibraryFilter.ALL, enabled),
        )
    }

    @Test
    fun `supported category shows downloaded library and folder content filters`() {
        listOf(
            LibraryFilter.ALBUMS,
            LibraryFilter.ARTISTS,
            LibraryFilter.PLAYLISTS,
        ).forEach { category ->
            assertEquals(
                listOf(
                    LibraryChip(category),
                    LibraryChip(category, LibraryContentFilter.DOWNLOADED),
                    LibraryChip(category, LibraryContentFilter.LIBRARY),
                    LibraryChip(category, LibraryContentFilter.FOLDER),
                ),
                libraryChips(category, LibraryFilter.entries),
            )
        }
    }

    @Test
    fun `supported category can hide content filters during transition`() {
        assertEquals(
            listOf(LibraryChip(LibraryFilter.ALBUMS)),
            libraryChips(
                activeCategory = LibraryFilter.ALBUMS,
                enabledCategories = LibraryFilter.entries,
                showContentFilters = false,
            ),
        )
    }

    @Test
    fun `unsupported category only shows its category chip`() {
        listOf(LibraryFilter.SONGS, LibraryFilter.FOLDERS).forEach { category ->
            assertEquals(
                listOf(LibraryChip(category)),
                libraryChips(category, LibraryFilter.entries),
            )
        }
    }

    @Test
    fun `album secondary filter can return to combined default`() {
        assertEquals(AlbumFilter.DOWNLOADED, nextAlbumFilter(AlbumFilter.ALL, AlbumFilter.DOWNLOADED))
        assertEquals(AlbumFilter.ALL, nextAlbumFilter(AlbumFilter.DOWNLOADED, AlbumFilter.DOWNLOADED))
        assertEquals(AlbumFilter.LIBRARY, nextAlbumFilter(AlbumFilter.DOWNLOADED, AlbumFilter.LIBRARY))
    }

    @Test
    fun `artist secondary filter can return to combined default`() {
        assertEquals(ArtistFilter.LIBRARY, nextArtistFilter(ArtistFilter.ALL, ArtistFilter.LIBRARY))
        assertEquals(ArtistFilter.ALL, nextArtistFilter(ArtistFilter.LIBRARY, ArtistFilter.LIBRARY))
        assertEquals(ArtistFilter.DOWNLOADED, nextArtistFilter(ArtistFilter.LIBRARY, ArtistFilter.DOWNLOADED))
    }

    @Test
    fun `playlist secondary filter can return to combined default`() {
        assertEquals(PlaylistFilter.DOWNLOADED, nextPlaylistFilter(PlaylistFilter.ALL, PlaylistFilter.DOWNLOADED))
        assertEquals(PlaylistFilter.ALL, nextPlaylistFilter(PlaylistFilter.DOWNLOADED, PlaylistFilter.DOWNLOADED))
        assertEquals(PlaylistFilter.LIBRARY, nextPlaylistFilter(PlaylistFilter.DOWNLOADED, PlaylistFilter.LIBRARY))
    }

    @Test
    fun `empty mask displays no selection but evaluates all secondary filters`() {
        assertEquals(emptySet<LibraryContentFilter>(), LibraryContentFilter.fromMask(0))
        assertEquals(
            LibraryContentFilter.entries.toSet(),
            LibraryContentFilter.effectiveFromMask(0),
        )
    }

    @Test
    fun `secondary filters toggle independently`() {
        val downloadedOnly = toggleLibraryContentFilter(
            0,
            LibraryContentFilter.DOWNLOADED,
        )
        val downloadedAndLibrary = toggleLibraryContentFilter(
            downloadedOnly,
            LibraryContentFilter.LIBRARY,
        )

        assertEquals(
            setOf(LibraryContentFilter.DOWNLOADED, LibraryContentFilter.LIBRARY),
            LibraryContentFilter.fromMask(downloadedAndLibrary),
        )
        assertEquals(
            LibraryContentFilter.LIBRARY.mask,
            toggleLibraryContentFilter(downloadedAndLibrary, LibraryContentFilter.DOWNLOADED),
        )
    }

    @Test
    fun `legacy all-selected default migrates to unselected default`() {
        assertEquals(0, migrateLibraryContentFilterMask(LibraryContentFilter.allMask))
        assertEquals(
            LibraryContentFilter.LIBRARY.mask,
            migrateLibraryContentFilterMask(LibraryContentFilter.LIBRARY.mask),
        )
        assertEquals(null, migrateLibraryContentFilterMask(null))
    }

    @Test
    fun `embedded album and artist hide liked-only state behind combined default`() {
        assertEquals(AlbumFilter.ALL, normalizeEmbeddedAlbumFilter(AlbumFilter.LIKED))
        assertEquals(AlbumFilter.DOWNLOADED, normalizeEmbeddedAlbumFilter(AlbumFilter.DOWNLOADED))
        assertEquals(ArtistFilter.ALL, normalizeEmbeddedArtistFilter(ArtistFilter.LIKED))
        assertEquals(ArtistFilter.LIBRARY, normalizeEmbeddedArtistFilter(ArtistFilter.LIBRARY))
    }

}
