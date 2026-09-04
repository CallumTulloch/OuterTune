package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.ArtistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistNavigationTest {
    @Test
    fun `online navigation accepts YouTube artist browse ids`() {
        assertEquals("UC123", "UC123".artistNavigationId(isLocal = false))
        assertEquals(
            "FEmusic_library_privately_owned_artist_abc",
            "FEmusic_library_privately_owned_artist_abc".artistNavigationId(isLocal = false),
        )
    }

    @Test
    fun `online navigation rejects missing and generated ids`() {
        assertNull(null.artistNavigationId(isLocal = false))
        assertNull("   ".artistNavigationId(isLocal = false))
        assertNull("LAabcdefgh".artistNavigationId(isLocal = false))
        assertNull("not-a-youtube-artist".artistNavigationId(isLocal = false))
    }

    @Test
    fun `local navigation keeps generated artist ids`() {
        assertEquals("LAabcdefgh", " LAabcdefgh ".artistNavigationId(isLocal = true))
        assertNull("   ".artistNavigationId(isLocal = true))
    }

    @Test
    fun `database artist navigation separates internal and browse ids`() {
        assertEquals(
            "LAinternal",
            ArtistEntity(id = "LAinternal", name = "Local", isLocal = true).artistNavigationId(),
        )
        assertEquals(
            "UCremote",
            ArtistEntity(
                id = "LAinternal",
                name = "Remote",
                isLocal = false,
                browseId = "UCremote",
            ).artistNavigationId(),
        )
        assertNull(
            ArtistEntity(
                id = "LAinternal",
                name = "Unresolved remote",
                isLocal = false,
                browseId = "LAgenerated",
            ).artistNavigationId(),
        )
    }
}
