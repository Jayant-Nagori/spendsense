package com.jayant.spendsense.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.EmptyState
import com.jayant.spendsense.ui.components.HairlineDivider
import com.jayant.spendsense.ui.components.MoneyText
import com.jayant.spendsense.ui.components.SpendCategories
import com.jayant.spendsense.ui.components.SpendChip
import com.jayant.spendsense.ui.components.TransactionRow
import com.jayant.spendsense.ui.components.PaymentModePickerOrder
import com.jayant.spendsense.ui.components.addMonths
import com.jayant.spendsense.ui.components.categoryColor
import com.jayant.spendsense.ui.components.formatDayHeader
import com.jayant.spendsense.ui.components.formatRupeesCompact
import com.jayant.spendsense.ui.components.label
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.staggeredEntrance
import com.jayant.spendsense.ui.components.startOfDay
import com.jayant.spendsense.ui.components.startOfMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

private enum class DatePreset(val label: String) {
    ANY("Any time"), THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_90("Last 90 days"), THIS_YEAR("This year"), CUSTOM("Custom range")
}

private data class Filters(
    val query: String = "",
    val type: TxnType? = null,
    val category: String? = null,
    val mode: PaymentMode? = null,
    val preset: DatePreset = DatePreset.ANY,
    val customFrom: Long? = null,
    val customTo: Long? = null,
)

private fun Filters.dateBounds(): Pair<Long, Long>? {
    val now = System.currentTimeMillis()
    return when (preset) {
        DatePreset.ANY -> null
        DatePreset.THIS_MONTH -> startOfMonth(now) to Long.MAX_VALUE
        DatePreset.LAST_MONTH -> addMonths(startOfMonth(now), -1) to startOfMonth(now)
        DatePreset.LAST_90 -> startOfDay(now - 90L * 86_400_000) to Long.MAX_VALUE
        DatePreset.THIS_YEAR -> Calendar.getInstance().apply {
            timeInMillis = startOfDay(now); set(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis to Long.MAX_VALUE
        DatePreset.CUSTOM -> if (customFrom != null && customTo != null) customFrom to (customTo + 86_400_000L) else null
    }
}

private fun Filters.isActive() = query.isNotBlank() || type != null || category != null || mode != null || preset != DatePreset.ANY

private fun matches(t: Transaction, f: Filters, bounds: Pair<Long, Long>?): Boolean {
    if (f.type != null && t.type != f.type) return false
    if (f.category != null && t.category != f.category) return false
    if (f.mode != null && t.paymentMode != f.mode) return false
    if (bounds != null && (t.timestamp < bounds.first || t.timestamp >= bounds.second)) return false
    val q = f.query.trim()
    if (q.isNotEmpty()) {
        val hay = listOfNotNull(t.counterparty, t.note, t.category, t.bank, t.rawText, t.paymentMode?.label(), t.amount.toLong().toString(), "%.2f".format(t.amount))
        if (hay.none { it.contains(q, ignoreCase = true) }) return false
    }
    return true
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen() {
    val context = LocalContext.current
    val repo = remember { TransactionRepository.get(context) }
    val changes by repo.changes.collectAsState()
    var all by remember { mutableStateOf<List<Transaction>?>(null) }
    var filters by remember { mutableStateOf(Filters()) }
    var detail by remember { mutableStateOf<Transaction?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }

    LaunchedEffect(changes) { all = withContext(Dispatchers.IO) { repo.all() } }

    val bounds = filters.dateBounds()
    val filtered by remember(all, filters) {
        derivedStateOf { all.orEmpty().filter { matches(it, filters, bounds) } }
    }
    val grouped by remember(filtered) { derivedStateOf { filtered.groupBy { startOfDay(it.timestamp) } } }
    val spent = filtered.filter { it.type == TxnType.DEBIT }.sumOf { it.effectiveAmount }
    val received = filtered.filter { it.type == TxnType.CREDIT }.sumOf { it.amount }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 112.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Text("Transactions", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (all == null) " " else "${filtered.size} shown  ·  ${formatRupeesCompact(spent)} spent  ·  ${formatRupeesCompact(received)} in",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedTextField(
                        value = filters.query,
                        onValueChange = { filters = filters.copy(query = it) },
                        placeholder = { Text("Search payee, note, amount") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (filters.query.isNotEmpty()) {
                                Box(Modifier.size(40.dp).clip(CircleShape).pressable(role = null) { filters = filters.copy(query = "") }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Close, "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = CircleShape,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item(key = "filters") {
                FilterBar(
                    filters, onChange = { filters = it }, onCustomRange = { showRange = true },
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                )
            }

            if (all == null) {
                // loading: keep empty, the list appears in one beat
            } else if (filtered.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        if (filters.isActive()) "No matches" else "No transactions yet",
                        if (filters.isActive()) "Try clearing a filter or searching for something else."
                        else "New payments appear here automatically. Tap Add to record a cash payment.",
                    )
                }
            } else {
                grouped.forEach { (day, list) ->
                    stickyHeader(key = "day-$day") {
                        DayHeader(day, list)
                    }
                    items(list.size, key = { list[it].id }) { i ->
                        val t = list[i]
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            TransactionRow(t, onClick = { detail = t })
                        }
                    }
                }
            }
        }

        AddButton(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp)) { showAdd = true }
    }

    detail?.let { TransactionDetailSheet(it, onDismiss = { detail = null }) }
    if (showAdd) ManualEntrySheet(onDismiss = { showAdd = false })

    if (showRange) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showRange = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                    onClick = {
                        val from = utcDayToLocalStart(state.selectedStartDateMillis!!)
                        val to = utcDayToLocalStart(state.selectedEndDateMillis!!)
                        filters = filters.copy(preset = DatePreset.CUSTOM, customFrom = from, customTo = to)
                        showRange = false
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { showRange = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state, modifier = Modifier.height(520.dp))
        }
    }
}

