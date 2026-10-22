package com.jayant.spendsense.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.CategoryPicker
import com.jayant.spendsense.ui.components.FieldLabel
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PaymentModePicker
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.SpendCard
import com.jayant.spendsense.ui.components.SpendChip
import com.jayant.spendsense.ui.components.SpendTextField
import com.jayant.spendsense.ui.components.SplitEditor
import com.jayant.spendsense.ui.components.formatDayHeader
import com.jayant.spendsense.ui.components.formatTime
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.rememberSplitState
import com.jayant.spendsense.ui.theme.SerifFamily
import com.jayant.spendsense.ui.theme.SansFamily
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

/**
 * Inserts a manual entry. insertIfNew dedupes same amount+type within 10 minutes, which would wrongly reject
 * two genuine cash payments of the same amount. With [force] we nudge the timestamp just outside the duplicate's
 * window (<= 10 min) so both rows are kept.
 */
internal fun insertManual(repo: TransactionRepository, txn: Transaction, force: Boolean): Transaction? {
    repo.insertIfNew(txn)?.let { return it }
    if (!force) return null
    var t = txn
    val window = 10 * 60 * 1000L + 1000L
    repeat(8) {
        val dup = repo.findDuplicate(t) ?: return repo.insertIfNew(t)
        t = t.copy(timestamp = if (t.timestamp >= dup.timestamp) dup.timestamp + window else dup.timestamp - window)
        repo.insertIfNew(t)?.let { return it }
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntrySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var isDebit by remember { mutableStateOf(true) }
    var amountText by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf<PaymentMode?>(PaymentMode.CASH) }
    var category by remember { mutableStateOf("Other") }
    var note by remember { mutableStateOf("") }
    var whenMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var duplicateWarning by remember { mutableStateOf(false) }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val split = rememberSplitState(amount)
    LaunchedEffect(amount) { split.updateTotal(amount) }

    fun save(force: Boolean) {
        val txn = Transaction(
            amount = amount,
            type = if (isDebit) TxnType.DEBIT else TxnType.CREDIT,
            counterparty = payee.trim().ifBlank { null },
            account = null, bank = null, upiRef = null,
            source = TxnSource.MANUAL, sourceApp = null,
            rawText = "",
            timestamp = whenMillis,
            category = category,
            isSplit = isDebit && split.isSplit,
            myShare = if (isDebit) split.myShare else null,
            reviewed = true,
            note = note.trim().ifBlank { null },
            paymentMode = mode,
        )
        scope.launch {
            val stored = withContext(Dispatchers.IO) { insertManual(TransactionRepository.get(context), txn, force) }
            if (stored == null) {
                duplicateWarning = true
            } else {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                sheetState.hide()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Add a transaction", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpendChip("Paid", isDebit, { isDebit = true; duplicateWarning = false })
                SpendChip("Received", !isDebit, { isDebit = false; duplicateWarning = false }, accent = MaterialTheme.spend.credit)
            }

            // Big serif amount entry
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "₹", style = TextStyle(fontFamily = SansFamily, fontSize = 28.sp(), color = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(bottom = 10.dp, end = 6.dp),
                )
                BasicTextField(
                    value = amountText,
                    onValueChange = { v ->
                        if (v.count { it == '.' } <= 1 && v.all { it.isDigit() || it == '.' } && v.length <= 10) {
                            amountText = v; duplicateWarning = false
                        }
                    },
                    textStyle = MaterialTheme.typography.displayLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Amount in rupees" },
                    decorationBox = { inner ->
                        Box {
                            if (amountText.isEmpty()) {
                                Text("0", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                            }
                            inner()
                        }
                    },
                )
            }

            SpendTextField(payee, { payee = it }, if (isDebit) "Paid to" else "Received from")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Paid with")
                PaymentModePicker(mode, { mode = it })
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Category")
                CategoryPicker(category, { category = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateTimeButton(formatDayHeader(whenMillis), "Change date", Modifier.weight(1f)) { showDate = true }
                DateTimeButton(formatTime(whenMillis), "Change time", Modifier.weight(1f)) { showTime = true }
            }

            SpendTextField(note, { note = it }, "Note", singleLine = false, minLines = 2)

            AnimatedVisibility(visible = isDebit) {
                SpendCard(contentPadding = 16.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    SplitEditor(split)
                }
            }

            AnimatedVisibility(visible = duplicateWarning) {
                SpendCard(contentPadding = 16.dp, color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) {
                    Text("Looks like this was already recorded", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "A payment of the same amount was saved within 10 minutes of this time.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("Discard", onDismiss, Modifier.weight(1f))
                        PrimaryButton("Add anyway", { save(force = true) }, Modifier.weight(1f))
                    }
                }
            }

            PrimaryButton(
                "Save", { save(force = false) }, Modifier.fillMaxWidth(),
                enabled = amount > 0 && (!isDebit || split.isValid) && !duplicateWarning,
            )
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = localToUtcDay(whenMillis))
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { whenMillis = combineDay(it, whenMillis) }
                    showDate = false; duplicateWarning = false
                }) { Text("Set date") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (showTime) {
        val cal = Calendar.getInstance().apply { timeInMillis = whenMillis }
        val state = rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = {
                    whenMillis = Calendar.getInstance().apply {
                        timeInMillis = whenMillis
                        set(Calendar.HOUR_OF_DAY, state.hour); set(Calendar.MINUTE, state.minute)
                    }.timeInMillis
                    showTime = false; duplicateWarning = false
                }) { Text("Set time") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun DateTimeButton(text: String, desc: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.spend.hairline, MaterialTheme.shapes.medium)
            .pressable(onClick = onClick)
            .semantics { contentDescription = "$desc, currently $text" }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun localToUtcDay(local: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = local }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/** Keeps the time-of-day of [old] and takes the calendar date from [utcDay] (DatePicker returns UTC midnight). */
private fun combineDay(utcDay: Long, old: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcDay }
    return Calendar.getInstance().apply {
        timeInMillis = old
        set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
