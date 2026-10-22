package com.jayant.spendsense.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.BarChart
import com.jayant.spendsense.ui.components.BarDatum
import com.jayant.spendsense.ui.components.CategoryBadge
import com.jayant.spendsense.ui.components.DonutChart
import com.jayant.spendsense.ui.components.DonutSlice
import com.jayant.spendsense.ui.components.EmptyState
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.HairlineDivider
import com.jayant.spendsense.ui.components.HeroMoney
import com.jayant.spendsense.ui.components.MoneyDecimals
import com.jayant.spendsense.ui.components.MoneyText
import com.jayant.spendsense.ui.components.Pill
import com.jayant.spendsense.ui.components.RankBar
import com.jayant.spendsense.ui.components.SectionHeader
import com.jayant.spendsense.ui.components.SpendCard
import com.jayant.spendsense.ui.components.SplitSheet
import com.jayant.spendsense.ui.components.StackedShareBar
import com.jayant.spendsense.ui.components.TransactionRow
import com.jayant.spendsense.ui.components.TransfersCategory
import com.jayant.spendsense.ui.components.addMonths
import com.jayant.spendsense.ui.components.categoryColor
import com.jayant.spendsense.ui.components.dayOfMonth
import com.jayant.spendsense.ui.components.daysInMonth
import com.jayant.spendsense.ui.components.formatDateTime
import com.jayant.spendsense.ui.components.formatMonth
import com.jayant.spendsense.ui.components.formatRupeesCompact
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.staggeredEntrance
import com.jayant.spendsense.ui.components.startOfMonth
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

private data class CategoryTotal(val name: String, val amount: Double, val color: Color)
private data class MerchantTotal(val name: String, val amount: Double, val count: Int, val color: Color)

private data class MonthSummary(
    val monthStart: Long,
    val count: Int,
    val mySpend: Double,
    val paidOut: Double,
    val coveredByOthers: Double,
    val income: Double,
    val transfers: Double,
    val previousSpend: Double,
    val daily: List<BarDatum>,
    val avgPerDay: Double,
    val categories: List<CategoryTotal>,
    val merchants: List<MerchantTotal>,
    val recent: List<Transaction>,
)

private fun summarise(monthStart: Long, txns: List<Transaction>, previous: List<Transaction>): MonthSummary {
    val debits = txns.filter { it.type == TxnType.DEBIT }
    val credits = txns.filter { it.type == TxnType.CREDIT }
    val days = daysInMonth(monthStart)
    val perDay = DoubleArray(days)
    debits.forEach { perDay[(dayOfMonth(it.timestamp) - 1).coerceIn(0, days - 1)] += it.effectiveAmount }

    val catMap = debits.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.effectiveAmount } }
        .filterValues { it > 0 }.toList().sortedByDescending { it.second }
    val top = catMap.take(7).map { CategoryTotal(it.first, it.second, categoryColor(it.first)) }
    val rest = catMap.drop(7).sumOf { it.second }
    val categories = if (rest > 0) top + CategoryTotal("More", rest, Color(0xFF5B6074)) else top

    val merchants = debits.filter { !it.counterparty.isNullOrBlank() }
        .groupBy { it.counterparty!!.trim().lowercase() }
        .map { (_, list) ->
            val newest = list.first()
            MerchantTotal(newest.counterparty!!.trim(), list.sumOf { it.effectiveAmount }, list.size, categoryColor(newest.category))
        }
        .filter { it.amount > 0 }.sortedByDescending { it.amount }.take(5)

    val now = System.currentTimeMillis()
    val isCurrent = startOfMonth(now) == monthStart
    val elapsed = if (isCurrent) dayOfMonth(now) else days
    val mySpend = debits.sumOf { it.effectiveAmount }
    return MonthSummary(
        monthStart = monthStart,
        count = txns.size,
        mySpend = mySpend,
        paidOut = debits.sumOf { it.amount },
        coveredByOthers = debits.sumOf { it.amount - it.effectiveAmount },
        income = credits.sumOf { it.amount },
        transfers = debits.filter { it.category == TransfersCategory }.sumOf { it.effectiveAmount },
        previousSpend = previous.filter { it.type == TxnType.DEBIT }.sumOf { it.effectiveAmount },
        daily = perDay.mapIndexed { i, v -> BarDatum((i + 1).toString(), v) },
        avgPerDay = if (elapsed > 0) mySpend / elapsed else 0.0,
        categories = categories,
        merchants = merchants,
        recent = txns.take(6),
    )
}

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "Late night"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

