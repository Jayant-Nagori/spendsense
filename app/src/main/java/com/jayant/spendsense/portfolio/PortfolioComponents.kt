package com.jayant.spendsense.portfolio

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jayant.spendsense.data.AssetType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs

// Reusable pieces for the Wealth tab. One accent (colorScheme.primary) carries everything:
// gains, SIP markers, the allocation ramp and progress hairlines. Errors use colorScheme.error.

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

/**
 * Single-hue sequential ramp (magnitude): the accent at descending strength, largest slice
 * strongest. Identity never relies on color alone, the legend labels every slice.
 */
@Composable
fun allocationRamp(): List<Color> {
    val p = MaterialTheme.colorScheme.primary
    return listOf(1f, 0.78f, 0.6f, 0.46f, 0.34f, 0.25f, 0.18f).map { p.copy(alpha = it) }
}

/** False when the user turned system animations off; callers should snap instead of animate. */
@Composable
fun rememberMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember {
        try {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (_: Exception) {
            true
        }
    }
}

/** Animates from the previous value to [target]; first run counts up from 0. */
@Composable
fun rememberCountUp(target: Double, durationMs: Int = 1400): Double {
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
    weight: FontWeight = FontWeight.SemiBold,
    compact: Boolean = false,
    onOverflow: (() -> Unit)? = null,
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
        style = style.copy(fontFeatureSettings = "tnum"),
        color = base,
        fontWeight = weight,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow) onOverflow?.invoke() },
    )
}

/** Small, letter-spaced label: sentence case, quiet, names a block of data. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.0.sp),
        color = color,
        maxLines = 1,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)))
}

/** Signed gain text: accent when up, error when down. */
@Composable
fun GainText(
    gain: Double,
    pct: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    withAmount: Boolean = false,
) {
    val color = if (gain >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val sign = if (gain >= 0) "+" else "-"
    val label = if (withAmount) "$sign${formatInr(abs(gain))}  ${formatPct(pct)}" else formatPct(pct)
    Text(
        label,
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
    )
}

/** Thin donut. Slices are separated by a 2dp gap; [selected] dims the rest. */
@Composable
fun DonutChart(
    fractions: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    selected: Int? = null,
    strokeWidth: Dp = 14.dp,
    gap: Dp = 2.dp,
    centre: @Composable () -> Unit = {},
) {
    val motion = rememberMotionEnabled()
    val sweepProgress = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(fractions, motion) {
        if (!motion) { sweepProgress.snapTo(1f); return@LaunchedEffect }
        sweepProgress.snapTo(0f)
        sweepProgress.animateTo(1f, tween(1300, delayMillis = 150, easing = EaseOutExpo))
    }
    val focus = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        focus.snapTo(0f)
        focus.animateTo(1f, tween(220))
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = strokeWidth.toPx()
            val diameter = size.minDimension - sw
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
            val total = fractions.sum().takeIf { it > 0f } ?: return@Canvas
            val gapDeg = if (fractions.size > 1) (gap.toPx() / (diameter / 2f) * (180f / PI.toFloat())) else 0f
            var start = -90f
            fractions.forEachIndexed { i, f ->
                val full = f / total * 360f
                val shown = full * sweepProgress.value
                val sweep = (shown - gapDeg).coerceAtLeast(0.4f)
                val dim = selected != null && selected != i
                val c = colors[i % colors.size]
                val k = if (dim) 1f - 0.65f * focus.value else 1f
                drawArc(
                    c.copy(alpha = c.alpha * k), start + gapDeg / 2f, sweep, false, topLeft, arcSize,
                    style = Stroke(sw, cap = StrokeCap.Butt),
                )
                start += shown
            }
        }
        centre()
    }
}

/**
 * One quiet entrance for the first paint of the screen. Items that appear later (scrolled into
 * view, new holdings) skip it: [play] is false, so they render immediately.
 */
@Composable
fun Staggered(index: Int, play: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = rememberMotionEnabled()
    val animate = play && motion
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) {
            delay(index.coerceAtMost(8) * 70L)
            progress.animateTo(1f, tween(520, easing = EaseOutExpo))
        }
    }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 10.dp.toPx()
        },
    ) { content() }
}

