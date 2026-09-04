/*
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

internal const val CHIP_ITEM_TRANSITION_DURATION_MILLIS = 300

@Composable
fun <E> ChipsRow(
    chips: List<Pair<E, String>>,
    currentValue: E,
    onValueUpdate: (E) -> Unit,
    modifier: Modifier = Modifier,
    selected: ((E) -> Boolean)? = null,
    separatorAfterIndex: Int? = null,
    isLoading: (E) -> Boolean = { false }
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))

        chips.forEachIndexed { index, (value, label) ->
            FilterChip(
                label = { Text(label) },
                selected = selected?.invoke(value) ?: (currentValue == value),
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                onClick = { onValueUpdate(value) },
                trailingIcon = {
                    if (isLoading(value)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            )

            Spacer(Modifier.width(8.dp))

            if (separatorAfterIndex == index) {
                VerticalDivider(
                    modifier = Modifier.height(24.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
fun <E> ChipsLazyRow(
    chips: List<Pair<E, String>>,
    currentValue: E,
    onValueUpdate: (E) -> Unit,
    modifier: Modifier = Modifier,
    selected: ((E) -> Boolean)? = null,
    visible: (E) -> Boolean = { true },
    itemKey: (E) -> Any = { it.toString() },
    separatorAfterIndex: Int? = null,
    isLoading: (E) -> Boolean = { false },
    visibilityTransitionKey: Any? = null,
    onVisibilityTransitionFinished: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val fadeTween: FiniteAnimationSpec<Float> = tween(
        durationMillis = CHIP_ITEM_TRANSITION_DURATION_MILLIS,
        easing = FastOutSlowInEasing
    )

    val sizeTween: FiniteAnimationSpec<IntSize> = tween(
        durationMillis = CHIP_ITEM_TRANSITION_DURATION_MILLIS,
        easing = LinearOutSlowInEasing
    )

    val visibilityStates = mutableListOf<MutableTransitionState<Boolean>>()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.width(12.dp))

        chips.forEachIndexed { index, (value, label) ->
            key(itemKey(value)) {
                val targetVisible = visible(value)
                val visibilityState = remember {
                    MutableTransitionState(targetVisible)
                }.apply {
                    targetState = targetVisible
                }
                visibilityStates += visibilityState

                AnimatedVisibility(
                    visibleState = visibilityState,
                    enter = fadeIn(fadeTween) + expandHorizontally(
                        animationSpec = sizeTween,
                        expandFrom = Alignment.Start,
                    ),
                    exit = fadeOut(fadeTween) + shrinkHorizontally(
                        animationSpec = sizeTween,
                        shrinkTowards = Alignment.Start,
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            label = { Text(label) },
                            selected = selected?.let { it(value) } ?: (currentValue == value),
                            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                            onClick = {
                                onValueUpdate(value)
                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                            },
                            trailingIcon = {
                                if (isLoading(value)) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        )

                        Spacer(Modifier.width(8.dp))

                        if (separatorAfterIndex == index) {
                            VerticalDivider(
                                modifier = Modifier.height(24.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
            }
        }
    }

    val allVisibilityTransitionsIdle = visibilityStates.all { it.isIdle }
    LaunchedEffect(visibilityTransitionKey, allVisibilityTransitionsIdle) {
        if (visibilityTransitionKey != null && allVisibilityTransitionsIdle) {
            onVisibilityTransitionFinished?.invoke()
        }
    }
}
