package com.dd3boh.outertune.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

/** One frame clock for all indicators representing the current playback in a screen. */
@Stable
class PlayingIndicatorAnimationState {
    var frameTimeNanos by mutableLongStateOf(0L)
        internal set

    fun barHeight(index: Int): Float = playingIndicatorBarHeight(frameTimeNanos, index)
}

internal fun playingIndicatorBarHeight(frameTimeNanos: Long, index: Int): Float {
    val phase = (frameTimeNanos % 1_440_000_000L).toDouble() / 1_440_000_000L * 2 * PI
    return (0.28 + 0.72 * (0.5 + 0.5 * sin(phase + index * 2.1))).toFloat()
}

internal val LocalPlayingIndicatorAnimation = staticCompositionLocalOf<PlayingIndicatorAnimationState?> { null }

/** Whether this part of the UI is visible, independently of audio playback. */
internal val LocalPlayingIndicatorAnimationsEnabled = compositionLocalOf { true }

@Composable
internal fun playingIndicatorAnimationsEnabled(): Boolean {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return LocalPlayingIndicatorAnimationsEnabled.current && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
}

@Composable
fun ProvidePlayingIndicatorAnimation(
    isPlaying: Boolean,
    content: @Composable () -> Unit,
) {
    val animation = remember { PlayingIndicatorAnimationState() }
    val animationsEnabled = playingIndicatorAnimationsEnabled()
    LaunchedEffect(isPlaying, animationsEnabled) {
        if (!isPlaying || !animationsEnabled) return@LaunchedEffect
        val durationScale = coroutineContext[MotionDurationScale]
        snapshotFlow { durationScale?.scaleFactor ?: 1f }.collectLatest { scale ->
            if (scale <= 0f) return@collectLatest
            while (isActive) {
                withFrameNanos { animation.frameTimeNanos = (it / scale.toDouble()).toLong() }
            }
        }
    }
    CompositionLocalProvider(LocalPlayingIndicatorAnimation provides animation, content = content)
}
