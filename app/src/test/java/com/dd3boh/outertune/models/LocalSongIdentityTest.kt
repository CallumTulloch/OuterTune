package com.dd3boh.outertune.models

import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.db.entities.AlbumEntity
import com.dd3boh.outertune.db.entities.ArtistEntity
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalSongIdentityTest {
    private fun identity(
        id: String,
        title: String = "Shared Song",
        artistNames: List<String> = listOf("Track Artist"),
        albumTitle: String? = "Shared Album",
        albumArtistNames: List<String> = listOf("Album Artist"),
        albumMusicBrainzId: String? = null,
        trackNumber: Int? = 1,
        discNumber: Int? = 1,
        duration: Int = 240,
        localPath: String = "/music/album/$id.flac",
    ) = LocalSongIdentity(
        id = id,
        title = title,
        artistNames = artistNames,
        albumTitle = albumTitle,
        albumArtistNames = albumArtistNames,
        albumMusicBrainzId = albumMusicBrainzId,
        trackNumber = trackNumber,
        discNumber = discNumber,
        duration = duration,
        localPath = localPath,
    )

    private fun songTempData(id: String, albumArtist: String) = SongTempData(
        song = Song(
            song = SongEntity(
                id = id,
                title = "Shared Song",
                duration = 240,
                localPath = "/music/$id.flac",
                isLocal = true,
            ),
            artists = listOf(ArtistEntity("LA-track-$id", "Track Artist", isLocal = true)),
            album = AlbumEntity(
                id = "LB-$id",
                title = "Shared Album",
                songCount = 1,
                duration = 240,
                isLocal = true,
            ),
        ),
        format = null,
        albumArtists = listOf(ArtistEntity("LA-album-$id", albumArtist, isLocal = true)),
    )

    @Test
    fun `conflicting album artists never identify one song`() {
        assertNull(
            localSongMatchScore(
                incoming = identity("new", albumArtistNames = listOf("Artist B")),
                candidate = identity("old", albumArtistNames = listOf("Artist A")),
                matchStrength = ScannerMatchCriteria.LEVEL_2,
                strictFileNames = false,
                strictFilePaths = false,
            ),
        )
    }

    @Test
    fun `conflicting musicbrainz releases never identify one moved song`() {
        assertNull(
            localSongMatchScore(
                incoming = identity(
                    "new",
                    albumMusicBrainzId = "b67ea343-3f47-4d75-9819-6adf476f2baa",
                ),
                candidate = identity(
                    "old",
                    albumMusicBrainzId = "42d3f760-8f20-4f8a-96bd-955e680c4374",
                ),
                matchStrength = ScannerMatchCriteria.LEVEL_2,
                strictFileNames = false,
                strictFilePaths = false,
            ),
        )
    }

    @Test
    fun `exact path remains authoritative after tags change`() {
        val existing = identity("old", localPath = "/music/file.flac")
        val incoming = identity(
            id = "new",
            title = "Corrected title",
            artistNames = listOf("Corrected artist"),
            albumArtistNames = listOf("Corrected album artist"),
            localPath = "/music/file.flac",
        )

        assertEquals(
            existing,
            selectMatchingLocalSong(
                incoming,
                listOf(existing),
                ScannerMatchCriteria.LEVEL_3,
                strictFileNames = true,
                strictFilePaths = true,
            ),
        )
    }

    @Test
    fun `track number selects a unique best candidate`() {
        val first = identity("first", trackNumber = 1)
        val second = identity("second", trackNumber = 2)

        assertEquals(
            second,
            selectMatchingLocalSong(
                identity("new", trackNumber = 2),
                listOf(first, second),
                ScannerMatchCriteria.LEVEL_2,
                strictFileNames = false,
                strictFilePaths = false,
            ),
        )
    }

    @Test
    fun `ambiguous equal scores do not update an arbitrary row`() {
        assertNull(
            selectMatchingLocalSong(
                identity("new", trackNumber = null),
                listOf(
                    identity("first", trackNumber = 1),
                    identity("second", trackNumber = 2),
                ),
                ScannerMatchCriteria.LEVEL_2,
                strictFileNames = false,
                strictFilePaths = false,
            ),
        )
    }

    @Test
    fun `scan dedup retains same metadata with different album artists`() {
        val songs = listOf(
            songTempData("first", "Artist A"),
            songTempData("second", "Artist B"),
        )

        assertEquals(
            2,
            deduplicateLocalSongs(
                songs,
                ScannerMatchCriteria.LEVEL_2,
                strictFileNames = false,
                strictFilePaths = false,
            ).size,
        )
    }
}
