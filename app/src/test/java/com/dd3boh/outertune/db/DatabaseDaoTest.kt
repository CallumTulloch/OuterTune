package com.dd3boh.outertune.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseDaoTest {
    @Test
    fun `online album keeps its canonical metadata id`() {
        val albumId = "MPREb_Oo0wRyxtDXp"

        assertEquals(
            albumId,
            resolveAlbumId(
                metadataAlbumId = albumId,
                isLocal = false,
                existingLocalAlbumId = "LBsameTitle"
            )
        )
    }

    @Test
    fun `local album reuses a matching local album instead of its generated id`() {
        assertEquals(
            "LBexisting",
            resolveAlbumId(
                metadataAlbumId = "LBgenerated",
                isLocal = true,
                existingLocalAlbumId = "LBexisting"
            )
        )
    }

    @Test
    fun `first local album keeps its generated id`() {
        assertEquals(
            "LBgenerated",
            resolveAlbumId(
                metadataAlbumId = "LBgenerated",
                isLocal = true
            )
        )
    }

    @Test
    fun `missing album id receives a local album id`() {
        assertTrue(
            resolveAlbumId(
                metadataAlbumId = "",
                isLocal = true
            ).startsWith("LB")
        )
    }
}
