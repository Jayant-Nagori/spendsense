package com.jayant.spendsense.ui

import com.jayant.spendsense.ioDispatcher

import com.jayant.spendsense.currentTimeMillis

import com.jayant.spendsense.platformContext

import com.jayant.spendsense.ui.theme.spend
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.ChartType
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.ui.components.CategoryBarChart
import com.jayant.spendsense.ui.components.DonutChart
import com.jayant.spendsense.ui.components.DonutSlice
import com.jayant.spendsense.ui.components.LineChart
import com.jayant.spendsense.ui.components.SpendChip
import com.jayant.spendsense.ui.components.dailySpend
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.AddFab
import com.jayant.spendsense.ui.components.AnswerTag
import com.jayant.spendsense.ui.components.CategoryBadge
import com.jayant.spendsense.ui.components.EmptyState
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.HairlineDivider
import com.jayant.spendsense.ui.components.HeroMoney
import com.jayant.spendsense.ui.components.MoneyDecimals
import com.jayant.spendsense.ui.components.MoneyText
import com.jayant.spendsense.ui.components.Overline
import com.jayant.spendsense.ui.components.PlainIconButton
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.SectionHeader
import com.jayant.spendsense.ui.components.SkeletonBlock
import com.jayant.spendsense.ui.components.SpendCard
import com.jayant.spendsense.ui.components.SplitSheet
import com.jayant.spendsense.ui.components.SquareIconButton
import com.jayant.spendsense.ui.components.TransactionRow
import com.jayant.spendsense.ui.components.addMonths
import com.jayant.spendsense.ui.components.formatDateTime
import com.jayant.spendsense.ui.components.formatMonth
import com.jayant.spendsense.ui.components.formatRupees
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.staggeredEntrance
import com.jayant.spendsense.ui.components.startOfMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private data class CategoryTotal(val name: String, val amount: Double)

private data class MonthSummary(
    val monthStart: Long,
    val count: Int,
    val mySpend: Double,
    val paidOut: Double,
    val coveredByOthers: Double,
    val refunded: Double,
    val income: Double,
    val categories: List<CategoryTotal>,
    val daily: List<Double>,
    val toCollect: Double,
    val owedBy: List<CategoryTotal>,
    val recent: List<Transaction>,
    val accounts: List<com.jayant.spendsense.data.AccountShare> = emptyList(),
)

private fun summarise(monthStart: Long, txns: List<Transaction>, accountPrefs: Map<String, com.jayant.spendsense.data.AccountPref> = emptyMap()): MonthSummary {
    // Card bill payments, own-account transfers and settle-ups are neither spending nor income.
    val debits = txns.filter { it.type == TxnType.DEBIT && !it.isTransfer }
    val credits = txns.filter { it.type == TxnType.CREDIT && !it.isRefundCredit && !it.isTransfer }
    val forOthers = debits.filter { it.paidForOthers }
    val categories = debits.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.effectiveAmount } }
        .filterValues { it > 0 }.toList().sortedByDescending { it.second }
        .map { CategoryTotal(it.first, it.second) }
    return MonthSummary(
        monthStart = monthStart,
        count = txns.size,
        mySpend = debits.sumOf { it.effectiveAmount },
        paidOut = debits.sumOf { it.amount },
        coveredByOthers = debits.sumOf { t -> t.myShare.let { share -> if (t.isSplit && share != null) t.amount - share else 0.0 } },
        refunded = debits.sumOf { if (it.refundStatus == com.jayant.spendsense.data.RefundStatus.RECEIVED) it.refundAmount ?: 0.0 else 0.0 },
        income = credits.sumOf { it.amount },
        categories = categories,
        daily = dailySpend(txns, monthStart, currentTimeMillis()),
        toCollect = forOthers.sumOf { it.amount },
        owedBy = forOthers.groupBy { it.paidForName ?: "Someone" }.map { (k, v) -> CategoryTotal(k, v.sumOf { it.amount }) }.sortedByDescending { it.amount },
        recent = txns.take(5),
        accounts = com.jayant.spendsense.data.AccountRepository.split(txns, accountPrefs),
    )
}