@Composable
fun DashboardScreen(onSeeAllTransactions: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { TransactionRepository.get(context) }
    val changes by repo.changes.collectAsState()
    val currentMonth = remember { startOfMonth(System.currentTimeMillis()) }
    var monthStart by rememberSaveable { mutableLongStateOf(currentMonth) }
    var summary by remember { mutableStateOf<MonthSummary?>(null) }
    var pending by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var detail by remember { mutableStateOf<Transaction?>(null) }
    var splitFor by remember { mutableStateOf<Transaction?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(monthStart, changes) {
        val (s, p) = withContext(Dispatchers.IO) {
            val end = addMonths(monthStart, 1)
            val txns = repo.between(monthStart, end)
            val prev = repo.between(addMonths(monthStart, -1), monthStart)
            summarise(monthStart, txns, prev) to repo.pendingReview()
        }
        summary = s
        pending = p
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        MonthHeader(
            monthStart, canGoForward = monthStart < currentMonth,
            onPrev = { monthStart = addMonths(monthStart, -1) },
            onNext = { monthStart = addMonths(monthStart, 1) },
        )

        val s = summary
        if (s == null || s.monthStart != monthStart) {
            Spacer(Modifier.height(240.dp))
        } else if (s.count == 0 && pending.isEmpty()) {
            EmptyState(
                "Nothing recorded in ${formatMonth(monthStart).substringBefore(' ')}",
                "Payments from SMS and payment-app notifications show up here on their own. You can also add one by hand from Transactions.",
            )
        } else {
            DashboardBody(
                s, pending,
                onOpenDetail = { detail = it },
                onOpenSplit = { splitFor = it },
                onFullyMine = { t -> scope.launch(Dispatchers.IO) { repo.setSplit(t.id, false, null) } },
                onSeeAll = onSeeAllTransactions,
            )
        }
    }

    detail?.let { TransactionDetailSheet(it, onDismiss = { detail = null }) }
    splitFor?.let { SplitSheet(it, onDismiss = { splitFor = null }) }
}

@Composable
private fun DashboardBody(
    s: MonthSummary,
    pending: List<Transaction>,
    onOpenDetail: (Transaction) -> Unit,
    onOpenSplit: (Transaction) -> Unit,
    onFullyMine: (Transaction) -> Unit,
    onSeeAll: () -> Unit,
) {
    val monthStart = s.monthStart
    Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
        HeroCard(s, Modifier.staggeredEntrance(0))

        if (pending.isNotEmpty()) {
            Column(Modifier.staggeredEntrance(1)) {
                SectionHeader("Needs your answer") {
                    Pill(pending.size.toString(), MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), MaterialTheme.colorScheme.primary)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pending.take(20).forEach { t ->
                        PendingCard(t, onOpen = { onOpenSplit(t) }, onFullyMine = { onFullyMine(t) })
                    }
                }
            }
        }

        if (s.paidOut > 0) {
            var selectedDay by remember(monthStart) { mutableStateOf<Int?>(null) }
            SpendCard(Modifier.staggeredEntrance(2)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("Daily spend", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            selectedDay?.let { "${it + 1} ${formatMonth(monthStart).take(3)}" } ?: "Tap a bar for the day",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(
                            selectedDay?.let { s.daily[it].value } ?: s.avgPerDay,
                            style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                            symbolScale = 0.75f, decimals = MoneyDecimals.NONE,
                        )
                        Text(
                            if (selectedDay == null) "average per day" else "that day",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                BarChart(s.daily, selectedDay, { selectedDay = it }, average = s.avgPerDay, labelEvery = 7)
            }
        }

        if (s.categories.isNotEmpty()) {
            var selectedCat by remember(monthStart) { mutableStateOf<Int?>(null) }
            val total = s.categories.sumOf { it.amount }
            SpendCard(Modifier.staggeredEntrance(3)) {
                Text("Where it went", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DonutChart(
                        slices = s.categories.map { DonutSlice(it.name, it.amount, it.color) },
                        selected = selectedCat,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val sel = selectedCat?.let { s.categories.getOrNull(it) }
                            Text(sel?.name ?: "Total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(
                                sel?.amount ?: total, style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurface, decimals = MoneyDecimals.NONE, symbolScale = 0.6f,
                            )
                            if (sel != null) {
                                Text("${(sel.amount / total * 100).roundToInt()}% of spend", style = MaterialTheme.typography.bodySmall, color = sel.color)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                val maxAmount = s.categories.maxOf { it.amount }
                s.categories.forEachIndexed { i, c ->
                    CategoryRankRow(c, total, maxAmount, selected = selectedCat == i) {
                        selectedCat = if (selectedCat == i) null else i
                    }
                }
            }
        }

        if (s.merchants.isNotEmpty()) {
            SpendCard(Modifier.staggeredEntrance(4)) {
                Text("Top merchants", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                s.merchants.forEachIndexed { i, m ->
                    if (i > 0) HairlineDivider()
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(m.color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                            Text(m.name.first().uppercase(), style = MaterialTheme.typography.titleSmall, color = m.color)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(m.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (m.count == 1) "1 payment" else "${m.count} payments",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        MoneyText(m.amount, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.8f)
                    }
                }
            }
        }

        if (s.recent.isNotEmpty()) {
            Column(Modifier.staggeredEntrance(5)) {
                SectionHeader("Recent") {
                    Text(
                        "See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.pressable(role = null, onClick = onSeeAll).padding(vertical = 8.dp, horizontal = 4.dp),
                    )
                }
                SpendCard(contentPadding = 16.dp) {
                    s.recent.forEachIndexed { i, t ->
                        if (i > 0) HairlineDivider()
                        TransactionRow(t, onClick = { onOpenDetail(t) }, showDate = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(monthStart: Long, canGoForward: Boolean, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMonth(monthStart), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        NavCircle(Icons.Rounded.ChevronLeft, "Previous month", true, onPrev)
        Spacer(Modifier.width(8.dp))
        NavCircle(Icons.Rounded.ChevronRight, "Next month", canGoForward, onNext)
    }
}

@Composable
private fun NavCircle(icon: ImageVector, desc: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.spend.hairline, CircleShape)
            .pressable(enabled = enabled, haptic = true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
}

@Composable
private fun HeroCard(s: MonthSummary, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val accent = MaterialTheme.colorScheme.primary
    val glow = Brush.radialGradient(
        colors = listOf(lerp(base, accent, 0.20f), lerp(base, accent, 0.05f), base),
        center = Offset(Float.POSITIVE_INFINITY, 0f), radius = 900f,
    )
    val isCurrent = startOfMonth(System.currentTimeMillis()) == s.monthStart
    SpendCard(modifier, brush = glow, contentPadding = 24.dp, shape = MaterialTheme.shapes.extraLarge) {
        Text(
            if (isCurrent) "My spend this month" else "My spend in ${formatMonth(s.monthStart).substringBefore(' ')}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        HeroMoney(s.mySpend)
        Spacer(Modifier.height(10.dp))
        DeltaLine(s)
        Spacer(Modifier.height(22.dp))
        StackedShareBar(
            mine = s.mySpend, other = s.coveredByOthers,
            mineColor = MaterialTheme.colorScheme.primary, otherColor = MaterialTheme.spend.accent2,
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroStat("Paid out", s.paidOut, MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
            HeroStat("Covered by others", s.coveredByOthers, MaterialTheme.spend.accent2, Modifier.weight(1f))
            HeroStat("Income", s.income, MaterialTheme.spend.credit, Modifier.weight(1f))
        }
        if (s.transfers > 0) {
            Spacer(Modifier.height(16.dp))
            HairlineDivider()
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Transfers, counted above", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                MoneyText(s.transfers, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, symbolScale = 0.9f)
            }
        }
    }
}

@Composable
private fun DeltaLine(s: MonthSummary) {
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (s.previousSpend <= 0.0) {
        Text("${s.count} transactions", style = style, color = color)
        return
    }
    val diff = s.mySpend - s.previousSpend
    val pct = abs(diff / s.previousSpend * 100).roundToInt()
    val word = if (diff <= 0) "less" else "more"
    Text("$pct% $word than the month before  ·  ${formatRupeesCompact(abs(diff))}", style = style, color = color)
}

@Composable
private fun HeroStat(label: String, amount: Double, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        MoneyText(
            amount, style = MaterialTheme.typography.titleMedium, color = color, animate = true,
            decimals = MoneyDecimals.NONE, symbolScale = 0.75f,
        )
        Text(
            label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PendingCard(t: Transaction, onOpen: () -> Unit, onFullyMine: () -> Unit) {
    SpendCard(
        Modifier.width(264.dp), onClick = onOpen, contentPadding = 16.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CategoryBadge(t.category, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    t.counterparty?.takeIf { it.isNotBlank() } ?: "Payment",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(formatDateTime(t.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        MoneyText(t.amount, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.55f)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Fully mine", onFullyMine, Modifier.weight(1f))
            GhostButton("Split", onOpen, Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CategoryRankRow(c: CategoryTotal, total: Double, maxAmount: Double, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().pressable(role = null, onClick = onClick).padding(vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(c.color))
            Text(
                c.name, style = MaterialTheme.typography.titleSmall,
                color = if (selected) c.color else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("${(c.amount / total * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MoneyText(
                c.amount, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                symbolScale = 0.8f, modifier = Modifier.width(84.dp), textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(8.dp))
        RankBar((c.amount / maxAmount).toFloat(), c.color)
    }
}
