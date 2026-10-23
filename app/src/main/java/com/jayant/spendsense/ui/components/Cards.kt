package com.jayant.spendsense.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.theme.spend

/**
 * Flat 4dp surface (#121212) with a hairline border. No gradient, no shadow. Optional [onClick] adds press-scale.
 * [brush] is kept for source compatibility and ignored: NeoPOP surfaces are flat.
 */
@Composable
fun SpendCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: Dp = 16.dp,
    @Suppress("UNUSED_PARAMETER") brush: Brush? = null,
    borderColor: Color = MaterialTheme.spend.hairline,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = modifier
        .clip(shape)
        .background(color)
        .border(BorderStroke(1.dp, borderColor), shape)
        .let { if (onClick != null) it.pressable(onClick = onClick, role = null) else it }
    Column(m.padding(contentPadding), content = content)
}

/** Section label: uppercase, small, grey, wide tracking. Optional trailing slot (count, action). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1,
        )
        trailing?.invoke()
    }
}

/** Small square tag (status, count). */
@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.clip(MaterialTheme.shapes.small).background(container).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1)
    }
}

/** Lime "ANSWER" tag: the only place the pop colour appears inside a list. */
@Composable
fun AnswerTag(modifier: Modifier = Modifier, text: String = "ANSWER") {
    Text(
        text, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
        maxLines = 1,
        modifier = modifier.clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Neutral square icon tile for a category (40dp, 2dp radius, monochrome icon). */
@Composable
fun CategoryBadge(category: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    IconTile(categoryIcon(category), modifier, size)
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val shape = MaterialTheme.shapes.small
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.spend.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Small letter-spaced label for stat captions. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/** One flat transaction row, shared by Home and Activity: 40dp tile, name + one meta line, amount right. */
@Composable
fun TransactionRow(txn: Transaction, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false) {
    val isCredit = txn.type == TxnType.CREDIT
    val pending = !isCredit && !txn.reviewed
    val spoken = buildString {
        append(txn.counterparty?.takeIf { it.isNotBlank() } ?: if (isCredit) "Money received" else "Payment")
        append(", ").append(formatRupees(txn.amount)).append(if (isCredit) " received" else " paid")
        if (txn.isSplit && txn.myShare != null) append(", your share ").append(formatRupees(txn.effectiveAmount))
        if (!isCredit) append(", ").append(txn.category)
        append(", ").append(formatDateTime(txn.timestamp))
        if (pending) append(", needs your answer")
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            .pressable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isCredit) IconTile(Icons.Rounded.CallReceived, tint = MaterialTheme.spend.credit) else CategoryBadge(txn.category)
        Column(Modifier.weight(1f)) {
            Text(
                txn.counterparty?.takeIf { it.isNotBlank() } ?: if (isCredit) "Money received" else "Payment",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                add(if (showDate) formatDateTime(txn.timestamp) else formatTime(txn.timestamp))
                if (!isCredit) add(txn.category) else txn.paymentMode?.let { add(it.label()) }
            }.joinToString("  ·  ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!isCredit && txn.isSplit && txn.myShare != null) {
                SignedMoney(txn.effectiveAmount, false)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("of ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MoneyText(txn.amount, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, symbolScale = 0.9f)
                }
            } else {
                SignedMoney(txn.amount, isCredit)
                if (pending) AnswerTag()
            }
        }
    }
}

/** Hairline horizontal divider that respects the theme. */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.spend.hairline))
}

/** Flat empty state: serif headline and one grey line. No illustration. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, @Suppress("UNUSED_PARAMETER") illustration: Boolean = false) {
    Column(modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.Start) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Soft moving highlight for loading skeletons. */
fun Modifier.shimmer(): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "shimmerX")
    val base = MaterialTheme.colorScheme.surfaceContainer
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
fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp, shape: Shape = MaterialTheme.shapes.small) {
    Box(modifier.height(height).clip(shape).shimmer())
}

/** Row-shaped placeholder matching [TransactionRow] proportions. */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SkeletonBlock(Modifier.size(40.dp), 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBlock(Modifier.fillMaxWidth(0.55f), 14.dp)
            SkeletonBlock(Modifier.fillMaxWidth(0.35f), 10.dp)
        }
        SkeletonBlock(Modifier.size(width = 56.dp, height = 14.dp), 14.dp)
    }
}
