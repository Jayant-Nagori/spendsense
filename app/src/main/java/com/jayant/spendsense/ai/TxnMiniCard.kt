package com.jayant.spendsense.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.categoryIcon
import com.jayant.spendsense.ui.theme.spend

/** Flat transaction row shown under an assistant answer: square icon tile, name + meta, amount. */
@Composable
fun TxnMiniCard(t: Transaction, modifier: Modifier = Modifier) {
    val line = MaterialTheme.spend.hairline
    Row(
        modifier.fillMaxWidth()
            .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(categoryIcon(t.category), null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                t.counterparty ?: "Unknown",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detailLine(t),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val credit = t.type == TxnType.CREDIT
            Text(
                (if (credit) "+" else "") + Money.inr(t.amount),
                style = MaterialTheme.typography.titleSmall,
                color = if (credit) MaterialTheme.spend.credit else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (t.isSplit && t.myShare != null) {
                Text(
                    "your share ${Money.inr(t.myShare)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun detailLine(t: Transaction): String = buildList {
    add(Ist.card(t.timestamp))
    t.paymentMode?.let { add(it.name) }
    if (t.category.isNotBlank() && t.category != "Other") add(t.category)
}.joinToString(" · ")