private fun utcDayToLocalStart(utcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
    return Calendar.getInstance().apply {
        clear(); set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

@Composable
private fun DayHeader(day: Long, list: List<Transaction>) {
    val spent = list.filter { it.type == TxnType.DEBIT }.sumOf { it.effectiveAmount }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatDayHeader(day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        if (spent > 0) {
            MoneyText(spent, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, symbolScale = 0.9f)
        }
    }
}

@Composable
private fun FilterBar(filters: Filters, onChange: (Filters) -> Unit, onCustomRange: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpendChip("All", filters.type == null, { onChange(filters.copy(type = null)) })
        SpendChip("Spent", filters.type == TxnType.DEBIT, { onChange(filters.copy(type = if (filters.type == TxnType.DEBIT) null else TxnType.DEBIT)) })
        SpendChip("Received", filters.type == TxnType.CREDIT, { onChange(filters.copy(type = if (filters.type == TxnType.CREDIT) null else TxnType.CREDIT)) })

        MenuChip(filters.category ?: "Category", filters.category != null) { dismiss ->
            DropdownMenuItem(text = { Text("All categories") }, onClick = { onChange(filters.copy(category = null)); dismiss() })
            SpendCategories.forEach { c ->
                DropdownMenuItem(
                    text = { Text(c, color = categoryColor(c)) },
                    onClick = { onChange(filters.copy(category = c)); dismiss() },
                )
            }
        }
        MenuChip(filters.mode?.label() ?: "Paid with", filters.mode != null) { dismiss ->
            DropdownMenuItem(text = { Text("Any method") }, onClick = { onChange(filters.copy(mode = null)); dismiss() })
            PaymentModePickerOrder.forEach { m ->
                DropdownMenuItem(text = { Text(m.label()) }, onClick = { onChange(filters.copy(mode = m)); dismiss() })
            }
        }
        MenuChip(
            when (filters.preset) {
                DatePreset.CUSTOM -> "Custom dates"
                DatePreset.ANY -> "Date"
                else -> filters.preset.label
            },
            filters.preset != DatePreset.ANY,
        ) { dismiss ->
            DatePreset.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.label) },
                    onClick = {
                        dismiss()
                        if (p == DatePreset.CUSTOM) onCustomRange() else onChange(filters.copy(preset = p))
                    },
                )
            }
        }
        if (filters.isActive()) {
            SpendChip("Clear", false, { onChange(Filters()) })
        }
    }
}

@Composable
private fun MenuChip(label: String, active: Boolean, items: @Composable (dismiss: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SpendChip(
            label = label, selected = active, onClick = { open = true },
            leading = null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
            items { open = false }
        }
    }
}

@Composable
private fun AddButton(modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .pressable(haptic = true, onClick = onClick)
            .padding(start = 18.dp, end = 22.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        Text("Add", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
    }
}
