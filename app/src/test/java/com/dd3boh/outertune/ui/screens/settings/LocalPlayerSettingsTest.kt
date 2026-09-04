package com.dd3boh.outertune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlayerSettingsTest {
    @Test
    fun `folder tab is restored before library tab`() {
        assertEquals("HSFM", restoreFolderTab("HSM"))
    }

    @Test
    fun `existing folder tab and custom order are preserved`() {
        assertEquals("MFHS", restoreFolderTab("MFHS"))
    }

    @Test
    fun `folder tab is appended when library tab is absent`() {
        assertEquals("HSAF", restoreFolderTab("HSA"))
    }
}
