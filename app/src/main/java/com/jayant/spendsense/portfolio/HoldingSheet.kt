package com.jayant.spendsense.portfolio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.vector.ImageVector
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


/** Rounded, filled text field in the sheet's quiet style: no heavy outline until focused. */
@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    readOnly: Boolean = false,
    isError: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val c = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        placeholder = placeholder,
        prefix = prefix,
        suffix = suffix,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        singleLine = singleLine,
        readOnly = readOnly,
        isError = isError,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surfaceContainerHigh,
            unfocusedContainerColor = c.surfaceContainerHigh,
            errorContainerColor = c.surfaceContainerHigh,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedBorderColor = c.primary,
            errorBorderColor = c.error,
            focusedLabelColor = c.primary,
            cursorColor = c.primary,
        ),
    )
}

@Composable
private fun SheetLabel(text: String) {
    SectionLabel(text, Modifier.padding(top = 10.dp))
}

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
    val haptics = LocalHapticFeedback.current

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

            SheetLabel("What is it")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AssetType.values().forEach { t ->
                    val chipSource = remember { MutableInteractionSource() }
                    FilterChip(
                        selected = form.type == t,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            change({ it.copy(type = t) })
                        },
                        label = { Text(t.label) },
                        leadingIcon = { Icon(assetIcon(t), null, Modifier.size(18.dp)) },
                        interactionSource = chipSource,
                        shape = CircleShape,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = form.type == t,
                            borderColor = MaterialTheme.colorScheme.outlineVariant,
                            selectedBorderColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.pressScale(chipSource, 0.95f),
                    )
                }
            }

            SheetField(
                value = form.name,
                onValueChange = { v -> change({ it.copy(name = v) }, FormField.NAME) },
                label = { Text("Name") },
                placeholder = { Text("e.g. Parag Parikh Flexi Cap") },
                singleLine = true,
                isError = FormField.NAME in errors,
                supportingText = errors[FormField.NAME]?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )
            SheetField(
                value = form.platform,
                onValueChange = { v -> change({ it.copy(platform = v) }) },
                label = { Text("Platform / bank (optional)") },
                placeholder = { Text("e.g. Zerodha, Groww, SBI") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SheetLabel("Amounts")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetField(
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
                SheetField(
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
            SheetField(
                value = form.units,
                onValueChange = { v -> change({ it.copy(units = v) }, FormField.UNITS) },
                label = { Text("Units / quantity (optional)") },
                singleLine = true,
                keyboardOptions = AMOUNT_KEYS,
                isError = FormField.UNITS in errors,
                supportingText = errors[FormField.UNITS]?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            SheetLabel("Dates and interest")
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
                        SheetField(
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

            SheetLabel("Recurring")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SIP / recurring investment", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "I invest a fixed amount regularly",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = form.isSip,
                    onCheckedChange = { v ->
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        change({ it.copy(isSip = v) }, FormField.SIP)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
            AnimatedVisibility(visible = form.isSip, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                SheetField(
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

            SheetLabel("Notes")
            SheetField(
                value = form.notes,
                onValueChange = { v -> change({ it.copy(notes = v) }) },
                label = { Text("Notes (optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            PrimaryButton(
                text = if (isEdit) "Save changes" else "Add holding",
                onClick = {
                    when (val r = form.validate(System.currentTimeMillis())) {
                        is FormResult.Valid -> onSave(r.investment)
                        is FormResult.Invalid -> {
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                            errors = r.errors
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            if (isEdit && onDelete != null) {
                QuietButton(
                    text = "Delete holding",
                    onClick = { confirmDelete = true },
                    icon = Icons.Rounded.Delete,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
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
        SheetField(
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
    val haptics = LocalHapticFeedback.current
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
            SheetField(
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
            PrimaryButton(
                text = "Update",
                onClick = {
                    val (v, e) = validateNewValue(text)
                    if (v != null) onSave(v) else {
                        haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        error = e
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
