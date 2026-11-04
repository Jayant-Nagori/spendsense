package com.jayant.spendsense.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.orders.StoredOrder

/** Itemised receipt for a payment matched to a Swiggy / Zomato / Zepto order. Plain text only (untrusted data). */
@Composable
fun OrderReceipt(order: StoredOrder, modifier: Modifier = Modifier, maxItems: Int = 12) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    SpendCard(modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Text(
            "ORDER  ·  ${order.source.label.uppercase()}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            order.merchant ?: order.source.label, style = MaterialTheme.typography.titleMedium, color = fg,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
        )
        Text(formatDateTime(order.placedAt), style = MaterialTheme.typography.bodySmall, color = grey)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            order.items.take(maxItems).forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text("${item.quantity}×", style = MaterialTheme.typography.bodyMedium, color = grey, modifier = Modifier.width(36.dp))
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    item.price?.let {
                        Text(formatRupees(it), style = MaterialTheme.typography.bodyMedium, color = grey, textAlign = TextAlign.End, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
            if (order.items.size > maxItems) {
                Text("+${order.items.size - maxItems} more", style = MaterialTheme.typography.bodySmall, color = grey)
            }
            if (order.items.isEmpty()) Text("Items weren't shared for this order.", style = MaterialTheme.typography.bodySmall, color = grey)
        }
        HairlineDivider(Modifier.padding(vertical = 12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ORDER TOTAL", style = MaterialTheme.typography.labelSmall, color = grey, modifier = Modifier.weight(1f))
            MoneyText(order.total, style = MaterialTheme.typography.titleSmall, color = fg, symbolScale = 0.8f)
        }
    }
}
