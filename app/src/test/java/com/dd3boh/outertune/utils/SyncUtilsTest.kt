package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.SYNC_CD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncUtilsTest {
    private val now = 1_700_000_000L

    @Test
    fun `sync cooldown is thirty minutes in epoch seconds`() {
        assertEquals(30L * 60L, SYNC_CD)
    }

    @Test
    fun `missing sync timestamp is immediately eligible`() {
        assertTrue(isSyncEligible(lastSyncEpochSeconds = 0L, currentEpochSeconds = now))
    }

    @Test
    fun `sync is suppressed before cooldown expires`() {
        assertFalse(
            isSyncEligible(
                lastSyncEpochSeconds = now - SYNC_CD + 1L,
                currentEpochSeconds = now,
            )
        )
    }

    @Test
    fun `sync is eligible exactly at cooldown boundary`() {
        assertTrue(
            isSyncEligible(
                lastSyncEpochSeconds = now - SYNC_CD,
                currentEpochSeconds = now,
            )
        )
    }

    @Test
    fun `sync is eligible after cooldown expires`() {
        assertTrue(
            isSyncEligible(
                lastSyncEpochSeconds = now - SYNC_CD - 1L,
                currentEpochSeconds = now,
            )
        )
    }

    @Test
    fun `future sync timestamp remains in cooldown`() {
        assertFalse(isSyncEligible(lastSyncEpochSeconds = now + 1L, currentEpochSeconds = now))
    }
}
