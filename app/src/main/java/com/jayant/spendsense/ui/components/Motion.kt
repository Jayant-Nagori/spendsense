package com.jayant.spendsense.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState

/** Gentle press scale (0.97) + click, no ripple: the scale is the feedback. */
fun Modifier.pressable(
    enabled: Boolean = true,
    haptic: Boolean = false,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val feedback = LocalHapticFeedback.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role) {
            if (haptic) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** Fade + small slide-up entrance, delayed by [index] (capped) so sections cascade once on first composition. */
fun Modifier.staggeredEntrance(index: Int, stepMillis: Int = 55): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 420, delayMillis = (index.coerceAtMost(10)) * stepMillis, easing = FastOutSlowInEasing))
    }
    this
        .alpha(progress.value)
        .graphicsLayer { translationY = (1f - progress.value) * 24.dp.toPx() }
}

/** 0f -> 1f once on first composition; for charts that draw themselves in. */
@Composable
fun rememberEntranceProgress(key: Any? = Unit, millis: Int = 700, delay: Int = 120): Float {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(millis, delay, FastOutSlowInEasing)) }
    return p.value
}
