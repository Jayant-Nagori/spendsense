package com.jayant.spendsense.ui.components

import com.jayant.spendsense.ioDispatcher

import com.jayant.spendsense.platformContext

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.RefundStatus
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Refund flow for one transaction. Debits: "I returned this" → refund expected → auto-matched when the refund
 * credit arrives (or marked received by hand). Refund credits: shows which purchase it belongs to, with an undo.
 * [onAction] runs a blocking repository call off the main thread and closes the host sheet.
 */
@Composable
fun RefundCard(txn: Transaction, onAction: (suspend (TransactionRepository) -> Unit) -> Unit) {
    if (txn.type == TxnType.CREDIT) {
        if (txn.refundOfId == null) return
        RefundCreditCard(txn, onAction)
        return
    }
    SpendCard(contentPadding = 16.dp) {
        when (txn.refundStatus) {
            RefundStatus.NONE -> ReturnPrompt(txn, onAction)
            RefundStatus.EXPECTED -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnswerTag(text = "REFUND EXPECTED · ${formatRupees(txn.refundAmount ?: txn.amount)}")
                Text(
                    "We'll match it automatically when the refund SMS or email arrives.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("Got it already", modifier = Modifier.weight(1f), onClick = {
                        onAction { it.update(txn.copy(refundStatus = RefundStatus.RECEIVED)) }
                    })
                    GhostButton("Cancel return", modifier = Modifier.weight(1f), onClick = { onAction { it.cancelReturn(txn.id) } })
                }
            }
            RefundStatus.RECEIVED -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("REFUNDED · ${formatRupees(txn.refundAmount ?: 0.0)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.spend.credit)
                Text(
                    "This now counts as ${formatRupees(txn.effectiveAmount)} of your spend.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReturnPrompt(txn: Transaction, onAction: (suspend (TransactionRepository) -> Unit) -> Unit) {
    var open by remember(txn.id) { mutableStateOf(false) }
    var full by remember(txn.id) { mutableStateOf(true) }
    var partial by remember(txn.id) { mutableStateOf("") }
    val partialAmount = partial.replace(",", "").toDoubleOrNull()
    val valid = full || (partialAmount != null && partialAmount > 0 && partialAmount <= txn.amount)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Returned this?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("Track the refund so it comes off your spend", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!open) {
            GhostButton("I returned this", modifier = Modifier.fillMaxWidth(), onClick = { open = true })
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpendChip("Full ${formatRupeesWhole(txn.amount)}", selected = full, onClick = { full = true })
                    SpendChip("Partial", selected = !full, onClick = { full = false })
                }
                if (!full) SpendTextField(partial, { v -> partial = v.filter { it.isDigit() || it == '.' || it == ',' } }, "Refund amount")
                PrimaryButton("Mark as returned", enabled = valid, modifier = Modifier.fillMaxWidth(), onClick = {
                    val amt = if (full) txn.amount else partialAmount
                    onAction { it.markReturned(txn.id, amt) }
                })
            }
        }
    }
}

@Composable
private fun RefundCreditCard(txn: Transaction, onAction: (suspend (TransactionRepository) -> Unit) -> Unit) {
    val context = platformContext()
    var purchase by remember(txn.id) { mutableStateOf<Transaction?>(null) }
    LaunchedEffect(txn.refundOfId) {
        purchase = withContext(ioDispatcher) { txn.refundOfId?.let { TransactionRepository.get(context).get(it) } }
    }
    SpendCard(contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("REFUND · NOT COUNTED AS INCOME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.spend.credit)
            purchase?.let { p ->
                Text(
                    "For your ${formatRupees(p.amount)} payment to ${p.counterparty ?: "a merchant"} on ${formatDateTime(p.timestamp)}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                )
            }
            GhostButton("Not a refund", modifier = Modifier.fillMaxWidth(), onClick = { onAction { it.unlinkRefund(txn.id) } })
        }
    }
}
