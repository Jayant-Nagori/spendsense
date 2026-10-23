package com.jayant.spendsense.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
 * "This was fully mine" square checkbox row; unticked reveals the share input, ½ ⅓ ¼ CUSTOM toggles,
 * a "split with N people" stepper and the live "Others owe you" result.
 */
@Composable
fun SplitEditor(state: SplitState, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    val fg = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .pressable(role = androidx.compose.ui.semantics.Role.Checkbox) { state.fullyMine = !state.fullyMine; state.touched = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SquareCheck(state.fullyMine)
            Column(Modifier.weight(1f)) {
                Text("This was fully mine", style = MaterialTheme.typography.titleSmall, color = fg)
                Text(
                    if (state.fullyMine) "The whole amount counts as your spend" else "Only your share counts as your spend",
                    style = MaterialTheme.typography.bodySmall, color = grey,
                )
            }
        }

        AnimatedVisibility(
            visible = !state.fullyMine,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FieldLabel("My share")
                OutlinedTextField(
                    value = state.shareText,
                    onValueChange = { v ->
                        if (v.count { it == '.' } <= 1 && v.all { it.isDigit() || it == '.' } && v.length <= 12) {
                            state.shareText = v; state.people = 0; state.touched = true
                        }
                    },
                    placeholder = { Text("Amount you owe, e.g. ${SplitState.plain(state.total / 2)}") },
                    prefix = { Text("₹ ", color = grey) },
                    singleLine = true,
                    isError = state.share != null && state.share!! > state.total + 0.004,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = MaterialTheme.shapes.small,
                    colors = neoFieldColors(),
                    textStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpendChip("½", state.people == 2, { state.applyPeople(2) }, Modifier.weight(1f))
                    SpendChip("⅓", state.people == 3, { state.applyPeople(3) }, Modifier.weight(1f))
                    SpendChip("¼", state.people == 4, { state.applyPeople(4) }, Modifier.weight(1f))
                    SpendChip(
                        "CUSTOM", state.people == 0 && state.shareText.isNotEmpty(),
                        { state.people = 0; state.touched = true; runCatching { focus.requestFocus() } },
                        Modifier.weight(1.6f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Split with", style = MaterialTheme.typography.bodyMedium, color = grey)
                    Spacer(Modifier.width(12.dp))
                    SquareIconButton(Icons.Rounded.Remove, "Fewer people", { state.applyPeople(if (state.people == 0) 2 else state.people - 1) }, enabled = state.people > 2, size = 36.dp)
                    Text(
                        if (state.people == 0) "–" else state.people.toString(),
                        style = MaterialTheme.typography.titleMedium, color = fg,
                        textAlign = TextAlign.Center, modifier = Modifier.width(36.dp),
                    )
                    SquareIconButton(Icons.Rounded.Add, "More people", { state.applyPeople(if (state.people == 0) 2 else state.people + 1) }, enabled = state.people < 20, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("people", style = MaterialTheme.typography.bodyMedium, color = grey)
                }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, MaterialTheme.spend.hairline, MaterialTheme.shapes.small)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("OTHERS OWE YOU", style = MaterialTheme.typography.labelSmall, color = grey, modifier = Modifier.weight(1f))
                    if (state.isValid) {
                        MoneyText(state.othersOwe, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.spend.credit, decimals = MoneyDecimals.AUTO)
                    } else if (state.share == null) {
                        Text("Pick ½ ⅓ ¼ or type your share", style = MaterialTheme.typography.bodySmall, color = grey)
                    } else {
                        Text("Share can't exceed total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** "You paid ₹254 to Swiggy · 12 Oct, 9:41 pm" */
fun splitHeadline(txn: Transaction): String {
    val who = txn.counterparty?.takeIf { it.isNotBlank() }
    return "You paid ${formatRupees(txn.amount)}" + (who?.let { " to $it" } ?: "") + "  ·  " + formatDateTime(txn.timestamp)
}

/**
 * Whole split prompt body (headline, big TOTAL, editor, SAVE). Used by SplitActivity and the in-app bottom sheet.
 * [onSave] receives (isSplit, myShare).
 */
@Composable
fun SplitContent(
    txn: Transaction,
    onSave: (isSplit: Boolean, myShare: Double?) -> Unit,
    modifier: Modifier = Modifier,
    saveLabel: String = "Save",
    startAsSplit: Boolean = false,
    /** When set, also asks "What was it for?" and reports the text here just before [onSave]. */
    onNote: ((String) -> Unit)? = null,
) {
    val state = rememberSplitState(txn.amount, txn.isSplit || (startAsSplit && !txn.reviewed), txn.myShare, key = txn.id)
    var note by remember(txn.id) { mutableStateOf(txn.note.orEmpty()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Overline("Total")
            MoneyText(txn.amount, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.5f)
            Text(splitHeadline(txn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onNote != null) SpendTextField(note, { note = it.take(200) }, "What was it for?")
        HairlineDivider()
        SplitEditor(state)
        PrimaryButton(saveLabel, onClick = { onNote?.invoke(note); onSave(state.isSplit, state.myShare) }, enabled = state.isValid, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
    }
}
