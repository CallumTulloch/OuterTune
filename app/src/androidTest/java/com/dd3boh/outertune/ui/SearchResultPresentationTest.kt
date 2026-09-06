package com.dd3boh.outertune.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.R
import com.dd3boh.outertune.fixtures.SearchUiFixtureActivity
import com.dd3boh.outertune.ui.component.LocalPlayingIndicatorAnimation
import com.dd3boh.outertune.ui.component.PlayingIndicatorAnimationState
import com.dd3boh.outertune.ui.component.PlayingIndicatorBox
import com.dd3boh.outertune.ui.component.ProvidePlayingIndicatorAnimation
import com.dd3boh.outertune.ui.component.items.SearchResultListItem
import com.dd3boh.outertune.ui.component.items.YouTubeListItem
import com.zionhuang.innertube.models.AlbumItem
import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.ArtistCredit
import com.zionhuang.innertube.models.ArtistCreditStatus
import com.zionhuang.innertube.models.SongItem
import com.zionhuang.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** Real Compose layout/drawing with fixed local data; no search, player, DB, or image requests. */
class SearchResultPresentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val artistText = "翟锦彦、8082Audio、Earth, Wind & Fire"
    private val song = SongItem(
        id = "fixture-song", title = "長い曲名が途中で欠けないことを確認するテスト",
        artists = listOf(Artist(artistText, null)), duration = 184, thumbnail = "",
        artistCredit = ArtistCredit(artistText, emptyList(), ArtistCreditStatus.RAW,
            evidence = listOf("video-source:$MUSIC_VIDEO_TYPE_ATV")),
    )
    private val album = AlbumItem(
        browseId = "fixture-album", playlistId = "fixture-playlist",
        title = "長いアルバム名が途中で欠けないことを確認するテスト",
        artists = listOf(Artist(artistText, null)), year = 2026, thumbnail = "",
    )

    @Test
    fun threeLevelsKeepLongNamesAndDistinguishTrackFromAlbumInBothThemes() = withFixture { activity ->
        for ((dark, width, fontScale, playing) in listOf(
            Appearance(false, 360, 1f, true), Appearance(true, 320, 1.5f, false),
        )) {
            show(activity) {
                FixtureTheme(dark, width, fontScale) {
                    ProvidePlayingIndicatorAnimation(playing) {
                        SearchResultListItem(song, "$artistText • 3:04", isActive = true,
                            isPlaying = playing, trailingContent = { MoreButton() })
                        SearchResultListItem(album, "$artistText • 2026", isActive = true,
                            isPlaying = playing, trailingContent = { MoreButton() })
                    }
                }
            }
            val songState = activity.getString(if (playing) R.string.search_result_playing else R.string.search_result_paused)
            val albumState = activity.getString(if (playing) R.string.search_result_album_track_playing else R.string.search_result_album_track_paused)
            awaitUi(activity) { nodes -> nodes.any { it.text == albumState } }
            saveScreenshot(activity, if (dark) "search-dark-320-large-paused" else "search-light-360-playing")
            onMain {
                val nodes = textNodes(activity)
                assertTrue(nodes.any { it.text == activity.getString(R.string.search_result_song) })
                assertTrue(nodes.any { it.text == activity.getString(R.string.search_result_album) })
                assertTrue(nodes.any { it.text == songState })
                val titleNodes = listOf(song.title, album.title).map { title -> nodes.single { it.text == title } }
                val subtitleNodes = listOf("$artistText • 3:04", "$artistText • 2026")
                    .map { subtitle -> nodes.single { it.text == subtitle } }
                (titleNodes + subtitleNodes + nodes.filter { it.text == songState || it.text == albumState }).forEach {
                    assertFalse("Text was clipped: ${it.text}; ${it.diagnostics}", it.overflow)
                    assertTrue("Text extends outside fixture: ${it.text}", it.bounds.right <= activity.fixtureView.width + 1f)
                    assertTrue("Text extends below fixture: ${it.text}", it.bounds.bottom <= activity.fixtureView.height + 1f)
                }
                titleNodes.zip(subtitleNodes).forEach { (title, subtitle) ->
                    assertTrue("Artist row overlaps title", title.bounds.bottom <= subtitle.bounds.top + 1f)
                }
            }
        }
        show(activity) {
            FixtureTheme(false, 360, 1f) {
                SearchResultListItem(song, "$artistText • 3:04", isActive = false, isPlaying = true)
                SearchResultListItem(album, "$artistText • 2026", isActive = false, isPlaying = true)
            }
        }
        awaitUi(activity) { it.any { node -> node.text == album.title } }
        onMain {
            val texts = textNodes(activity).map { it.text }
            listOf(R.string.search_result_playing, R.string.search_result_paused,
                R.string.search_result_album_track_playing, R.string.search_result_album_track_paused).forEach {
                assertFalse("Inactive result has a playback label", activity.getString(it) in texts)
            }
        }
        saveScreenshot(activity, "search-inactive")
    }

    @Test
    fun threeLevelMetadataIsOptInAndDefaultAlbumRowStaysTwoLevels() = withFixture { activity ->
        val optIn = mutableStateOf(false)
        show(activity) {
            FixtureTheme(false, 360, 1f) {
                YouTubeListItem(album, showSearchMetadata = optIn.value, badges = {},
                    modifier = Modifier.testTag("album-row"))
            }
        }
        awaitUi(activity) { it.any { node -> node.text == album.title } }
        val oldHeight = onMain {
            assertFalse(textNodes(activity).any { it.text == activity.getString(R.string.search_result_album) })
            taggedNode(activity, "album-row").boundsInRoot.height
        }
        saveScreenshot(activity, "search-filtered-default-two-levels")
        onMain { optIn.value = true }
        awaitUi(activity) { it.any { node -> node.text == activity.getString(R.string.search_result_album) } }
        onMain {
            assertTrue("Opt-in metadata did not add room", taggedNode(activity, "album-row").boundsInRoot.height > oldHeight)
            assertTrue(textNodes(activity).any { it.text == "$artistText • 2026" })
        }
    }

    @Test
    fun indicatorsStayTogetherWhenSecondResultArrivesLateAndPlaybackResumes() = withFixture { activity ->
        val playing = mutableStateOf(true)
        val secondVisible = mutableStateOf(false)
        val firstState = AtomicReference<PlayingIndicatorAnimationState>()
        val secondState = AtomicReference<PlayingIndicatorAnimationState>()
        show(activity) {
            FixtureTheme(false, 360, 1f) {
                ProvidePlayingIndicatorAnimation(playing.value) {
                    IndicatorProbe("song-indicator", playing.value, firstState)
                    if (secondVisible.value) IndicatorProbe("album-indicator", playing.value, secondState)
                }
            }
        }
        awaitCondition { firstState.get()?.frameTimeNanos?.let { it > 0 } == true }
        nextFrames(3)
        onMain { secondVisible.value = true }
        awaitCondition { secondState.get() != null }
        nextFrames(3)
        onMain { assertSame("Late result must inherit the existing playback clock", firstState.get(), secondState.get()) }
        assertMatchingIndicators(activity)
        saveScreenshot(activity, "search-indicators-late-arrival")

        onMain { playing.value = false }
        nextFrames(3)
        val pausedAt = onMain { firstState.get().frameTimeNanos }
        nextFrames(3)
        onMain { assertEquals("Paused scope still advances its clock", pausedAt, firstState.get().frameTimeNanos) }
        assertMatchingIndicators(activity)
        saveScreenshot(activity, "search-indicators-paused")

        onMain { playing.value = true }
        awaitCondition { firstState.get().frameTimeNanos > pausedAt }
        nextFrames(3)
        onMain { assertSame(firstState.get(), secondState.get()) }
        assertMatchingIndicators(activity)
        saveScreenshot(activity, "search-indicators-resumed")
    }

    @Composable
    private fun IndicatorProbe(tag: String, playing: Boolean, state: AtomicReference<PlayingIndicatorAnimationState>) {
        val current = checkNotNull(LocalPlayingIndicatorAnimation.current)
        SideEffect { state.set(current) }
        Box(Modifier.size(48.dp).background(Color.Black).testTag(tag)) {
            PlayingIndicatorBox(Modifier.size(48.dp), isActive = true, playWhenReady = playing, showPauseIcon = true)
        }
    }

    @Composable
    private fun FixtureTheme(dark: Boolean, width: Int, fontScale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Column(Modifier.width(width.dp).background(MaterialTheme.colorScheme.surface)) { content() }
            }
        }
    }

    @Composable
    private fun MoreButton() {
        IconButton(onClick = {}) { Icon(Icons.Rounded.MoreVert, contentDescription = "その他") }
    }

    private data class Appearance(val dark: Boolean, val width: Int, val fontScale: Float, val playing: Boolean)
    private data class TextSnapshot(val text: String, val bounds: Rect, val overflow: Boolean, val diagnostics: String)

    private fun semantics(activity: SearchUiFixtureActivity): List<SemanticsNode> =
        (activity.fixtureView.getChildAt(0) as ViewRootForTest).semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)

    private fun textNodes(activity: SearchUiFixtureActivity): List<TextSnapshot> = semantics(activity).mapNotNull { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: return@mapNotNull null
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        // Semantics can rebuild a short paragraph at the parent's maximum width, while
        // retaining the Text's smaller measured size. Inspect occupied line bounds,
        // not unused paragraph width (which makes hasVisualOverflow a false positive).
        val clipped = layouts.any { layout ->
            layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { line ->
                layout.isLineEllipsized(line) || layout.getLineLeft(line) < -1f ||
                    layout.getLineRight(line) > layout.size.width + 1f ||
                    layout.getLineBottom(line) > layout.size.height + 1f
            } || layout.getLineEnd(layout.lineCount - 1) < layout.layoutInput.text.length
        }
        TextSnapshot(text, node.boundsInRoot, clipped, layouts.joinToString {
            "size=${it.size}, paragraph=${it.multiParagraph.width}x${it.multiParagraph.height}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}, constraints=${it.layoutInput.constraints}"
        })
    }

    private fun taggedNode(activity: SearchUiFixtureActivity, tag: String) =
        semantics(activity).single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun assertMatchingIndicators(activity: SearchUiFixtureActivity) = onMain {
        val bitmap = drawFixture(activity)
        try {
            fun columns(tag: String): List<Int> {
                val bounds = taggedNode(activity, tag).boundsInRoot
                return (bounds.left.roundToInt() until bounds.right.roundToInt()).map { x ->
                    (bounds.top.roundToInt() until bounds.bottom.roundToInt()).count { y ->
                        val pixel = bitmap.getPixel(x, y)
                        android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 &&
                            android.graphics.Color.blue(pixel) > 220
                    }
                }
            }
            val first = columns("song-indicator")
            val second = columns("album-indicator")
            assertTrue("Indicator did not draw", first.sum() > 0)
            assertEquals(first.size, second.size)
            assertTrue("Indicators draw different bar heights in the same frame", first.zip(second).all { abs(it.first - it.second) <= 1 })
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawFixture(activity: SearchUiFixtureActivity): Bitmap =
        Bitmap.createBitmap(activity.fixtureView.width, activity.fixtureView.height, Bitmap.Config.ARGB_8888).also {
            activity.fixtureView.draw(Canvas(it))
        }

    private fun saveScreenshot(activity: SearchUiFixtureActivity, name: String) = onMain {
        val output = File(activity.filesDir, "search-ui-fixtures/$name.png")
        output.parentFile!!.mkdirs()
        val bitmap = drawFixture(activity)
        try { output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    private fun show(activity: SearchUiFixtureActivity, content: @Composable () -> Unit) {
        onMain { activity.fixtureView.setContent(content) }
        nextFrames(3)
    }

    private fun awaitUi(activity: SearchUiFixtureActivity, condition: (List<TextSnapshot>) -> Boolean) =
        awaitCondition { onMain { activity.fixtureView.childCount > 0 && condition(textNodes(activity)) } }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Fixture did not reach the expected state" }
            SystemClock.sleep(16)
        }
    }

    private fun nextFrames(count: Int) {
        val complete = CountDownLatch(1)
        onMain {
            var remaining = count
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (--remaining == 0) complete.countDown()
                    else Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
        }
        assertTrue("No Android frames reached the fixture", complete.await(5, TimeUnit.SECONDS))
        // A continuously animated view need not become idle; the frame latch is bounded.
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private fun withFixture(block: (SearchUiFixtureActivity) -> Unit) {
        val intent = Intent(instrumentation.targetContext, SearchUiFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as SearchUiFixtureActivity
        try {
            block(activity)
        } finally {
            onMain { activity.finish() }
        }
    }
}
