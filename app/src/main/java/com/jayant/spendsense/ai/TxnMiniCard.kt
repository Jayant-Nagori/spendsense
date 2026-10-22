package com.jayant.spendsense.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType

/** Compact transaction card shown under an assistant answer. Theme colors only. */
@Composable
fun TxnMiniCard(t: Transaction, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    t.counterparty ?: "Unknown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detailLine(t),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val credit = t.type == TxnType.CREDIT
                Text(
                    (if (credit) "+" else "") + Money.inr(t.amount),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (credit) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
                if (t.isSplit && t.myShare != null) {
                    Text(
                        "your share ${Money.inr(t.myShare)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun detailLine(t: Transaction): String = buildList {
    add(Ist.card(t.timestamp))
    t.paymentMode?.let { add(it.name) }
    t.account?.takeIf { it.isNotBlank() }?.let { add(it.takeLast(6)) }
    if (t.category.isNotBlank() && t.category != "Other") add(t.category)
}.joinToString(" · ")
