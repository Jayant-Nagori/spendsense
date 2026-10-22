package com.jayant.spendsense.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.PaymentMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryPicker(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SpendCategories.forEach { c ->
            val color = categoryColor(c)
            SpendChip(
                label = c, selected = selected == c, onClick = { onSelect(c) }, accent = color,
                leading = { Icon(categoryIcon(c), null, tint = color, modifier = Modifier.size(16.dp)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentModePicker(selected: PaymentMode?, onSelect: (PaymentMode) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaymentModePickerOrder.forEach { m -> SpendChip(m.label(), selected == m, { onSelect(m) }) }
    }
}

/** Section label above a form control (sentence case, quiet). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun SpendTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) },
        singleLine = singleLine, minLines = minLines, shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    )
}
