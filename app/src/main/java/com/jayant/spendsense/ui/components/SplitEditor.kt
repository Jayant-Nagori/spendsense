package com.jayant.spendsense.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.ui.theme.spend
import kotlin.math.roundToLong

/** Hoisted state for the split control. Create with [rememberSplitState]. */
@Stable
class SplitState(total: Double, fullyMine: Boolean = true, share: Double? = null) {
    var total by mutableDoubleStateOf(total)
        private set
    var fullyMine by mutableStateOf(fullyMine)
    var shareText by mutableStateOf(share?.let { plain(it) } ?: "")
    /** 0 = custom amount, otherwise "split evenly with N people". */
    var people by mutableIntStateOf(0)
    /** True once the user changed anything; lets callers avoid marking a transaction answered by accident. */
    var touched by mutableStateOf(false)

    val share: Double? get() = shareText.replace(",", "").toDoubleOrNull()
    val isValid: Boolean get() = fullyMine || (share != null && share!! >= 0.0 && share!! <= total + 0.004)
    val isSplit: Boolean get() = !fullyMine
    val myShare: Double? get() = if (fullyMine) null else share?.coerceIn(0.0, total)
    val othersOwe: Double get() = (total - (share ?: 0.0)).coerceAtLeast(0.0)

    fun updateTotal(newTotal: Double) {
        total = newTotal
        if (people > 0) applyPeople(people)
    }

    fun applyPeople(n: Int) {
        people = n.coerceIn(2, 20)
        shareText = plain((total / people * 100).roundToLong() / 100.0)
        touched = true
    }

    companion object {
        fun plain(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(java.util.Locale.US, v)
    }
}

@Composable
fun rememberSplitState(total: Double, isSplit: Boolean = false, myShare: Double? = null, key: Any? = null): SplitState =
    remember(key) { SplitState(total, fullyMine = !isSplit, share = myShare) }

/**
 * "This was fully mine" checkbox; unchecked reveals "My share" with 1/2, 1/3, 1/4, Custom chips,
 * a "split with N people" stepper and the live "Others owe you" result.
 */
@Composable
fun SplitEditor(state: SplitState, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().pressable(role = null) { state.fullyMine = !state.fullyMine; state.touched = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.fullyMine,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                    uncheckedColor = MaterialTheme.colorScheme.outline,
                ),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text("This was fully mine", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (state.fullyMine) "The whole amount counts as your spend" else "Only your share counts as your spend",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(
            visible = !state.fullyMine,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = state.shareText,
                    onValueChange = { v ->
                        if (v.count { it == '.' } <= 1 && v.all { it.isDigit() || it == '.' } && v.length <= 12) {
                            state.shareText = v; state.people = 0; state.touched = true
                        }
                    },
                    label = { Text("My share") },
                    prefix = { Text("₹ ", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    isError = state.share != null && state.share!! > state.total + 0.004,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpendChip("1/2", state.people == 2, { state.applyPeople(2) })
                    SpendChip("1/3", state.people == 3, { state.applyPeople(3) })
                    SpendChip("1/4", state.people == 4, { state.applyPeople(4) })
                    SpendChip("Custom", state.people == 0, { state.people = 0; state.touched = true; runCatching { focus.requestFocus() } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Split with", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Stepper(Icons.Rounded.Remove, "Fewer people", enabled = state.people > 2) { state.applyPeople(if (state.people == 0) 2 else state.people - 1) }
                    Text(
                        if (state.people == 0) "–" else state.people.toString(),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center, modifier = Modifier.width(34.dp),
                    )
                    Stepper(Icons.Rounded.Add, "More people", enabled = state.people < 20) { state.applyPeople(if (state.people == 0) 2 else state.people + 1) }
                    Spacer(Modifier.width(10.dp))
                    Text("people", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.spend.credit.copy(alpha = 0.10f)).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Others owe you", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    if (state.isValid) {
                        MoneyText(state.othersOwe, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.spend.credit, animate = true, decimals = MoneyDecimals.AUTO)
                    } else {
                        Text("Share can't exceed total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.spend.hairline, CircleShape)
            .pressable(enabled = enabled, haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}

/** "You paid ₹254 to Swiggy · 12 Oct, 9:41 pm" */
fun splitHeadline(txn: Transaction): String {
    val who = txn.counterparty?.takeIf { it.isNotBlank() }
    return "You paid ${formatRupees(txn.amount)}" + (who?.let { " to $it" } ?: "") + "  ·  " + formatDateTime(txn.timestamp)
}

/**
 * Whole split prompt body (headline, TOTAL, editor, save). Used by SplitActivity and the in-app bottom sheet.
 * [onSave] receives (isSplit, myShare).
 */
@Composable
fun SplitContent(
    txn: Transaction,
    onSave: (isSplit: Boolean, myShare: Double?) -> Unit,
    modifier: Modifier = Modifier,
    saveLabel: String = "Save",
) {
    val state = rememberSplitState(txn.amount, txn.isSplit, txn.myShare, key = txn.id)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(splitHeadline(txn), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text("Total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(txn.amount, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.5f)
        }
        SplitEditor(state)
        PrimaryButton(saveLabel, onClick = { onSave(state.isSplit, state.myShare) }, enabled = state.isValid, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
    }
}
