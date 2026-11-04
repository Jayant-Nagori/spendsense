package com.jayant.spendsense.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.ui.theme.spend
import kotlin.math.max
import kotlin.math.min

data class BarDatum(val label: String, val value: Double)

/**
 * Canvas bar chart. Bars grow in on first draw, tapping or dragging selects a bar ([selected] / [onSelect]),
 * a dashed line marks [average]. X labels are drawn for every [labelEvery]th bar.
 */
@Composable
fun BarChart(
    data: List<BarDatum>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    average: Double? = null,
    labelEvery: Int = 7,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val progress = rememberEntranceProgress(key = data.size, millis = 800)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.spend.hairline
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val selectedAnim by animateFloatAsState(selected?.toFloat() ?: -1f, tween(180), label = "barSel")
    val maxV = max(data.maxOfOrNull { it.value } ?: 0.0, 1.0)
    // Round the axis up a little so the tallest bar never touches the top.
    val axisMax = maxV * 1.12

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(data.size) {
                detectTapGestures { pos ->
                    val slot = size.width.toFloat() / data.size
                    val i = (pos.x / slot).toInt().coerceIn(0, data.lastIndex)
                    onSelect(if (selected == i) null else i)
                }
            }
            .pointerInput(data.size) {
                detectHorizontalDragGestures { change, _ ->
                    val slot = size.width.toFloat() / data.size
                    onSelect((change.position.x / slot).toInt().coerceIn(0, data.lastIndex))
                }
            },
    ) {
        if (data.isEmpty()) return@Canvas
        val labelH = 18.dp.toPx()
        val chartH = size.height - labelH
        val slot = size.width / data.size
        val barW = min(slot * 0.62f, 14.dp.toPx())
        val radius = CornerRadius(barW / 2.2f, barW / 2.2f)

        // grid: three hairlines
        for (k in 0..2) {
            val y = chartH * k / 2f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }

        data.forEachIndexed { i, d ->
            val frac = (d.value / axisMax).toFloat().coerceIn(0f, 1f) * progress
            val h = max(frac * chartH, if (d.value > 0) 3.dp.toPx() else 0f)
            val x = slot * i + (slot - barW) / 2f
            val isSel = selected == i
            val dim = selected != null && !isSel
            val top = if (isSel) accent else accent.copy(alpha = if (dim) 0.28f else 0.8f)
            val bottom = accent.copy(alpha = if (isSel) 0.55f else if (dim) 0.08f else 0.22f)
            if (d.value > 0) {
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(top, bottom), startY = chartH - h, endY = chartH),
                    topLeft = Offset(x, chartH - h),
                    size = Size(barW, h),
                    cornerRadius = radius,
                )
            } else {
                drawRoundRect(grid, Offset(x, chartH - 2.dp.toPx()), Size(barW, 2.dp.toPx()), CornerRadius(1f, 1f))
            }
            if (i % labelEvery == 0 || i == data.lastIndex && false) {
                val r = measurer.measure(d.label, labelStyle)
                drawText(r, topLeft = Offset(slot * i + slot / 2f - r.size.width / 2f, chartH + 4.dp.toPx()))
            }
        }

        average?.takeIf { it > 0 }?.let { avg ->
            val y = chartH - (avg / axisMax).toFloat() * chartH * progress
            drawLine(
                muted.copy(alpha = 0.55f), Offset(0f, y), Offset(size.width, y),
                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
            )
        }
    }
}

data class DonutSlice(val label: String, val value: Double, val color: Color)

/**
 * Donut chart. Arcs sweep in on first draw; the slice at [selected] thickens. [center] is drawn in the hole.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    selected: Int?,
    modifier: Modifier = Modifier,
    size: Dp = 176.dp,
    stroke: Dp = 22.dp,
    /** When set, tapping a slice selects it (tapping it again, or the hole, clears). */
    onSelect: ((Int?) -> Unit)? = null,
    center: @Composable () -> Unit = {},
) {
    val progress = rememberEntranceProgress(key = slices.size, millis = 900)
    val track = MaterialTheme.spend.hairline
    val total = slices.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
    val selAnim by animateFloatAsState(selected?.toFloat() ?: -1f, tween(220), label = "donutSel")

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.size(size).then(
                if (onSelect == null) Modifier else Modifier.pointerInput(slices) {
                    detectTapGestures { pos ->
                        val half = this.size.width / 2f
                        val hit = donutHit(pos.x - half, pos.y - half, slices.map { it.value }, half * 0.45f, half)
                        onSelect(if (hit == selected) null else hit)
                    }
                },
            ),
        ) {
            val base = stroke.toPx()
            val inset = base / 2f + 6.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(base * 0.6f))
            if (slices.isEmpty()) return@Canvas
            val gap = if (slices.size > 1) 3.2f else 0f
            var start = -90f
            slices.forEachIndexed { i, s ->
                val sweepFull = (s.value / total * 360f).toFloat()
                val sweep = (sweepFull - gap).coerceAtLeast(0.5f) * progress
                val emphasis = (1f - kotlin.math.abs(selAnim - i)).coerceIn(0f, 1f)
                val dim = selected != null && selected != i
                drawArc(
                    color = s.color.copy(alpha = s.color.alpha * if (dim) 0.35f else 1f),
                    startAngle = start + gap / 2f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(base * (1f + 0.18f * emphasis), cap = StrokeCap.Butt),
                )
                start += sweepFull
            }
        }
        center()
    }
}

