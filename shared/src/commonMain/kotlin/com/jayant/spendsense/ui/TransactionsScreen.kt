package com.jayant.spendsense.ui

import com.jayant.spendsense.formatFixed

import com.jayant.spendsense.ioDispatcher

import com.jayant.spendsense.currentTimeMillis

import com.jayant.spendsense.platformContext

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.AddFab
import com.jayant.spendsense.ui.components.EmptyState
import com.jayant.spendsense.ui.components.FieldLabel
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.HairlineDivider
import com.jayant.spendsense.ui.components.MoneyText
import com.jayant.spendsense.ui.components.PaymentModePickerOrder
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.SkeletonRow
import com.jayant.spendsense.ui.components.SpendCategories
import com.jayant.spendsense.ui.components.SpendChip
import com.jayant.spendsense.ui.components.SquareIconButton
import com.jayant.spendsense.ui.components.TransactionRow
import com.jayant.spendsense.ui.components.addMonths
import com.jayant.spendsense.ui.components.formatDayHeader
import com.jayant.spendsense.ui.components.formatRupeesCompact
import com.jayant.spendsense.ui.components.formatRupeesWhole
import com.jayant.spendsense.ui.components.label
import com.jayant.spendsense.ui.components.neoFieldColors
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.startOfDay
import com.jayant.spendsense.ui.components.startOfMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jayant.spendsense.time.atStartOfDayMillis

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
    val minAmount: Double? = null,
    val maxAmount: Double? = null,
    val paidForOthers: Boolean = false,
)

private val AMOUNT_PRESETS = listOf(500.0, 1_000.0, 5_000.0, 10_000.0)

private fun Filters.dateBounds(): Pair<Long, Long>? {
    val now = currentTimeMillis()
    return when (preset) {
        DatePreset.ANY -> null
        DatePreset.THIS_MONTH -> startOfMonth(now) to Long.MAX_VALUE
        DatePreset.LAST_MONTH -> addMonths(startOfMonth(now), -1) to startOfMonth(now)
        DatePreset.LAST_90 -> startOfDay(now - 90L * 86_400_000) to Long.MAX_VALUE
        DatePreset.THIS_YEAR -> com.jayant.spendsense.time.localDateOf(now, kotlinx.datetime.TimeZone.currentSystemDefault()).let {
            kotlinx.datetime.LocalDate(it.year, 1, 1).atStartOfDayMillis(kotlinx.datetime.TimeZone.currentSystemDefault())
        } to Long.MAX_VALUE
        DatePreset.CUSTOM -> if (customFrom != null && customTo != null) customFrom to (customTo + 86_400_000L) else null
    }
}

private fun Filters.isActive() = query.isNotBlank() || type != null || category != null || mode != null || preset != DatePreset.ANY ||
    minAmount != null || maxAmount != null || paidForOthers

private fun matches(t: Transaction, f: Filters, bounds: Pair<Long, Long>?): Boolean {
    if (f.type != null && t.type != f.type) return false
    if (f.category != null && t.category != f.category) return false
    if (f.mode != null && t.paymentMode != f.mode) return false
    if (f.paidForOthers && !t.paidForOthers) return false
    if (f.minAmount != null && t.amount < f.minAmount) return false
    if (f.maxAmount != null && t.amount > f.maxAmount) return false
    if (bounds != null && (t.timestamp < bounds.first || t.timestamp >= bounds.second)) return false
    val q = f.query.trim()
    if (q.isNotEmpty()) {
        val hay = listOfNotNull(t.counterparty, t.note, t.category, t.bank, t.rawText, t.paymentMode?.label(), t.paidFor, t.amount.toLong().toString(), formatFixed(t.amount, 2))
        if (hay.none { it.contains(q, ignoreCase = true) }) return false
    }
    return true
}

