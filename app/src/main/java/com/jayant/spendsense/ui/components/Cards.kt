package com.jayant.spendsense.ui.components

import androidx.compose.animation.animateColorAsState
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

/** Selectable chip: accent-filled when selected. 40dp tall for a comfortable hit area. */
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
    Row(
        modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, borderC, CircleShape)
            .pressable(haptic = false, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Round tinted icon tile used for categories. Radius is concentric with its parent row (16dp tile). */
@Composable
fun CategoryBadge(category: String, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val c = categoryColor(category)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.34f)).background(c.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(categoryIcon(category), contentDescription = category, tint = c, modifier = Modifier.size(size * 0.5f))
    }
}

/** One transaction row, shared by dashboard and transactions list. */
@Composable
fun TransactionRow(txn: Transaction, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false) {
    val isCredit = txn.type == TxnType.CREDIT
    Row(
        modifier.fillMaxWidth().pressable(role = null, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
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

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Suppress("unused")
private val keep = Pair(Modifier.animateContentSize(), Modifier.width(0.dp))