/**
 * Canvas bar chart with one bar per [slices] entry, each in its own colour. Tapping a bar selects it
 * (tapping again clears). Bars grow in on first draw; labels are clipped to the bar slot.
 */
@Composable
fun CategoryBarChart(
    slices: List<DonutSlice>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 188.dp,
) {
    val progress = rememberEntranceProgress(key = slices.map { it.value }, millis = 800)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.spend.hairline
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val selAnim by animateFloatAsState(selected?.toFloat() ?: -1f, tween(200), label = "catBarSel")
    val axisMax = max(slices.maxOfOrNull { it.value } ?: 0.0, 1.0) * 1.1

    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(slices.size, selected) {
            detectTapGestures { pos ->
                val i = slotAt(pos.x, size.width.toFloat(), slices.size)
                onSelect(if (i == selected) null else i)
            }
        },
    ) {
        if (slices.isEmpty()) return@Canvas
        val labelH = 20.dp.toPx()
        val chartH = size.height - labelH
        val slot = size.width / slices.size
        val barW = min(slot * 0.56f, 44.dp.toPx())
        for (k in 0..2) drawLine(grid, Offset(0f, chartH * k / 2f), Offset(size.width, chartH * k / 2f), strokeWidth = 1.dp.toPx())
        slices.forEachIndexed { i, s ->
            val emphasis = (1f - kotlin.math.abs(selAnim - i)).coerceIn(0f, 1f)
            val dim = selected != null && selected != i
            val h = max((s.value / axisMax).toFloat().coerceIn(0f, 1f) * chartH * progress, if (s.value > 0) 3.dp.toPx() else 0f)
            val w = barW * (1f + 0.14f * emphasis)
            val x = slot * i + (slot - w) / 2f
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(s.color.copy(alpha = s.color.alpha * if (dim) 0.35f else 1f), s.color.copy(alpha = s.color.alpha * if (dim) 0.1f else 0.35f)),
                    startY = chartH - h, endY = chartH,
                ),
                topLeft = Offset(x, chartH - h), size = Size(w, h), cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
            val r = measurer.measure(
                s.label, labelStyle.copy(color = if (selected == i) fg else muted.copy(alpha = if (dim) 0.5f else 1f)),
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = (slot - 4.dp.toPx()).toInt().coerceAtLeast(1)),
            )
            drawText(r, topLeft = Offset(slot * i + slot / 2f - r.size.width / 2f, chartH + 5.dp.toPx()))
        }
    }
}

/**
 * Canvas line chart of [values] (one point per day, index 0 = day 1) with a soft area fill. The line draws in
 * left to right on first display; tapping or dragging selects a day ([selected] / [onSelect]).
 */
