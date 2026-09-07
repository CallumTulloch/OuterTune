package com.dd3boh.outertune.repositories

import com.dd3boh.outertune.db.entities.ArtistEntity
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ArtistImagePolicyTest {
    @Test fun `only identified remote artists with missing or aged images need requests`() {
        val updated = LocalDateTime.of(2026, 9, 7, 12, 0)
        val now = updated.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val artist = ArtistEntity("LA-internal", "Original name", onlineId = "UC-profile",
            thumbnailUrl = "https://example.invalid/avatar", lastUpdateTime = updated)
        assertFalse(artistImageNeedsRefresh(artist, now))
        assertFalse(artistImageNeedsRefresh(artist, now + IMAGE_REFRESH_MS - 1))
        assertTrue(artistImageNeedsRefresh(artist, now + IMAGE_REFRESH_MS))
        assertTrue(artistImageNeedsRefresh(artist.copy(thumbnailUrl = null), now))
        assertTrue(artistImageNeedsRefresh(artist.copy(thumbnailUrl = "  "), now))
        assertFalse(artistImageNeedsRefresh(artist.copy(isLocal = true, thumbnailUrl = null), now))
        assertFalse(artistImageNeedsRefresh(artist.copy(onlineId = null, thumbnailUrl = null), now))
    }
}
