package com.dd3boh.outertune.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalMediaNavigationTest {
    @Test
    fun `disabled local media hides folder without changing encoded tabs`() {
        val encodedTabs = "HSFM"

        val visibleScreens = Screens.getScreens(encodedTabs, localMediaEnabled = false)

        assertEquals(listOf(Screens.Home, Screens.Songs, Screens.Library), visibleScreens)
        assertFalse(Screens.Folders in visibleScreens)
        assertEquals("HSFM", encodedTabs)
    }

    @Test
    fun `enabled local media includes folder in configured position`() {
        assertEquals(
            listOf(Screens.Home, Screens.Songs, Screens.Folders, Screens.Library),
            Screens.getScreens("HSFM", localMediaEnabled = true),
        )
    }
}
