package com.dd3boh.outertune.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.dd3boh.outertune.fixtures.SearchUiFixtureActivity
import com.dd3boh.outertune.ui.component.LocalPlayingIndicatorAnimation
import com.dd3boh.outertune.ui.component.LocalPlayingIndicatorAnimationsEnabled
import com.dd3boh.outertune.ui.component.PlayingIndicator
import com.dd3boh.outertune.ui.component.PlayingIndicatorAnimationState
import com.dd3boh.outertune.ui.component.PlayingIndicatorBox
import com.dd3boh.outertune.ui.component.ProvidePlayingIndicatorAnimation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Exercise actual bar drawing while covered content stays composed; no player or network is needed. */
class PlayingIndicatorVisibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun coveredLegacyBarsFreezeAndResumeWithoutStoppingVisibleQueueSibling() = withFixture { activity ->
        val enabled = mutableStateOf(true)
        val retained = RetentionProbe()
        show(activity) {
            Column {
                CompositionLocalProvider(LocalPlayingIndicatorAnimationsEnabled provides enabled.value) {
                    IndicatorProbe("covered", retained) {
                        PlayingIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    }
                }
                QueueSibling()
            }
        }
        assertMoves(activity, "covered")
        val identity = retained.identity.get()

        onMain { enabled.value = false }
        nextFrames(4)
        val frozen = columns(activity, "covered")
        assertFrozen(activity, "covered", frozen)
        assertMoves(activity, "queue")
        assertEquals("Visible queue updates changed a covered indicator", frozen, columns(activity, "covered"))
        assertRetained(retained, identity)

