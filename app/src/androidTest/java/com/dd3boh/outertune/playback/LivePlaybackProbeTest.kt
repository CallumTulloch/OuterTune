package com.dd3boh.outertune.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.constants.AudioQuality
import com.dd3boh.outertune.utils.YTPlayerUtils
import com.zionhuang.innertube.YouTube
import com.zionhuang.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit network probe, excluded from ordinary deterministic regression runs. No credentials or URLs are printed. */
class LivePlaybackProbeTest {
    @Test fun officialArtistAndStreamAreAvailableInCurrentAppSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePlaybackProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousLocale = YouTube.locale
        try {
            YouTube.locale = YouTubeLocale("JP", "ja")
            val artist = YouTube.artist("UCbrWU0y_rLsEOYgaTX5Y74A", notifyMetadata = false)
                .getOrElse { failWithoutCredentials("artist", it) }.artist
            assertEquals("椎名林檎", artist.title)
            val playback = YTPlayerUtils.playerResponseForPlayback(
                "4tlUwgtgdZA", audioQuality = AudioQuality.AUTO,
                connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
            ).getOrElse { failWithoutCredentials("playback", it) }
            val source = DefaultHttpDataSource.Factory().createDataSource()
            try {
                source.open(DataSpec.Builder().setUri(Uri.parse(playback.streamUrl)).setLength(4096).build())
                assertTrue("Stream returned no audio bytes", source.read(ByteArray(4096), 0, 4096) > 0)
            } catch (failure: Exception) {
                failWithoutCredentials("stream", failure)
            } finally {
                source.close()
            }
        } finally {
            YouTube.locale = previousLocale
        }
    }

    private fun failWithoutCredentials(stage: String, error: Throwable): Nothing {
        val types = generateSequence(error) { it.cause }.take(6).joinToString(" -> ") { it.javaClass.simpleName }
        throw AssertionError("Live $stage failed: $types (response and credentials omitted)")
    }
}
