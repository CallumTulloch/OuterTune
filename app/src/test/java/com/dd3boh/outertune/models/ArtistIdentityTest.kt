package com.dd3boh.outertune.models

import com.dd3boh.outertune.db.entities.ArtistEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistIdentityTest {
    @Test
    fun `same normalized name resolves only inside the requested source`() {
        val online = ArtistEntity(id = "UC-online", name = "Artist A", isLocal = false)
        val local = ArtistEntity(id = "LA-local", name = "Ａｒｔｉｓｔ　Ａ", isLocal = true)
        val candidates = listOf(online, local)

        assertEquals(
            local,
            selectArtistByNormalizedName(" artist   a ", true, candidates),
        )
        assertEquals(
            online,
            selectArtistByNormalizedName("artist a", false, candidates),
        )
    }

    @Test
    fun `opposite source is never used as a fallback`() {
        val online = ArtistEntity(id = "UC-online", name = "Same Name", isLocal = false)

        assertNull(selectArtistByNormalizedName("Same Name", true, listOf(online)))
    }
}