        onMain { enabled.value = true }
        nextFrames(4)
        assertMoves(activity, "covered")
        assertRetained(retained, identity)
    }

    @Test
    fun coveredSharedBarsAndClockFreezeAndResumeWithoutStoppingVisibleQueueSibling() = withFixture { activity ->
        val enabled = mutableStateOf(true)
        val retained = RetentionProbe()
        val shared = AtomicReference<PlayingIndicatorAnimationState>()
        show(activity) {
            Column {
                CompositionLocalProvider(LocalPlayingIndicatorAnimationsEnabled provides enabled.value) {
                    ProvidePlayingIndicatorAnimation(isPlaying = true) {
                        SharedIndicatorProbe("covered", retained, shared)
                    }
                }
                QueueSibling()
            }
        }
        assertMoves(activity, "covered")
        val identity = retained.identity.get()
        val clock = shared.get()
        assertTrue("Shared playback clock never started", onMain { clock.frameTimeNanos > 0L })

        onMain { enabled.value = false }
        nextFrames(4)
        val frozenAt = onMain { clock.frameTimeNanos }
        val frozen = columns(activity, "covered")
        assertFrozen(activity, "covered", frozen)
        assertMoves(activity, "queue")
        assertEquals("Visible queue updates changed a covered indicator", frozen, columns(activity, "covered"))
        assertEquals("Covered shared provider still advances its clock", frozenAt, onMain { clock.frameTimeNanos })
        assertSame("Covering content recreated its shared clock", clock, shared.get())
        assertRetained(retained, identity)

        onMain { enabled.value = true }
        nextFrames(4)
        assertMoves(activity, "covered")
        assertTrue("Uncovered shared clock did not resume", onMain { clock.frameTimeNanos > frozenAt })
        assertSame("Uncovering content recreated its shared clock", clock, shared.get())
        assertRetained(retained, identity)
    }

    @Test
    fun stoppedLifecycleFreezesRetainedLegacyAndSharedBarsUntilStartedAgain() = withFixture { activity ->
        val owner = onMain { TestLifecycleOwner().also { it.registry.currentState = Lifecycle.State.STARTED } }
        val legacy = RetentionProbe()
        val sharedRetention = RetentionProbe()
        val shared = AtomicReference<PlayingIndicatorAnimationState>()
        show(activity) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Column {
                    IndicatorProbe("legacy", legacy) {
                        PlayingIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                    }
                    ProvidePlayingIndicatorAnimation(isPlaying = true) {
                        SharedIndicatorProbe("shared", sharedRetention, shared)
                    }
                }
            }
        }
        assertMoves(activity, "legacy")
        assertMoves(activity, "shared")
        val legacyIdentity = legacy.identity.get()
        val sharedIdentity = sharedRetention.identity.get()
        val clock = shared.get()

        // Keep the Activity and composition alive; only the supplied screen lifecycle stops.
        onMain { owner.registry.currentState = Lifecycle.State.CREATED }
        nextFrames(4)
        val frozenAt = onMain { clock.frameTimeNanos }
        val legacyPixels = columns(activity, "legacy")
        val sharedPixels = columns(activity, "shared")
        assertFrozen(activity, "legacy", legacyPixels)
        assertFrozen(activity, "shared", sharedPixels)
        assertEquals(legacyPixels, columns(activity, "legacy"))
        assertEquals("Stopped screen still advances its shared clock", frozenAt, onMain { clock.frameTimeNanos })
        assertRetained(legacy, legacyIdentity)
        assertRetained(sharedRetention, sharedIdentity)

        onMain { owner.registry.currentState = Lifecycle.State.STARTED }
        nextFrames(4)
        assertMoves(activity, "legacy")
        assertMoves(activity, "shared")
        assertTrue("Started screen did not restart its shared clock", onMain { clock.frameTimeNanos > frozenAt })
        assertSame(clock, shared.get())
        assertRetained(legacy, legacyIdentity)
        assertRetained(sharedRetention, sharedIdentity)
    }

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class RetentionProbe {
        val identity = AtomicReference<Any>()
        val disposals = AtomicInteger()
    }

    @Composable
    private fun IndicatorProbe(tag: String, retained: RetentionProbe, content: @Composable () -> Unit) {
        val identity = remember { Any() }
        SideEffect { retained.identity.set(identity) }
        DisposableEffect(Unit) { onDispose { retained.disposals.incrementAndGet() } }
        Box(Modifier.size(48.dp).background(Color.Black).testTag(tag)) { content() }
    }

    @Composable
    private fun SharedIndicatorProbe(
        tag: String,
        retained: RetentionProbe,
        state: AtomicReference<PlayingIndicatorAnimationState>,
    ) {
        val current = checkNotNull(LocalPlayingIndicatorAnimation.current)
        SideEffect { state.set(current) }
        IndicatorProbe(tag, retained) {
            PlayingIndicator(color = Color.White, modifier = Modifier.size(48.dp))
        }
    }

    @Composable
    private fun QueueSibling() {
        Box(Modifier.size(48.dp).background(Color.Black).testTag("queue")) {
            PlayingIndicatorBox(Modifier.size(48.dp), isActive = true, playWhenReady = true)
        }
    }

    private fun assertRetained(probe: RetentionProbe, identity: Any) {
        assertSame("Screen content was replaced instead of suspending its animation", identity, probe.identity.get())
        assertEquals("Screen content left composition", 0, probe.disposals.get())
    }

    private fun assertMoves(activity: SearchUiFixtureActivity, tag: String) {
        val initial = columns(activity, tag)
        // Three random legacy bars and the shared sinusoid must produce a different image
        // within 36 frames. Sampling drawing avoids asserting only an implementation flag.
        repeat(18) {
            nextFrames(2)
            if (columns(activity, tag) != initial) return
        }
        fail("$tag bars did not move while visible and started")
    }

    private fun assertFrozen(activity: SearchUiFixtureActivity, tag: String, expected: List<Int>) {
        repeat(6) {
            nextFrames(3)
            assertEquals("$tag bars changed while animation was disabled", expected, columns(activity, tag))
        }
    }

    private fun columns(activity: SearchUiFixtureActivity, tag: String): List<Int> = onMain {
        val root = activity.fixtureView.getChildAt(0) as ViewRootForTest
        val node = root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
            .single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
        val bounds = node.boundsInRoot
        val bitmap = Bitmap.createBitmap(bounds.width.roundToInt(), bounds.height.roundToInt(), Bitmap.Config.ARGB_8888)
        try {
            // Draw only the tagged area into a small bitmap, avoiding full-screen allocations.
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            activity.fixtureView.draw(canvas)
            (0 until bitmap.width).map { x ->
                (0 until bitmap.height).count { y ->
                    val pixel = bitmap.getPixel(x, y)
                    android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) > 220 &&
                        android.graphics.Color.blue(pixel) > 220
                }
            }.also { assertTrue("$tag indicator disappeared or did not draw", it.sum() > 0) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun show(activity: SearchUiFixtureActivity, content: @Composable () -> Unit) {
        onMain { activity.fixtureView.setContent(content) }
        nextFrames(4)
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
