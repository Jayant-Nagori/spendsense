package com.jayant.spendsense.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * CRED NeoPOP-style raised surface: a flat face with hard bevelled right and bottom edges ("plunk").
 * On press the face sinks into the edges. [edge] defaults to a darkened [color].
 */
@Composable
fun NeoPopSurface(
    color: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    depth: Dp = 4.dp,
    edge: Color = lerp(color, Color.Black, 0.45f),
    border: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val sink by animateFloatAsState(
        if (pressed && enabled) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh),
        label = "neopop",
    )
    val rightEdge = edge
    val bottomEdge = lerp(edge, color, 0.35f)
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .drawBehind {
                val full = depth.toPx()
                val p = full * sink
                val d = full - p
                if (d > 0.5f) {
                    val fr = size.width - full + p
                    val fb = size.height - full + p
                    drawPath(Path().apply { moveTo(fr, p); lineTo(fr + d, p + d); lineTo(fr + d, fb + d); lineTo(fr, fb); close() }, rightEdge)
                    drawPath(Path().apply { moveTo(p, fb); lineTo(fr, fb); lineTo(fr + d, fb + d); lineTo(p + d, fb + d); close() }, bottomEdge)
                }
            }
            .clickable(interaction, indication = null, enabled = enabled && onClick != null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick?.invoke()
            },
        propagateMinConstraints = true,
    ) {
        Box(
            Modifier
                .padding(end = depth, bottom = depth)
                .offset { val o = (depth.toPx() * sink).roundToInt(); IntOffset(o, o) }
                .background(color, RectangleShape)
                .then(if (border != null) Modifier.border(1.dp, border, RectangleShape) else Modifier),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** White NeoPOP CTA with black uppercase label — the one obvious action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = MaterialTheme.colorScheme.onBackground,
    content: Color = MaterialTheme.colorScheme.background,
) {
    NeoPopSurface(container, onClick, modifier.heightIn(min = 56.dp), enabled = enabled) {
        Text(
            text.uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing),
            color = content, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

/** Secondary NeoPOP button: black face, hairline border, grey edges. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    NeoPopSurface(
        MaterialTheme.colorScheme.background, onClick, modifier.heightIn(min = 56.dp), enabled = enabled,
        edge = Color(0xFF3A3A3A), border = Color.White.copy(alpha = 0.22f),
    ) {
        Text(
            text.uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing),
            color = color, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
