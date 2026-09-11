package com.dd3boh.outertune.utils

import com.dd3boh.outertune.constants.AudioQuality
import com.zionhuang.innertube.models.response.PlayerResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackFormatsTest {
    private val low = format(140, 48_000)
    private val high = format(141, 256_000)

    @Test
    fun `cached high quality bytes retain their representation after switching to metered network`() {
        assertEquals(high, selectPlaybackFormat(listOf(low, high), AudioQuality.AUTO, false, null))
        assertEquals(high, selectPlaybackFormat(listOf(low, high), AudioQuality.AUTO, true, high.itag))
        assertEquals(high, selectPlaybackFormat(listOf(low, high), AudioQuality.LOW, true, high.itag))
    }

    @Test
    fun `uncached playback respects current connection and quality preference`() {
        assertEquals(low, selectPlaybackFormat(listOf(low, high), AudioQuality.AUTO, true, null))
        assertEquals(high, selectPlaybackFormat(listOf(low, high), AudioQuality.HIGH, true, null))
    }

    @Test
    fun `missing cached representation tries another client instead of mixing formats`() {
        assertNull(selectPlaybackFormat(listOf(low), AudioQuality.AUTO, true, high.itag))
        assertEquals(high, selectPlaybackFormat(listOf(high), AudioQuality.AUTO, true, high.itag))
    }

    @Test
    fun `video formats cannot satisfy an audio representation`() {
        assertNull(selectPlaybackFormat(listOf(high.copy(width = 1920)), AudioQuality.HIGH, false, high.itag))
    }

    private fun format(itag: Int, bitrate: Int) = PlayerResponse.StreamingData.Format(
        itag = itag, bitrate = bitrate, url = "https://example.invalid/$itag",
        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"", width = null, height = null,
        contentLength = 1_000_000L, quality = "tiny", fps = null, qualityLabel = null,
        averageBitrate = null, audioQuality = null, approxDurationMs = null,
        audioSampleRate = 44_100, audioChannels = 2, loudnessDb = null,
        lastModified = null, signatureCipher = null,
    )
}
