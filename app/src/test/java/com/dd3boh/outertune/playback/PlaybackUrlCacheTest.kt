package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackUrlCacheTest {
    @Test
    fun `logout and same account login invalidate urls and cannot accept late old responses`() {
        var revision = 1L
        val cache = PlaybackUrlCache({ 0L }, authRevision = { revision })
        cache.put("song", "account-a-old", 600)
        revision++ // logout, even if no cache lookup happens here
        revision++ // the same account logs in again
        assertNull(cache["song"])
        cache.put("song", "account-a-new", 600, requestAuthRevision = revision)
        cache.put("song", "late-account-a-old", 600, requestAuthRevision = 1L)
        assertEquals("account-a-new", cache["song"])
    }

    @Test
    fun `rejected url is unavailable on manual retry while other songs survive`() {
        val cache = PlaybackUrlCache({ 0L })
        cache.put("failed", "expired-signature", 600)
        cache.put("other", "working", 600)

        assertTrue(cache.invalidate("failed"))
        assertNull(cache["failed"])
        assertEquals("working", cache["other"])
        cache.put("failed", "fresh-signature", 600)
        assertEquals("fresh-signature", cache["failed"])
    }

    @Test
    fun `urls expire before their server deadline`() {
        var now = 0L
        val cache = PlaybackUrlCache({ now })
        cache.put("song", "signed", 60)
        now = 29_999L
        assertEquals("signed", cache["song"])
        now = 30_000L
        assertNull(cache["song"])
    }

    @Test
    fun `already expiring response replaces an older url without caching it`() {
        val cache = PlaybackUrlCache({ 0L })
        cache.put("song", "old", 600)
        cache.put("song", "almost-expired", 10)
        assertNull(cache["song"])
    }

    @Test
    fun `long running queue does not accumulate urls forever`() {
        val cache = PlaybackUrlCache({ 0L }, maxEntries = 2)
        cache.put("old", "old-url", 600)
        cache.put("second", "second-url", 600)
        cache.put("third", "third-url", 600)
        assertNull(cache["old"])
        assertEquals("second-url", cache["second"])
        assertEquals("third-url", cache["third"])
    }

    @Test
    fun `rejected stream gets one automatic refresh until another user attempt`() {
        val retry = StreamRefreshRetry()
        assertTrue(retry.shouldRetry(403, hadCachedUrl = true, playWhenReady = true))
        assertFalse(retry.shouldRetry(403, hadCachedUrl = true, playWhenReady = true))
        retry.reset()
        assertTrue(retry.shouldRetry(410, hadCachedUrl = true, playWhenReady = true))
    }

    @Test
    fun `pause and permanent or unrelated errors do not trigger stream refresh`() {
        val retry = StreamRefreshRetry()
        assertFalse(retry.shouldRetry(403, hadCachedUrl = true, playWhenReady = false))
        assertFalse(retry.shouldRetry(403, hadCachedUrl = false, playWhenReady = true))
        assertFalse(retry.shouldRetry(404, hadCachedUrl = true, playWhenReady = true))
        assertFalse(retry.shouldRetry(null, hadCachedUrl = true, playWhenReady = true))
        assertTrue(retry.shouldRetry(401, hadCachedUrl = true, playWhenReady = true))
    }
}
