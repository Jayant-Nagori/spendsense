package com.jayant.spendsense.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.AnswerTag
import com.jayant.spendsense.ui.components.CategoryBadge
import com.jayant.spendsense.ui.components.CategoryPicker
import com.jayant.spendsense.ui.components.FieldLabel
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.MoneyText
import com.jayant.spendsense.ui.components.PaymentModePicker
import com.jayant.spendsense.ui.components.Pill
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.SpendCard
import com.jayant.spendsense.ui.components.SpendTextField
import com.jayant.spendsense.ui.components.SplitEditor
import com.jayant.spendsense.ui.components.formatDateTime
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.rememberSplitState
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Detail + edit sheet for one transaction: category, note, payee, mode, split, raw message, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailSheet(txn: Transaction, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDebit = txn.type == TxnType.DEBIT

    var counterparty by remember(txn.id) { mutableStateOf(txn.counterparty.orEmpty()) }
    var category by remember(txn.id) { mutableStateOf(txn.category) }
    var note by remember(txn.id) { mutableStateOf(txn.note.orEmpty()) }
    var mode by remember(txn.id) { mutableStateOf<PaymentMode?>(txn.paymentMode) }
    var showRaw by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val split = rememberSplitState(txn.amount, txn.isSplit, txn.myShare, key = txn.id)

    fun close(action: suspend () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { action() }
            sheetState.hide()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CategoryBadge(category, size = 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        txn.counterparty?.takeIf { it.isNotBlank() } ?: if (isDebit) "Payment" else "Money received",
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(formatDateTime(txn.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Pill(
                    if (isDebit) "DEBIT" else "CREDIT",
                    container = if (isDebit) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.spend.credit.copy(alpha = 0.14f),
                    content = if (isDebit) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.spend.credit,
                )
            }

            Column {
                MoneyText(txn.amount, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.5f)
                if (isDebit && txn.isSplit && txn.myShare != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your share ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoneyText(txn.effectiveAmount, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.8f)
                    }
                }
            }

            if (isDebit) {
                SpendCard(contentPadding = 16.dp) {
                    if (!txn.reviewed) {
                        AnswerTag(text = "NOT ANSWERED YET")
                        Spacer(Modifier.height(12.dp))
                    }
                    SplitEditor(split)
                }
            }

            SpendTextField(counterparty, { counterparty = it }, if (isDebit) "Paid to" else "Received from")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Category")
                CategoryPicker(category, { category = it })
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Paid with")
                PaymentModePicker(mode, { mode = it })
            }
            SpendTextField(note, { note = it }, "Note", singleLine = false, minLines = 2)

            SpendCard(contentPadding = 16.dp, modifier = Modifier.animateContentSize()) {
                Row(Modifier.fillMaxWidth().pressable(role = null) { showRaw = !showRaw }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Original message", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            sourceLabel(txn), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(if (showRaw) "HIDE" else "SHOW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                if (showRaw) {
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            txn.rawText.ifBlank { "No message stored for manual entries." },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    txn.upiRef?.let { Text("Ref $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp)) }
                }
            }

            PrimaryButton("Save changes", enabled = !isDebit || split.isValid, modifier = Modifier.fillMaxWidth(), onClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
                val repo = TransactionRepository.get(context)
                close {
                    val fresh = repo.get(txn.id) ?: txn
                    repo.update(
                        fresh.copy(
                            counterparty = counterparty.trim().ifBlank { null },
                            category = category,
                            note = note.trim().ifBlank { null },
                            paymentMode = mode,
                            isSplit = if (isDebit) split.isSplit else false,
                            myShare = if (isDebit) split.myShare else null,
                            reviewed = fresh.reviewed || !isDebit || split.touched,
                        ),
                    )
                }
            })
            GhostButton("Delete transaction", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth(), onClick = { confirmDelete = true })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this transaction?") },
            text = { Text("It will be removed from your spending totals. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    close { TransactionRepository.get(context).delete(txn.id) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

private fun sourceLabel(t: Transaction): String = when (t.source) {
    TxnSource.SMS -> "SMS" + (t.sourceApp?.let { " from $it" } ?: "")
    TxnSource.NOTIFICATION -> "Notification" + (t.sourceApp?.let { " from $it" } ?: "")
    TxnSource.MANUAL -> "Added by you"
}
