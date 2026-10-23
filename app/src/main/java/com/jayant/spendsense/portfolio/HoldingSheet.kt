package com.jayant.spendsense.portfolio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.NeoPopSurface
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.spend

/** Square text field: 1dp hairline border, white when focused, near-square corners. */
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
        shape = RoundedCornerShape(2.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            unfocusedBorderColor = MaterialTheme.spend.hairline.copy(alpha = 0.22f),
            focusedBorderColor = c.onSurface,
            errorBorderColor = c.error,
            focusedLabelColor = c.onSurface,
            unfocusedLabelColor = c.onSurfaceVariant,
            cursorColor = c.onSurface,
            focusedPlaceholderColor = c.outline,
            unfocusedPlaceholderColor = c.outline,
        ),
    )
}

@Composable
private fun SheetLabel(text: String) {
    SectionLabel(text, Modifier.padding(top = 14.dp))
}

/** Square NeoPOP toggle: selected = white face / black text, unselected = black face / hairline. */
@Composable
private fun SquareToggle(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    NeoPopSurface(
        color = if (selected) c.onBackground else c.background,
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        depth = 3.dp,
        edge = if (selected) Color(0xFF8C8C8C) else Color(0xFF3A3A3A),
        border = if (selected) null else Color.White.copy(alpha = 0.22f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.background else c.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
        )
    }
}

private val AMOUNT_KEYS = KeyboardOptions(keyboardType = KeyboardType.Decimal)

@Composable
private fun SheetDragHandle() {
    Box(
        Modifier.padding(vertical = 12.dp).width(32.dp).height(3.dp)
            .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
    )
}

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
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { SheetDragHandle() },
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
                style = MaterialTheme.typography.displaySmall,
            )

            SheetLabel("What is it")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 3,
            ) {
                AssetType.values().forEach { t ->
                    SquareToggle(
                        text = t.label,
                        selected = form.type == t,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            change({ it.copy(type = t) })
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            SheetLabel("Details")
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SheetLabel("Recurring")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SquareToggle(
                    "One-time", selected = !form.isSip,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOff)
                        change({ it.copy(isSip = false) }, FormField.SIP)
                    },
                    modifier = Modifier.weight(1f),
                )
                SquareToggle(
                    "Monthly SIP", selected = form.isSip,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        change({ it.copy(isSip = true) }, FormField.SIP)
                    },
                    modifier = Modifier.weight(1f),
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
                text = "Save",
                onClick = {
                    when (val r = form.validate(System.currentTimeMillis())) {
                        is FormResult.Valid -> onSave(r.investment)
                        is FormResult.Invalid -> {
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                            errors = r.errors
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            if (isEdit && onDelete != null) {
                GhostButton(
                    text = "Delete holding",
                    onClick = { confirmDelete = true },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (confirmDelete && initial != null && onDelete != null) {
        BasicAlertDialog(onDismissRequest = { confirmDelete = false }) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.border(1.dp, MaterialTheme.spend.hairline, RoundedCornerShape(4.dp)),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Delete holding?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "\"${initial.name}\" will be removed from your wealth. This can't be undone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GhostButton("Cancel", { confirmDelete = false }, Modifier.weight(1f))
                        PrimaryButton("Delete", { confirmDelete = false; onDelete(initial) }, Modifier.weight(1f))
                    }
                }
            }
        }
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
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = { SheetDragHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Update value", style = MaterialTheme.typography.displaySmall)
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
                text = "Save",
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