private fun Filters.advancedCount() = listOf(category != null, mode != null, preset != DatePreset.ANY, minAmount != null || maxAmount != null).count { it }

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(onBack: (() -> Unit)? = null, accountKey: String? = null) {
    val context = platformContext()
    val repo = remember { TransactionRepository.get(context) }
    val changes by repo.changes.collectAsState()
    var all by remember { mutableStateOf<List<Transaction>?>(null) }
    var filters by remember { mutableStateOf(Filters()) }
    var detail by remember { mutableStateOf<Transaction?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showRange by remember { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }

    var accountLabel by remember(accountKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(changes, accountKey) {
        all = withContext(ioDispatcher) {
            val rows = repo.all()
            if (accountKey == null) rows else {
                val accounts = com.jayant.spendsense.data.AccountRepository.get(context)
                val prefs = runCatching { accounts.prefs() }.getOrDefault(emptyMap())
                accountLabel = prefs[accountKey]?.nickname ?: com.jayant.spendsense.data.Accounts.labelForKey(accountKey)
                rows.filter { com.jayant.spendsense.data.AccountRepository.belongsTo(it, accountKey, prefs) }
            }
        }
    }

    val bounds = filters.dateBounds()
    val filtered by remember(all, filters) {
        derivedStateOf { all.orEmpty().filter { matches(it, filters, bounds) } }
    }
    val grouped by remember(filtered) { derivedStateOf { filtered.groupBy { startOfDay(it.timestamp) } } }
    val spent = filtered.filter { it.type == TxnType.DEBIT }.sumOf { it.effectiveAmount }
    val received = filtered.filter { it.type == TxnType.CREDIT }.sumOf { it.amount }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val advanced = filters.advancedCount()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 112.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (onBack != null) SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                        Column(Modifier.weight(1f)) {
                            Text(
                                accountLabel ?: "Activity", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                if (all == null) " " else "${filtered.size} shown  ·  ${formatRupeesCompact(spent)} spent  ·  ${formatRupeesCompact(received)} in",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = filters.query,
                        onValueChange = { filters = filters.copy(query = it) },
                        placeholder = { Text("Search payee, note, amount") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (filters.query.isNotEmpty()) {
                                Box(Modifier.size(40.dp).pressable(role = null) { filters = filters.copy(query = "") }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Close, "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        colors = neoFieldColors(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SpendChip("All", filters.type == null, { filters = filters.copy(type = null) }, Modifier.weight(1f))
                        SpendChip("Spent", filters.type == TxnType.DEBIT, { filters = filters.copy(type = if (filters.type == TxnType.DEBIT) null else TxnType.DEBIT) }, Modifier.weight(1f))
                        SpendChip("Received", filters.type == TxnType.CREDIT, { filters = filters.copy(type = if (filters.type == TxnType.CREDIT) null else TxnType.CREDIT) }, Modifier.weight(1.3f))
                        SpendChip(
                            if (advanced > 0) "Filters · $advanced" else "Filters", advanced > 0, { showFilters = true }, Modifier.weight(1.3f),
                            // Drop the icon when active so the count fits.
                            leading = if (advanced > 0) null else { { Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(16.dp)) } },
                        )
                    }
                    val toCollect = all.orEmpty().sumOf { if (it.paidForOthers) it.amount else 0.0 }
                    if (toCollect > 0.004 || filters.paidForOthers) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SpendChip("Paid for others", filters.paidForOthers, { filters = filters.copy(paidForOthers = !filters.paidForOthers) }, Modifier.weight(1.4f))
                            Text(
                                "${formatRupeesCompact(toCollect)} to collect", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (all == null) {
                items(7) { Column(Modifier.padding(horizontal = 20.dp)) { SkeletonRow() } }
            } else if (filtered.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp)) {
                        EmptyState(
                            if (filters.isActive()) "No matches" else "No transactions yet",
                            if (filters.isActive()) "Try clearing a filter or searching for something else."
                            else "New payments appear here automatically. Tap Add to record a cash payment.",
                        )
                    }
                }
            } else {
                grouped.forEach { (day, list) ->
                    stickyHeader(key = "day-$day") { DayHeader(day, list) }
                    items(list.size, key = { list[it].id }) { i ->
                        val t = list[i]
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            TransactionRow(t, onClick = { detail = t })
                            if (i < list.lastIndex) HairlineDivider()
                        }
                    }
                }
            }
        }

        AddFab({ showAdd = true }, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp))
    }

    detail?.let { TransactionDetailSheet(it, onDismiss = { detail = null }) }
    if (showAdd) ManualEntrySheet(onDismiss = { showAdd = false })
    if (showFilters) {
        FiltersSheet(
            filters, onChange = { filters = it },
            onCustomRange = { showRange = true },
            onDismiss = { showFilters = false },
        )
    }

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
    return com.jayant.spendsense.time.localDateOf(utcMillis, kotlinx.datetime.TimeZone.UTC)
        .atStartOfDayMillis(kotlinx.datetime.TimeZone.currentSystemDefault())
}

