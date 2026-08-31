package com.dd3boh.outertune.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseDaoTest {
    @Test
    fun `online album keeps its canonical metadata id`() {
        val albumId = "MPREb_Oo0wRyxtDXp"

        assertEquals(albumId, resolveAlbumId(albumId))
    }

    @Test
    fun `missing album id reuses a matching local album`() {
        assertEquals("LBexisting", resolveAlbumId("", "LBexisting"))
    }

    @Test
    fun `missing album without a match receives a local album id`() {
        assertTrue(resolveAlbumId("").startsWith("LB"))
    }
}