@Composable
fun DashboardScreen(onSeeAllTransactions: () -> Unit = {}, onOpenSettings: () -> Unit = {}, onOpenAccount: (String) -> Unit = {}) {
    val context = platformContext()
    val repo = remember { TransactionRepository.get(context) }
    val changes by repo.changes.collectAsState()
    val accountRepo = remember { com.jayant.spendsense.data.AccountRepository.get(context) }
    val accountChanges by accountRepo.changes.collectAsState()
    val currentMonth = remember { startOfMonth(currentTimeMillis()) }
    var monthStart by rememberSaveable { mutableLongStateOf(currentMonth) }
    var summary by remember { mutableStateOf<MonthSummary?>(null) }
    var pending by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var detail by remember { mutableStateOf<Transaction?>(null) }
    var splitFor by remember { mutableStateOf<Transaction?>(null) }
    var forSomeone by remember { mutableStateOf<Transaction?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(monthStart, changes, accountChanges) {
        val (s, p) = withContext(ioDispatcher) {
            val txns = repo.between(monthStart, addMonths(monthStart, 1))
            summarise(monthStart, txns, runCatching { accountRepo.prefs() }.getOrDefault(emptyMap())) to repo.pendingReview()
        }
        summary = s
        pending = p
    }

    val scroll = rememberScrollState()
    // + ADD steps aside while scrolling down (so it never sits on content being read) and returns on scroll up / at rest at the top.
    var fabVisible by remember { mutableStateOf(true) }
    LaunchedEffect(scroll) {
        var last = scroll.value
        androidx.compose.runtime.snapshotFlow { scroll.value }.collect { v ->
            if (v > last + 4) fabVisible = false else if (v < last - 4 || v == 0) fabVisible = true
            last = v
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            TopBar(
                monthStart, canGoForward = monthStart < currentMonth,
                onPrev = { monthStart = addMonths(monthStart, -1) },
                onNext = { monthStart = addMonths(monthStart, 1) },
                onSettings = onOpenSettings,
            )

            val s = summary
            if (s == null || s.monthStart != monthStart) {
                HomeSkeleton()
            } else if (s.count == 0 && pending.isEmpty()) {
                EmptyState(
                    "Nothing recorded in ${formatMonth(monthStart).substringBefore(' ')}",
                    com.jayant.spendsense.platform.Platform.services.emptyStateHint,
                )
            } else {
                HomeBody(
                    s, pending,
                    onOpenDetail = { detail = it },
                    onOpenSplit = { splitFor = it },
                    onForSomeone = { forSomeone = it },
                    onFullyMine = { t -> scope.launch(ioDispatcher) { repo.setSplit(t.id, false, null) } },
                    onSeeAll = onSeeAllTransactions,
                    onOpenAccount = onOpenAccount,
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            fabVisible, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)) + androidx.compose.animation.slideOutVertically { it / 2 },
        ) { AddFab({ showAdd = true }) }
    }

    detail?.let { TransactionDetailSheet(it, onDismiss = { detail = null }) }
    splitFor?.let { SplitSheet(it, onDismiss = { splitFor = null }, startAsSplit = true) }
    forSomeone?.let { SplitSheet(it, onDismiss = { forSomeone = null }, startForOthers = true) }
    if (showAdd) ManualEntrySheet(onDismiss = { showAdd = false })
}

@Composable
private fun HomeBody(
    s: MonthSummary,
    pending: List<Transaction>,
    onOpenDetail: (Transaction) -> Unit,
    onOpenSplit: (Transaction) -> Unit,
    onForSomeone: (Transaction) -> Unit,
    onFullyMine: (Transaction) -> Unit,
    onSeeAll: () -> Unit,
    onOpenAccount: (String) -> Unit = {},
) {
    val context = platformContext()
    val settings = remember { Settings(context) }
    var chartType by remember { mutableStateOf(settings.chartType) }
    val setChart: (ChartType) -> Unit = { chartType = it; settings.chartType = it }
    Column(verticalArrangement = Arrangement.spacedBy(40.dp)) {
        Hero(s, Modifier.staggeredEntrance(0))

        if (s.toCollect > 0.004) ToCollect(s, Modifier.staggeredEntrance(0))

        if (pending.isNotEmpty()) {
            var skipped by rememberSaveable { mutableIntStateOf(0) }
            val index = skipped % pending.size
            val current = pending[index]
            Column(Modifier.staggeredEntrance(1)) {
                SectionHeader("Needs your answer")
                PendingCard(current, onOpen = { onOpenDetail(current) }, onSplit = { onOpenSplit(current) }, onForSomeone = { onForSomeone(current) }, onFullyMine = { onFullyMine(current) })
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    val grey = MaterialTheme.colorScheme.onSurfaceVariant
                    Text("${index + 1} of ${pending.size}", style = MaterialTheme.typography.bodySmall, color = grey, modifier = Modifier.padding(vertical = 12.dp))
                    if (pending.size > 1) {
                        Text("  ·  ", style = MaterialTheme.typography.bodySmall, color = grey, modifier = Modifier.padding(vertical = 12.dp))
                        Text(
                            "skip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.heightIn(min = 40.dp).pressable(role = null) { skipped = index + 1 }.padding(vertical = 10.dp, horizontal = 4.dp),
                        )
                    }
                }
            }
        }

        if (s.categories.isNotEmpty()) {
            WhereItWent(s.categories, s.daily, chartType, setChart, Modifier.staggeredEntrance(2))
        }

        if (s.accounts.isNotEmpty()) {
            ByCardAndAccount(s.accounts, chartType, onOpenAccount, Modifier.staggeredEntrance(2))
        }

        com.jayant.spendsense.splitwise.SplitwiseDashboardCard(Modifier.staggeredEntrance(3))

        if (s.recent.isNotEmpty()) {
            Column(Modifier.staggeredEntrance(3)) {
                SectionHeader("Recent")
                s.recent.forEachIndexed { i, t ->
                    if (i > 0) HairlineDivider()
                    TransactionRow(t, onClick = { onOpenDetail(t) }, showDate = true)
                }
                HairlineDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(role = null, onClick = onSeeAll),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("SEE ALL ACTIVITY →", style = MaterialTheme.typography.labelLarge.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing), color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun TopBar(monthStart: Long, canGoForward: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatMonth(monthStart), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        SquareIconButton(Icons.Rounded.ChevronLeft, "Previous month", onPrev)
        Spacer(Modifier.width(8.dp))
        SquareIconButton(Icons.Rounded.ChevronRight, "Next month", onNext, enabled = canGoForward)
        Spacer(Modifier.width(8.dp))
        PlainIconButton(Icons.Rounded.Settings, "Settings", onSettings)
    }
}

@Composable
private fun Hero(s: MonthSummary, modifier: Modifier = Modifier) {
    val isCurrent = startOfMonth(currentTimeMillis()) == s.monthStart
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Overline(if (isCurrent) "Spent this month" else "Spent in ${formatMonth(s.monthStart).substringBefore(' ')}")
        HeroMoney(s.mySpend)
        val moneyIn = MaterialTheme.spend.credit
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                val parts = listOfNotNull(
                    "${formatRupees(s.paidOut)} paid" to false,
                    s.coveredByOthers.takeIf { it > 0.004 }?.let { "${formatRupees(it)} covered by others" to true },
                    s.refunded.takeIf { it > 0.004 }?.let { "${formatRupees(it)} refunded" to true },
                    "${formatRupees(s.income)} in" to (s.income > 0.004),
                )
                parts.forEachIndexed { i, (text, isIn) ->
                    if (i > 0) append("  ·  ")
                    if (isIn) withStyle(androidx.compose.ui.text.SpanStyle(color = moneyIn)) { append(text) } else append(text)
                }
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PendingCard(t: Transaction, onOpen: () -> Unit, onSplit: () -> Unit, onForSomeone: () -> Unit, onFullyMine: () -> Unit) {
    SpendCard(
        Modifier.fillMaxWidth(), contentPadding = 16.dp,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
    ) {
        Row(
            Modifier.fillMaxWidth().pressable(role = null, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CategoryBadge(t.category)
            Column(Modifier.weight(1f)) {
                Text(
                    com.jayant.spendsense.data.Payee.display(t.counterparty) ?: "Payment",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatDateTime(t.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            AnswerTag()
        }
        Spacer(Modifier.height(20.dp))
        MoneyText(t.amount, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.5f)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("All mine", onFullyMine, Modifier.weight(1f))
            GhostButton("I split it", onSplit, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        // Same three answers as the payment pop-up.
        GhostButton("For someone else", onForSomeone, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ToCollect(s: MonthSummary, modifier: Modifier = Modifier) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Overline("To collect")
        MoneyText(
            s.toCollect, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.spend.credit,
            symbolScale = 0.7f, decimals = MoneyDecimals.AUTO,
        )
        Text(
            s.owedBy.take(3).joinToString("  ·  ") { "${it.name} ${formatRupees(it.amount)}" } +
                if (s.owedBy.size > 3) "  ·  +${s.owedBy.size - 3} more" else "",
            style = MaterialTheme.typography.bodySmall, color = grey, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WhereItWent(categories: List<CategoryTotal>, daily: List<Double>, chartType: ChartType, onChartType: (ChartType) -> Unit, modifier: Modifier = Modifier) {
    var selected by remember(chartType, categories) { mutableStateOf<Int?>(null) }
    val total = categories.sumOf { it.amount }
    val top = categories.take(4)
    val rest = categories.drop(4).sumOf { it.amount }
    val base = MaterialTheme.colorScheme.onSurface
    val shades: List<Color> = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.spend.accent2, base.copy(alpha = 0.6f), base.copy(alpha = 0.32f),
    )
    val restColor = base.copy(alpha = 0.16f)
    val slices = top.mapIndexed { i, c -> DonutSlice(c.name, c.amount, shades[i]) } +
        (if (rest > 0) listOf(DonutSlice("Others", rest, restColor)) else emptyList())
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val fg = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        SectionHeader("Where it went")
        // Chart style toggles sit left-aligned above the card: the floating + ADD (bottom-right) can never cover them,
        // whatever the scroll position.
        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChartType.entries.forEach { t ->
                ChartTypeToggle(t.label.uppercase(), chartType == t) { onChartType(t) }
            }
        }
        SpendCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            // One readout line shared by all three styles: the tapped slice / bar / day, else a hint.
            val readout: Pair<String, Double>? = selected?.let { i ->
                if (chartType == ChartType.LINE) daily.getOrNull(i)?.let { "Day ${i + 1}" to it } else slices.getOrNull(i)?.let { it.label to it.value }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                if (readout == null) {
                    Text(
                        if (chartType == ChartType.LINE) "Daily spend  ·  tap a day" else "Tap a ${if (chartType == ChartType.BAR) "bar" else "slice"}",
                        style = MaterialTheme.typography.bodySmall, color = grey, modifier = Modifier.weight(1f),
                    )
                } else {
                    val pct = if (chartType != ChartType.LINE && total > 0) "  ·  ${(readout.second / total * 100).roundToInt()}%" else ""
                    Text(
                        readout.first + pct, style = MaterialTheme.typography.titleSmall, color = fg,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    MoneyText(readout.second, style = MaterialTheme.typography.titleSmall, color = fg, symbolScale = 0.8f, textAlign = TextAlign.End)
                }
            }
            Spacer(Modifier.height(12.dp))
            val desc = "Spending by category. " + top.joinToString(". ") { "${it.name} ${(it.amount / total * 100).roundToInt()} percent" }
            Box(Modifier.fillMaxWidth().semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
                when (chartType) {
                    ChartType.DONUT -> DonutChart(slices, selected, onSelect = { selected = it }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val sel = selected?.let { slices.getOrNull(it) }
                            Overline(sel?.label ?: "Total")
                            MoneyText(sel?.value ?: total, style = MaterialTheme.typography.titleMedium, color = fg, symbolScale = 0.8f)
                        }
                    }
                    ChartType.BAR -> CategoryBarChart(slices, selected, { selected = it })
                    ChartType.LINE -> LineChart(daily, selected, { selected = it })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        top.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(role = null) { if (chartType != ChartType.LINE) selected = if (selected == i) null else i },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(8.dp).background(shades[i]))
                Text(
                    c.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${(c.amount / total * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(40.dp),
                )
                MoneyText(
                    c.amount, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    symbolScale = 0.8f, modifier = Modifier.width(88.dp), textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * "By card & account": money paid out of each card / account in the selected month. Follows the chart setting
 * (donut or bars; the daily line has no per-account meaning, so it shows bars). Tap a row for its payments.
 */
@Composable
private fun ByCardAndAccount(rows: List<com.jayant.spendsense.data.AccountShare>, chartType: ChartType, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.onSurface
    val shades: List<Color> = listOf(
        MaterialTheme.colorScheme.primary, MaterialTheme.spend.accent2, base.copy(alpha = 0.6f), base.copy(alpha = 0.32f),
    )
    val restColor = base.copy(alpha = 0.16f)
    val top = rows.take(4)
    val rest = rows.drop(4).sumOf { it.amount }
    val slices = top.mapIndexed { i, r -> DonutSlice(r.label, r.amount, shades[i]) } + (if (rest > 0) listOf(DonutSlice("Others", rest, restColor)) else emptyList())
    var selected by remember(chartType, rows) { mutableStateOf<Int?>(null) }
    val total = rows.sumOf { it.amount }
    val fg = MaterialTheme.colorScheme.onSurface
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        SectionHeader("By card & account")
        if (rows.size > 1) {
            SpendCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                val desc = "Paid from each card and account. " + top.joinToString(". ") { "${it.label} ${(it.share * 100).roundToInt()} percent" }
                Box(Modifier.fillMaxWidth().semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
                    if (chartType == ChartType.DONUT) {
                        DonutChart(slices, selected, onSelect = { selected = it }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val sel = selected?.let { slices.getOrNull(it) }
                                Overline(sel?.label?.take(18) ?: "Paid out")
                                MoneyText(sel?.value ?: total, style = MaterialTheme.typography.titleMedium, color = fg, symbolScale = 0.8f)
                            }
                        }
                    } else {
                        CategoryBarChart(slices, selected, { selected = it })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        rows.forEachIndexed { i, r ->
            if (i > 0) HairlineDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).pressable(role = null) { onOpen(r.key) }
                    .semantics { contentDescription = "${r.label}, ${formatRupees(r.amount)}, ${(r.share * 100).roundToInt()} percent, ${r.count} payments" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .border(1.dp, (shades.getOrNull(i) ?: restColor).copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(r.monogram, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1) }
                Column(Modifier.weight(1f)) {
                    Text(r.label, style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${(r.share * 100).roundToInt()}%  ·  ${r.count} payment${if (r.count == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall, color = grey, maxLines = 1,
                    )
                }
                MoneyText(r.amount, style = MaterialTheme.typography.titleSmall, color = fg, symbolScale = 0.8f, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SkeletonBlock(Modifier.fillMaxWidth(0.6f), 72.dp)
        SkeletonBlock(Modifier.fillMaxWidth(), 200.dp)
        SkeletonBlock(Modifier.fillMaxWidth(), 160.dp)
    }
}

/** Small square text toggle for the chart style (selected = white face / black text). */
@Composable
private fun ChartTypeToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val fg = MaterialTheme.colorScheme.onBackground
    Box(
        Modifier.heightIn(min = 40.dp)
            .semantics { this.selected = selected; contentDescription = "$label chart" }
            .pressable(role = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, softWrap = false,
            modifier = Modifier.background(if (selected) fg else Color.Transparent)
                .border(1.dp, if (selected) fg else fg.copy(alpha = 0.22f), androidx.compose.ui.graphics.RectangleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