@Composable
private fun DayHeader(day: Long, list: List<Transaction>) {
    val spent = list.filter { it.type == TxnType.DEBIT }.sumOf { it.effectiveAmount }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatDayHeader(day).uppercase(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (spent > 0) {
                MoneyText(spent, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, symbolScale = 0.9f)
            }
        }
        HairlineDivider(Modifier.padding(horizontal = 20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(filters: Filters, onChange: (Filters) -> Unit, onCustomRange: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            AmountFilter(filters, onChange)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldLabel("Category")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpendChip("Any", filters.category == null, { onChange(filters.copy(category = null)) })
                    SpendCategories.forEach { c ->
                        SpendChip(c, filters.category == c, { onChange(filters.copy(category = if (filters.category == c) null else c)) })
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldLabel("Paid with")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpendChip("Any", filters.mode == null, { onChange(filters.copy(mode = null)) })
                    PaymentModePickerOrder.forEach { m ->
                        SpendChip(m.label(), filters.mode == m, { onChange(filters.copy(mode = if (filters.mode == m) null else m)) })
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldLabel("Date")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DatePreset.entries.forEach { p ->
                        SpendChip(
                            if (p == DatePreset.ANY) "Any time" else p.label, filters.preset == p,
                            { if (p == DatePreset.CUSTOM) onCustomRange() else onChange(filters.copy(preset = p)) },
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("Clear", { onChange(filters.copy(category = null, mode = null, preset = DatePreset.ANY, minAmount = null, maxAmount = null)) }, Modifier.weight(1f))
                PrimaryButton("Done", onDismiss, Modifier.weight(1f))
            }
        }
    }
}

/** "More than" quick picks plus free min/max fields. Amounts are the full transaction amount, not your split share. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountFilter(filters: Filters, onChange: (Filters) -> Unit) {
    var minText by remember { mutableStateOf(filters.minAmount?.let { formatAmountInput(it) }.orEmpty()) }
    var maxText by remember { mutableStateOf(filters.maxAmount?.let { formatAmountInput(it) }.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FieldLabel("Amount")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SpendChip("Any", filters.minAmount == null && filters.maxAmount == null, {
                minText = ""; maxText = ""
                onChange(filters.copy(minAmount = null, maxAmount = null))
            })
            AMOUNT_PRESETS.forEach { v ->
                val selected = filters.minAmount == v && filters.maxAmount == null
                SpendChip("Over ${formatRupeesWhole(v)}", selected, {
                    val next = if (selected) null else v
                    minText = next?.let { formatAmountInput(it) }.orEmpty(); maxText = ""
                    onChange(filters.copy(minAmount = next, maxAmount = null))
                })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AmountField(minText, "More than ₹", Modifier.weight(1f)) {
                minText = it; onChange(filters.copy(minAmount = parseAmountInput(it)))
            }
            AmountField(maxText, "Less than ₹", Modifier.weight(1f)) {
                maxText = it; onChange(filters.copy(maxAmount = parseAmountInput(it)))
            }
        }
    }
}

@Composable
private fun AmountField(value: String, label: String, modifier: Modifier, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 10 && v.all { it.isDigit() || it == '.' } && v.count { it == '.' } <= 1) onValueChange(v) },
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        colors = neoFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        modifier = modifier,
    )
}

private fun parseAmountInput(s: String): Double? = s.toDoubleOrNull()?.takeIf { it > 0 }

private fun formatAmountInput(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
