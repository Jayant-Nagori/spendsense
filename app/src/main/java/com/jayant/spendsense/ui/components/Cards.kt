package com.jayant.spendsense.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.theme.spend

/** Standard 24dp surface card with a hairline border. Optional [onClick] adds press-scale. */
@Composable
fun SpendCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: Dp = 20.dp,
    brush: Brush? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = modifier
        .clip(shape)
        .let { if (brush != null) it.background(brush) else it.background(color) }
        .border(BorderStroke(1.dp, MaterialTheme.spend.hairline), shape)
        .let { if (onClick != null) it.pressable(onClick = onClick, role = null) else it }
    Column(m.padding(contentPadding), content = content)
}

/** Title row above a section: serif headline + optional trailing action text. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Small filled pill label (status, count, delta). */
@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

/** Selectable chip: accent-tinted when selected. 40dp visual inside a 48dp touch target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable (() -> Unit)? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
) {
    val bg by animateColorAsState(if (selected) accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh, label = "chipBg")
    val fg by animateColorAsState(if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant, label = "chipFg")
    val borderC by animateColorAsState(if (selected) accent.copy(alpha = 0.5f) else MaterialTheme.spend.hairline, label = "chipBorder")
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .pressable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, borderC, CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            leading?.invoke()
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
    }
}

/** Neutral icon tile for a category. Colour is reserved for charts and money semantics, so tiles stay quiet. */
@Composable
fun CategoryBadge(category: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.34f))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.spend.hairline, RoundedCornerShape(size * 0.34f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(category), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.48f))
    }
}

/** Small letter-spaced label for stat captions and card eyebrows. Used sparingly. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.W700),
        color = color,
        maxLines = 1,
    )
}

/** One transaction row, shared by dashboard and transactions list. */
@Composable
fun TransactionRow(txn: Transaction, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false) {
    val isCredit = txn.type == TxnType.CREDIT
    val spoken = buildString {
        append(txn.counterparty?.takeIf { it.isNotBlank() } ?: if (isCredit) "Money received" else "Payment")
        append(", ").append(formatRupees(txn.amount)).append(if (isCredit) " received" else " paid")
        if (txn.isSplit && txn.myShare != null) append(", your share ").append(formatRupees(txn.effectiveAmount))
        if (!isCredit) append(", ").append(txn.category)
        append(", ").append(formatDateTime(txn.timestamp))
        if (!isCredit && !txn.reviewed) append(", needs your answer")
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .pressable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isCredit) {
            val green = MaterialTheme.spend.credit
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(green.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CallReceived, "Credit", tint = green, modifier = Modifier.size(22.dp))
            }
        } else CategoryBadge(txn.category)
        Column(Modifier.weight(1f)) {
            Text(
                txn.counterparty?.takeIf { it.isNotBlank() } ?: if (isCredit) "Money received" else "Payment",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                add(if (showDate) formatDateTime(txn.timestamp) else formatTime(txn.timestamp))
                txn.paymentMode?.let { add(it.label()) }
                if (!isCredit) add(txn.category)
            }.joinToString("  ·  ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (!isCredit && txn.isSplit && txn.myShare != null) {
                SignedMoney(txn.effectiveAmount, false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("of ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(txn.amount, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, symbolScale = 0.9f)
                }
            } else {
                SignedMoney(txn.amount, isCredit)
                if (!isCredit && !txn.reviewed) {
                    Text("Needs answer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Hairline horizontal divider that respects the theme. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.spend.hairline))
}

/** Drawn empty-state art: a torn receipt with a saffron coin. Pure Canvas, follows the theme. */
@Composable
fun ReceiptIllustration(modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val paper = MaterialTheme.colorScheme.surfaceContainerHigh
    val edge = MaterialTheme.spend.hairline.copy(alpha = 0.18f)
    val accent = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.spend.accent2
    Canvas(modifier.size(width = 168.dp, height = 132.dp)) {
        val w = size.width; val h = size.height
        val rw = w * 0.52f; val rx = w * 0.14f; val top = h * 0.06f; val bottom = h * 0.94f
        val tooth = rw / 7f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(rx, top)
            lineTo(rx + rw, top)
            lineTo(rx + rw, bottom - tooth * 0.8f)
            var x = rx + rw
            var up = false
            while (x > rx + 0.1f) {
                x = (x - tooth / 2f).coerceAtLeast(rx)
                lineTo(x, if (up) bottom - tooth * 0.8f else bottom)
                up = !up
            }
            lineTo(rx, bottom - tooth * 0.8f)
            close()
        }
        drawPath(path, paper)
        drawPath(path, edge, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        // text lines
        val lx = rx + rw * 0.14f
        val widths = listOf(0.62f, 0.44f, 0.7f, 0.36f)
        widths.forEachIndexed { i, f ->
            val y = top + h * (0.18f + i * 0.13f)
            drawLine(line, Offset(lx, y), Offset(lx + rw * f, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        // dashed total rule
        drawLine(
            line.copy(alpha = 0.4f), Offset(lx, top + h * 0.72f), Offset(rx + rw * 0.86f, top + h * 0.72f),
            strokeWidth = 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
        )
        // coin
        val c = Offset(w * 0.7f, h * 0.58f); val r = h * 0.27f
        drawCircle(accent.copy(alpha = 0.16f), r * 1.35f, c)
        drawCircle(accent, r, c)
        drawCircle(Color.Black.copy(alpha = 0.18f), r * 0.78f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        val g = androidx.compose.ui.graphics.Path().apply {
            moveTo(c.x - r * 0.32f, c.y - r * 0.38f); lineTo(c.x + r * 0.34f, c.y - r * 0.38f)
            moveTo(c.x - r * 0.32f, c.y - r * 0.12f); lineTo(c.x + r * 0.34f, c.y - r * 0.12f)
            moveTo(c.x - r * 0.22f, c.y - r * 0.38f)
            cubicTo(c.x + r * 0.5f, c.y - r * 0.38f, c.x + r * 0.5f, c.y + r * 0.26f, c.x - r * 0.22f, c.y + r * 0.26f)
            lineTo(c.x + r * 0.28f, c.y + r * 0.62f)
        }
        drawPath(g, Color(0xFF1B1200), style = androidx.compose.ui.graphics.drawscope.Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        // spark
        drawCircle(accent2, 3.dp.toPx(), Offset(w * 0.9f, h * 0.18f))
        drawCircle(accent2.copy(alpha = 0.5f), 2.dp.toPx(), Offset(w * 0.08f, h * 0.7f))
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, illustration: Boolean = true) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.Start) {
        if (illustration) {
            ReceiptIllustration()
            Spacer(Modifier.height(24.dp))
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Soft moving highlight for loading skeletons. Honors the system animation scale. */
fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shimmerX")
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    drawWithCache {
        val w = size.width
        val brush = Brush.linearGradient(
            colors = listOf(base, hi, base),
            start = Offset(w * x, 0f), end = Offset(w * (x + 0.6f), size.height),
        )
        onDrawBehind { drawRect(brush) }
    }
}

@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(modifier.height(height).clip(shape).shimmer())
}

/** Row-shaped placeholder matching [TransactionRow] proportions. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SkeletonBlock(Modifier.size(44.dp), 44.dp, RoundedCornerShape(15.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth(0.55f), 14.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.35f), 10.dp)
        }
        SkeletonBlock(Modifier.size(width = 56.dp, height = 14.dp), 14.dp)
    }
}

