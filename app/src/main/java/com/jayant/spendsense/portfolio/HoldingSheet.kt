package com.jayant.spendsense.portfolio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment

private val AMOUNT_KEYS = KeyboardOptions(keyboardType = KeyboardType.Decimal)

/** Add (id == 0) or edit a holding. [onSave] receives a validated Investment. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HoldingSheet(
    initial: Investment?,
    onDismiss: () -> Unit,
    onSave: (Investment) -> Unit,
    onDelete: ((Investment) -> Unit)? = null,
) {
    val now = remember { System.currentTimeMillis() }
    var form by remember { mutableStateOf(initial?.let { HoldingForm.from(it) } ?: HoldingForm()) }
    var errors by remember { mutableStateOf<Map<FormField, String>>(emptyMap()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isEdit = initial != null && initial.id != 0L
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun change(block: (HoldingForm) -> HoldingForm, clear: FormField? = null) {
        form = block(form)
        if (clear != null && clear in errors) errors = errors - clear
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (isEdit) "Edit holding" else "Add holding",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )

            Text("Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                AssetType.values().forEach { t ->
                    FilterChip(
                        selected = form.type == t,
                        onClick = { change({ it.copy(type = t) }) },
                        label = { Text(t.label) },
                        leadingIcon = { Icon(assetIcon(t), null, Modifier.size(18.dp)) },
                    )
                }
            }

            OutlinedTextField(
                value = form.name,
                onValueChange = { v -> change({ it.copy(name = v) }, FormField.NAME) },
                label = { Text("Name") },
                placeholder = { Text("e.g. Parag Parikh Flexi Cap") },
                singleLine = true,
                isError = FormField.NAME in errors,
                supportingText = errors[FormField.NAME]?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.platform,
                onValueChange = { v -> change({ it.copy(platform = v) }) },
                label = { Text("Platform / bank (optional)") },
                placeholder = { Text("e.g. Zerodha, Groww, SBI") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.invested,
                    onValueChange = { v -> change({ it.copy(invested = v) }, FormField.INVESTED) },
                    label = { Text("Invested") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    keyboardOptions = AMOUNT_KEYS,
                    isError = FormField.INVESTED in errors,
                    supportingText = errors[FormField.INVESTED]?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.current,
                    onValueChange = { v -> change({ it.copy(current = v) }, FormField.CURRENT) },
                    label = { Text("Current value") },
                    prefix = { Text("₹") },
                    singleLine = true,
                    keyboardOptions = AMOUNT_KEYS,
                    isError = FormField.CURRENT in errors,
                    supportingText = errors[FormField.CURRENT]?.let { { Text(it) } }
                        ?: { Text("Optional") },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = form.units,
                onValueChange = { v -> change({ it.copy(units = v) }, FormField.UNITS) },
                label = { Text("Units / quantity (optional)") },
                singleLine = true,
                keyboardOptions = AMOUNT_KEYS,
                isError = FormField.UNITS in errors,
                supportingText = errors[FormField.UNITS]?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            DateField("Start date", form.startDate, Modifier.fillMaxWidth(),
                onChange = { d -> change({ it.copy(startDate = d) }, FormField.DATES) })

            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AnimatedVisibility(
                    visible = form.showsMaturity,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        DateField("Maturity date", form.maturityDate, Modifier.fillMaxWidth(),
                            error = errors[FormField.DATES],
                            onChange = { d -> change({ it.copy(maturityDate = d) }, FormField.DATES) })
                        OutlinedTextField(
                            value = form.rate,
                            onValueChange = { v -> change({ it.copy(rate = v) }, FormField.RATE) },
                            label = { Text("Interest rate") },
                            suffix = { Text("% p.a.") },
                            singleLine = true,
                            keyboardOptions = AMOUNT_KEYS,
                            isError = FormField.RATE in errors,
                            supportingText = errors[FormField.RATE]?.let { { Text(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // Live estimate hint for FD / RD / Bond when current value is blank.
                val estimate = if (form.current.isBlank() && form.showsMaturity) form.estimate(now) else null
                AnimatedVisibility(visible = estimate != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Text(
                        "Estimated current value: ${formatInr(estimate ?: 0.0)} (saved when you leave current value blank)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SIP / recurring investment", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "I invest a fixed amount regularly",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = form.isSip, onCheckedChange = { v -> change({ it.copy(isSip = v) }, FormField.SIP) })
            }
            AnimatedVisibility(visible = form.isSip, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                OutlinedTextField(
                    value = form.sipAmount,
                    onValueChange = { v -> change({ it.copy(sipAmount = v) }, FormField.SIP) },
                    label = { Text("SIP amount") },
                    prefix = { Text("₹") },
                    suffix = { Text("/ month") },
                    singleLine = true,
                    keyboardOptions = AMOUNT_KEYS,
                    isError = FormField.SIP in errors,
                    supportingText = errors[FormField.SIP]?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> change({ it.copy(notes = v) }) },
                label = { Text("Notes (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    when (val r = form.validate(System.currentTimeMillis())) {
                        is FormResult.Valid -> onSave(r.investment)
                        is FormResult.Invalid -> errors = r.errors
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (isEdit) "Save changes" else "Add holding", fontWeight = FontWeight.SemiBold) }

            if (isEdit && onDelete != null) {
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Delete holding", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete && initial != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete holding?") },
            text = { Text("\"${initial.name}\" will be removed from your wealth. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(initial) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Read-only date field that opens a Material date picker; clear icon when set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    millis: Long?,
    modifier: Modifier = Modifier,
    error: String? = null,
    onChange: (Long?) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = millis?.let { formatDate(it) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text("Select date") },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = {
                if (millis != null) {
                    IconButton(onClick = { onChange(null) }) { Icon(Icons.Rounded.Close, "Clear $label") }
                } else {
                    Icon(Icons.Rounded.CalendarMonth, null)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent click target over the text area (leaves the clear button usable).
        Box(
            Modifier
                .matchParentSize()
                .padding(end = if (millis != null) 56.dp else 0.dp)
                .clickable { open = true },
        )
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = millis?.let { localMillisToUtcPicker(it) })
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(utcPickerToLocalMillis(it)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** Quick "update current value" sheet (e.g. refresh an MF or stock NAV). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateValueSheet(
    holding: Investment,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(plainNumber(holding.valueNow)) }
    var error by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Update value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "${holding.name} · invested ${formatInr(holding.investedAmount)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                label = { Text("Current value") },
                prefix = { Text("₹") },
                singleLine = true,
                keyboardOptions = AMOUNT_KEYS,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val (v, e) = validateNewValue(text)
                    if (v != null) onSave(v) else error = e
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Update", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
