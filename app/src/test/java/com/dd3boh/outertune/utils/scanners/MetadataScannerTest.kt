package com.dd3boh.outertune.utils.scanners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataScannerTest {
    @Test
    fun `album artist tag aliases are recognized without matching sort tags`() {
        assertTrue(isAlbumArtistTag("ALBUMARTIST"))
        assertTrue(isAlbumArtistTag("album_artist"))
        assertTrue(isAlbumArtistTag("Album Artist"))
        assertFalse(isAlbumArtistTag("ALBUMARTISTSORT"))
        assertFalse(isAlbumArtistTag("ARTIST"))
    }

    @Test
    fun `album artist values split only explicit multi-value separators`() {
        assertEquals(
            listOf("Artist A", "Artist B", "Artist C"),
            parseAlbumArtistNames(listOf("Artist A; Artist B\u0000Artist C")),
        )
    }

    @Test
    fun `commas and ampersands remain part of an album artist name`() {
        assertEquals(
            listOf("Earth, Wind & Fire"),
            parseAlbumArtistNames(listOf("Earth, Wind & Fire")),
        )
    }

    @Test
    fun `album artist values are cleaned and deduplicated by normalized name`() {
        assertEquals(
            listOf("Artist A", "Artist B"),
            parseAlbumArtistNames(
                listOf(
                    "  Artist   A  ",
                    "Ａｒｔｉｓｔ　Ａ",
                    "artist a",
                    "Artist B",
                    " ",
                ),
            ),
        )
    }

    @Test
    fun `musicbrainz album id tag aliases are recognized`() {
        assertTrue(isMusicBrainzAlbumIdTag("MUSICBRAINZ_ALBUMID"))
        assertTrue(isMusicBrainzAlbumIdTag("MusicBrainz Album Id"))
        assertTrue(isMusicBrainzAlbumIdTag("musicbrainz-album-id"))
        assertFalse(isMusicBrainzAlbumIdTag("MUSICBRAINZ_RELEASEGROUPID"))
    }

    @Test
    fun `musicbrainz album id is validated and normalized`() {
        assertEquals(
            "42d3f760-8f20-4f8a-96bd-955e680c4374",
            parseMusicBrainzAlbumId(
                listOf(
                    "not-a-release-id",
                    "{42D3F760-8F20-4F8A-96BD-955E680C4374}",
                ),
            ),
        )
        assertEquals(null, parseMusicBrainzAlbumId(listOf("1234", "")))
    }
}
