package com.jayant.spendsense.portfolio

import com.jayant.spendsense.platformContext

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CurrencyBitcoin
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Handshake
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.delay
import kotlin.math.abs

// Reusable pieces for the Wealth tab (NeoPOP): flat rows, hairlines, square tiles, one lime highlight.
// Buttons come from ui/components (NeoPopSurface / PrimaryButton / GhostButton).

/** Ease-out with a long tail: decisive start, soft landing. */
val EaseOutExpo = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

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

/** False when the user turned system animations off; callers should snap instead of animate. */
@Composable
fun rememberMotionEnabled(): Boolean = com.jayant.spendsense.rememberSystemMotionEnabled()

/** Animates from the previous value to [target]; first run counts up from 0. */
@Composable
fun rememberCountUp(target: Double, durationMs: Int = 900): Double {
    val motion = rememberMotionEnabled()
    var from by remember { mutableDoubleStateOf(0.0) }
    var to by remember { mutableDoubleStateOf(0.0) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target, motion) {
        if (!motion) {
            from = target; to = target
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        from = from + (to - from) * progress.value
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = EaseOutExpo))
    }
    return from + (to - from) * progress.value
}

/** Money with a smaller, softer, raised rupee symbol and tabular digits. */
@Composable
fun MoneyText(
    amount: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleLarge,
    color: Color = Color.Unspecified,
    decimals: Int = 0,
    weight: FontWeight? = null,
    compact: Boolean = false,
    tabular: Boolean = true,
) {
    val base = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val text = if (compact) formatInrCompact(amount) else formatInr(amount, decimals)
    val annotated = buildAnnotatedString {
        var body = text
        var prefix = ""
        if (body.startsWith("-")) { prefix = "-"; body = body.drop(1) }
        if (body.startsWith("₹")) {
            append(prefix)
            withStyle(
                SpanStyle(
                    fontSize = 0.55.em,
                    color = base.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Medium,
                    baselineShift = BaselineShift(0.3f),
                ),
            ) { append("₹") }
            append(body.drop(1))
        } else append(text)
    }
    Text(
        annotated,
        modifier = modifier,
        style = style.copy(fontFeatureSettings = if (tabular) "tnum" else null),
        color = base,
        fontWeight = weight ?: style.fontWeight,
        maxLines = 1,
        softWrap = false,
    )
}

/** UPPERCASE grey label that names a block of data. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.spend.hairline))
}

/** Gain/loss colour: credit green up, error down, grey when flat. */
@Composable
fun gainColor(gain: Double): Color = when {
    abs(gain) < 0.5 -> MaterialTheme.colorScheme.onSurfaceVariant
    gain > 0 -> MaterialTheme.spend.credit
    else -> MaterialTheme.colorScheme.error
}

/** Signed gain percentage. */
@Composable
fun GainText(
    gain: Double,
    pct: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Text(
        formatPct(pct),
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum", letterSpacing = 0.em),
        color = gainColor(gain),
        maxLines = 1,
    )
}

/** 40dp square monochrome icon tile. */
@Composable
fun IconTile(type: AssetType, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(2.dp)
    Box(
        modifier
            .size(40.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.spend.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(assetIcon(type), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Thin 2dp progress line for an FD's tenure. Square ends. */
@Composable
fun TenureBar(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Box(modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.spend.hairline)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(2.dp).background(color))
    }
}

/** Greyscale ramp for allocation segments; the largest slice (index 0) is lime. */
@Composable
fun allocationRamp(): List<Color> {
    val ink = MaterialTheme.colorScheme.onSurface
    return listOf(MaterialTheme.colorScheme.primary, com.jayant.spendsense.ui.theme.LocalSpendColors.current.accent2) +
        listOf(0.6f, 0.42f, 0.3f, 0.2f, 0.13f).map { ink.copy(alpha = it) }
}

/** One 4dp stacked horizontal bar, 2dp gaps between segments, square ends. */
@Composable
fun AllocationBar(slices: List<AllocationSlice>, colors: List<Color>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        slices.forEachIndexed { i, s ->
            Box(
                Modifier
                    .weight(s.fraction.coerceAtLeast(0.01f))
                    .height(4.dp)
                    .background(colors[i % colors.size]),
            )
        }
    }
}

/** Fades in once on first paint. Later items (scrolled in, new holdings) render immediately. */
@Composable
fun Staggered(index: Int, play: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    val animate = play && motion
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) {
            delay(index.coerceAtMost(6) * 40L)
            progress.animateTo(1f, tween(220))
        }
    }
    Box(modifier.graphicsLayer { alpha = progress.value }) { content() }
}