/** Springy scale-down while pressed; pair with a clickable or button sharing [source]. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Filled accent button, pill-shaped, spring press, 52dp tall. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        interactionSource = source,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier.heightIn(min = 52.dp).pressScale(source),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Text-only action in the accent (or [color]); 44dp minimum hit area, spring press. */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val source = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        interactionSource = source,
        colors = ButtonDefaults.textButtonColors(contentColor = color),
        modifier = modifier.heightIn(min = 44.dp).pressScale(source),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Tinted accent marker for SIPs. */
@Composable
fun SipMarker(amount: Double?, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Repeat, null, Modifier.size(11.dp), tint = accent)
        Spacer(Modifier.width(4.dp))
        Text(
            if (amount != null) "SIP ${formatInrCompact(amount)}/mo" else "SIP",
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
        )
    }
}

/** 3dp hairline progress, e.g. how much of an FD's tenure has elapsed. */
@Composable
fun TenureBar(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val shown by animateFloatAsState(progress, tween(900, easing = EaseOutExpo), label = "tenure")
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(color))
    }
}

/**
 * Empty-state illustration: an unfinished allocation ring beside four rising bars and one coin.
 * Drawn on Canvas from theme colors; animates in once (snaps when system animations are off).
 */
@Composable
fun EmptyIllustration(modifier: Modifier = Modifier) {
    val motion = rememberMotionEnabled()
    val accent = MaterialTheme.colorScheme.primary
    val line = MaterialTheme.colorScheme.outlineVariant
    val soft = MaterialTheme.colorScheme.surfaceContainerHighest
    val surface = MaterialTheme.colorScheme.background
    val ring = remember { Animatable(if (motion) 0f else 1f) }
    val bars = remember { List(4) { Animatable(if (motion) 0f else 1f) } }
    val coin = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!motion) return@LaunchedEffect
        coroutineScope {
            launch { ring.animateTo(1f, tween(1100, easing = EaseOutExpo)) }
            bars.forEachIndexed { i, a ->
                launch {
                    delay(200L + i * 90L)
                    a.animateTo(1f, spring(Spring.DampingRatioLowBouncy, Spring.StiffnessLow))
                }
            }
            launch {
                delay(750)
                coin.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow))
            }
        }
    }
    Canvas(modifier.size(width = 220.dp, height = 150.dp)) {
        val w = size.width
        val h = size.height
        // Ring: dashed outline circle with an accent progress arc, deliberately unfinished.
        val r = h * 0.36f
        val c = Offset(w * 0.28f, h * 0.5f)
        val stroke = 7.dp.toPx()
        drawCircle(
            line, radius = r, center = c,
            style = Stroke(stroke / 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 7.dp.toPx()))),
        )
        drawArc(
            accent, -90f, 250f * ring.value, false,
            Offset(c.x - r, c.y - r), Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        // Bars: ascending, the last one in the accent.
        val barW = 16.dp.toPx()
        val gap = 9.dp.toPx()
        val baseY = h * 0.86f
        val heights = listOf(0.2f, 0.34f, 0.5f, 0.68f).map { it * h }
        val startX = w * 0.56f
        heights.forEachIndexed { i, bh ->
            val shown = bh * bars[i].value
            val x = startX + i * (barW + gap)
            drawRoundRect(
                if (i == heights.lastIndex) accent.copy(alpha = 0.9f) else soft,
                topLeft = Offset(x, baseY - shown),
                size = Size(barW, shown.coerceAtLeast(0.1f)),
                cornerRadius = CornerRadius(5.dp.toPx()),
            )
        }
        // Coin resting on the tallest bar.
        val lastX = startX + 3 * (barW + gap) + barW / 2f
        val coinR = 11.dp.toPx() * coin.value
        val coinY = baseY - heights.last() * bars.last().value - coinR - 6.dp.toPx() * coin.value
        drawCircle(accent, coinR, Offset(lastX, coinY))
        drawCircle(surface, coinR * 0.5f, Offset(lastX, coinY), style = Stroke(1.5.dp.toPx()))
        drawLine(line, Offset(w * 0.5f, baseY + 6.dp.toPx()), Offset(w, baseY + 6.dp.toPx()), strokeWidth = 1.dp.toPx())
    }
}
