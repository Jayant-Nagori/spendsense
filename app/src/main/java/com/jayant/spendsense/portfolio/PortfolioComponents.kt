package com.jayant.spendsense.portfolio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.jayant.spendsense.data.AssetType
import kotlin.math.abs

// Reusable bits for the Wealth tab. Colors come from MaterialTheme only; the donut derives its
// slice colors from primary/secondary/tertiary plus tints.

fun assetIcon(type: AssetType): ImageVector = when (type) {
    AssetType.MUTUAL_FUND -> Icons.Rounded.PieChart
    AssetType.STOCK -> Icons.AutoMirrored.Rounded.ShowChart
    AssetType.FIXED_DEPOSIT -> Icons.Rounded.AccountBalance
    AssetType.RECURRING_DEPOSIT -> Icons.Rounded.Repeat
    AssetType.BOND -> Icons.Rounded.Description
    AssetType.GOLD -> Icons.Rounded.Diamond
    AssetType.PPF -> Icons.Rounded.Security
    AssetType.EPF -> Icons.Rounded.Work
    AssetType.NPS -> Icons.Rounded.BeachAccess
    AssetType.CRYPTO -> Icons.Rounded.CurrencyBitcoin
    AssetType.REAL_ESTATE -> Icons.Rounded.Home
    AssetType.SAVINGS -> Icons.Rounded.Savings
    AssetType.INSURANCE -> Icons.Rounded.HealthAndSafety
    AssetType.LOAN_GIVEN -> Icons.Rounded.Handshake
    AssetType.OTHER -> Icons.Rounded.Category
}

/** Slice colors derived from the theme: primary, tertiary, secondary, then blends and muted tints. */
@Composable
fun chartPalette(): List<Color> {
    val p = MaterialTheme.colorScheme.primary
    val s = MaterialTheme.colorScheme.secondary
    val t = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.onSurface
    return listOf(
        p,
        t,
        s,
        lerp(p, t, 0.5f),
        lerp(s, t, 0.5f),
        lerp(p, s, 0.5f),
        lerp(p, surface, 0.45f),
        lerp(t, surface, 0.45f),
    )
}

/** Animates smoothly from the previous value to [target] (first run counts up from 0). */
@Composable
fun rememberCountUp(target: Double, durationMs: Int = 1000): Double {
    var from by remember { mutableDoubleStateOf(0.0) }
    var to by remember { mutableDoubleStateOf(0.0) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        from = from + (to - from) * progress.value
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return from + (to - from) * progress.value
}

/** Money with a smaller, softer rupee symbol and tabular digits. */
@Composable
fun MoneyText(
    amount: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    color: Color = Color.Unspecified,
    decimals: Int = 0,
    weight: FontWeight = FontWeight.SemiBold,
    compact: Boolean = false,
) {
    val symbolColor = (if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color).copy(alpha = 0.65f)
    val text = if (compact) formatInrCompact(amount) else formatInr(amount, decimals)
    val annotated = buildAnnotatedString {
        var body = text
        var prefix = ""
        if (body.startsWith("-")) { prefix = "-"; body = body.drop(1) }
        if (body.startsWith("₹")) {
            append(prefix)
            withStyle(SpanStyle(fontSize = 0.62.em, color = symbolColor, fontWeight = FontWeight.Medium)) { append("₹") }
            append(body.drop(1))
        } else append(text)
    }
    Text(
        annotated,
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = color,
        fontWeight = weight,
        maxLines = 1,
    )
}

/** Gain / loss pill: arrow, absolute change and percentage. */
@Composable
fun GainPill(gain: Double, pct: Double, modifier: Modifier = Modifier, showAmount: Boolean = true) {
    val positive = gain >= 0
    val tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = tint.copy(alpha = 0.14f),
        contentColor = tint,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (positive) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            val label = if (showAmount) "${formatInr(abs(gain))}  ·  ${formatPct(pct, signed = false).trimStart('-')}"
            else formatPct(pct)
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Donut chart; slices animate in. [colors] must have at least as many entries as [fractions]. */
@Composable
fun DonutChart(
    fractions: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 22.dp,
    gapDegrees: Float = 2.5f,
    centre: @Composable () -> Unit = {},
) {
    val sweepProgress = remember { Animatable(0f) }
    LaunchedEffect(fractions) {
        sweepProgress.snapTo(0f)
        sweepProgress.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = strokeWidth.toPx()
            val diameter = size.minDimension - sw
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
            val total = fractions.sum().takeIf { it > 0f } ?: return@Canvas
            val gap = if (fractions.size > 1) gapDegrees else 0f
            var start = -90f
            fractions.forEachIndexed { i, f ->
                val full = f / total * 360f
                val sweep = (full * sweepProgress.value - gap).coerceAtLeast(0.5f)
                drawArc(
                    colors[i % colors.size], start + gap / 2f, sweep, false, topLeft, arcSize,
                    style = Stroke(sw, cap = StrokeCap.Butt),
                )
                start += full * sweepProgress.value
            }
        }
        centre()
    }
}

/** Fades and slides a block in once, delayed by [index] for a staggered entrance. */
@Composable
fun Staggered(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay((index.coerceAtMost(8)) * 60L)
        progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 28.dp.toPx()
        },
    ) { content() }
}

/** Slight scale-down while pressed, paired with a clickable that shares [source]. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.97f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "press",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun AssetIconBadge(type: AssetType, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            assetIcon(type), contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}