@Composable
fun LineChart(
    values: List<Double>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 188.dp,
    labelEvery: Int = 7,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val progress = rememberEntranceProgress(key = values, millis = 1000)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.spend.hairline
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = muted)
    val axisMax = max(values.maxOrNull() ?: 0.0, 1.0) * 1.15

    Canvas(
        modifier.fillMaxWidth().height(height)
            .pointerInput(values.size, selected) {
                detectTapGestures { pos ->
                    val i = slotAt(pos.x, size.width.toFloat(), values.size)
                    onSelect(if (i == selected) null else i)
                }
            }
            .pointerInput(values.size) {
                detectHorizontalDragGestures { change, _ -> onSelect(slotAt(change.position.x, size.width.toFloat(), values.size)) }
            },
    ) {
        if (values.isEmpty()) return@Canvas
        val labelH = 20.dp.toPx()
        val chartH = size.height - labelH - 8.dp.toPx()
        val top = 8.dp.toPx()
        val slot = size.width / values.size
        for (k in 0..2) drawLine(grid, Offset(0f, top + chartH * k / 2f), Offset(size.width, top + chartH * k / 2f), strokeWidth = 1.dp.toPx())
        fun px(i: Int) = slot * i + slot / 2f
        fun py(i: Int) = top + chartH - (values[i] / axisMax).toFloat().coerceIn(0f, 1f) * chartH

        val line = androidx.compose.ui.graphics.Path().apply {
            moveTo(px(0), py(0))
            for (i in 1 until values.size) {
                val mx = (px(i - 1) + px(i)) / 2f
                cubicTo(mx, py(i - 1), mx, py(i), px(i), py(i))
            }
        }
        val area = androidx.compose.ui.graphics.Path().apply {
            addPath(line); lineTo(px(values.lastIndex), top + chartH); lineTo(px(0), top + chartH); close()
        }
        clipRect(right = size.width * progress) {
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0f)), startY = top, endY = top + chartH))
            drawPath(line, accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        selected?.takeIf { it in values.indices }?.let { i ->
            drawLine(
                muted.copy(alpha = 0.55f), Offset(px(i), top), Offset(px(i), top + chartH),
                strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
            drawCircle(accent.copy(alpha = 0.25f), 9.dp.toPx(), Offset(px(i), py(i)))
            drawCircle(accent, 4.5.dp.toPx(), Offset(px(i), py(i)))
        }
        values.indices.forEach { i ->
            if (i % labelEvery == 0) {
                val r = measurer.measure((i + 1).toString(), labelStyle)
                drawText(r, topLeft = Offset((px(i) - r.size.width / 2f).coerceIn(0f, size.width - r.size.width), size.height - labelH + 5.dp.toPx()))
            }
        }
    }
}

/** Slim horizontal progress bar used in ranked lists. */
@Composable
fun RankBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val progress = rememberEntranceProgress(key = fraction, millis = 700, delay = 150)
    val track = MaterialTheme.spend.hairline
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(track, size = size, cornerRadius = r)
        val w = size.width * fraction.coerceIn(0f, 1f) * progress
        if (w > 0f) drawRoundRect(color, size = Size(max(w, size.height), size.height), cornerRadius = r)
    }
}

/** Two-segment stacked bar: [mine] of [total], remainder shown in [other]. */
@Composable
fun StackedShareBar(mine: Double, other: Double, mineColor: Color, otherColor: Color, modifier: Modifier = Modifier) {
    val progress = rememberEntranceProgress(key = mine + other, millis = 900, delay = 250)
    val track = MaterialTheme.spend.hairline
    val total = (mine + other).takeIf { it > 0 } ?: 1.0
    val m = remember(mine, other) { (mine / total).toFloat() }
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        val r = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(track, size = size, cornerRadius = r)
        val full = size.width * progress
        val gap = 3.dp.toPx()
        val mw = full * m
        if (mw > 0f) drawRoundRect(mineColor, size = Size(max(mw - if (other > 0) gap / 2 else 0f, size.height), size.height), cornerRadius = r)
        val ow = full - mw
        if (other > 0 && ow > 0f) {
            drawRoundRect(otherColor, topLeft = Offset(mw + gap / 2, 0f), size = Size(max(ow - gap / 2, size.height / 2), size.height), cornerRadius = r)
        }
    }
}

/** One segment of a [StackedBar]: relative [weight] and its [color]. */
data class BarSegment(val weight: Double, val color: Color)

/** Flat NeoPOP stacked bar: square segments separated by thin gaps, drawn in once. Default 4dp tall. */
@Composable
fun StackedBar(segments: List<BarSegment>, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val progress = rememberEntranceProgress(key = segments.map { it.weight }, millis = 700, delay = 150)
    val track = MaterialTheme.spend.hairline
    val total = segments.sumOf { it.weight }
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(track, size = size)
        if (total <= 0.0) return@Canvas
        val gap = 2.dp.toPx()
        val usable = size.width - gap * (segments.size - 1).coerceAtLeast(0)
        var x = 0f
        segments.forEach { s ->
            val w = (usable * (s.weight / total)).toFloat() * progress
            if (w > 0f) drawRect(s.color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += (usable * (s.weight / total)).toFloat() + gap
        }
    }
}

@Suppress("unused")
private val keepStyle = TextStyle.Default
