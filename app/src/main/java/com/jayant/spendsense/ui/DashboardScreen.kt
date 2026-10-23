package com.jayant.spendsense.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.AddFab
import com.jayant.spendsense.ui.components.AnswerTag
import com.jayant.spendsense.ui.components.BarSegment
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
import com.jayant.spendsense.ui.components.StackedBar
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
    val recent: List<Transaction>,
)

private fun summarise(monthStart: Long, txns: List<Transaction>): MonthSummary {
    val debits = txns.filter { it.type == TxnType.DEBIT }
    val credits = txns.filter { it.type == TxnType.CREDIT && !it.isRefundCredit }
    val categories = debits.groupBy { it.category }.mapValues { (_, v) -> v.sumOf { it.effectiveAmount } }
        .filterValues { it > 0 }.toList().sortedByDescending { it.second }
        .map { CategoryTotal(it.first, it.second) }
    return MonthSummary(
        monthStart = monthStart,
        count = txns.size,
        mySpend = debits.sumOf { it.effectiveAmount },
        paidOut = debits.sumOf { it.amount },
        coveredByOthers = debits.sumOf { if (it.isSplit && it.myShare != null) it.amount - it.myShare else 0.0 },
        refunded = debits.sumOf { if (it.refundStatus == com.jayant.spendsense.data.RefundStatus.RECEIVED) it.refundAmount ?: 0.0 else 0.0 },
        income = credits.sumOf { it.amount },
        categories = categories,
        recent = txns.take(5),
    )
}

@Composable
fun DashboardScreen(onSeeAllTransactions: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val repo = remember { TransactionRepository.get(context) }
    val changes by repo.changes.collectAsState()
    val currentMonth = remember { startOfMonth(System.currentTimeMillis()) }
    var monthStart by rememberSaveable { mutableLongStateOf(currentMonth) }
    var summary by remember { mutableStateOf<MonthSummary?>(null) }
    var pending by remember { mutableStateOf<List<Transaction>>(emptyList()) }
    var detail by remember { mutableStateOf<Transaction?>(null) }
    var splitFor by remember { mutableStateOf<Transaction?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(monthStart, changes) {
        val (s, p) = withContext(Dispatchers.IO) {
            val txns = repo.between(monthStart, addMonths(monthStart, 1))
            summarise(monthStart, txns) to repo.pendingReview()
        }
        summary = s
        pending = p
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 112.dp),
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
                    "Payments from SMS and payment-app notifications show up here on their own. You can also add one by hand.",
                )
            } else {
                HomeBody(
                    s, pending,
                    onOpenDetail = { detail = it },
                    onOpenSplit = { splitFor = it },
                    onFullyMine = { t -> scope.launch(Dispatchers.IO) { repo.setSplit(t.id, false, null) } },
                    onSeeAll = onSeeAllTransactions,
                )
            }
        }
        AddFab({ showAdd = true }, Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp))
    }

    detail?.let { TransactionDetailSheet(it, onDismiss = { detail = null }) }
    splitFor?.let { SplitSheet(it, onDismiss = { splitFor = null }, startAsSplit = true) }
    if (showAdd) ManualEntrySheet(onDismiss = { showAdd = false })
}

@Composable
private fun HomeBody(
    s: MonthSummary,
    pending: List<Transaction>,
    onOpenDetail: (Transaction) -> Unit,
    onOpenSplit: (Transaction) -> Unit,
    onFullyMine: (Transaction) -> Unit,
    onSeeAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(40.dp)) {
        Hero(s, Modifier.staggeredEntrance(0))

        if (pending.isNotEmpty()) {
            var skipped by rememberSaveable { mutableIntStateOf(0) }
            val index = skipped % pending.size
            val current = pending[index]
            Column(Modifier.staggeredEntrance(1)) {
                SectionHeader("Needs your answer")
                PendingCard(current, onOpen = { onOpenDetail(current) }, onSplit = { onOpenSplit(current) }, onFullyMine = { onFullyMine(current) })
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
            WhereItWent(s.categories, Modifier.staggeredEntrance(2))
        }

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
    val isCurrent = startOfMonth(System.currentTimeMillis()) == s.monthStart
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Overline(if (isCurrent) "Spent this month" else "Spent in ${formatMonth(s.monthStart).substringBefore(' ')}")
        HeroMoney(s.mySpend)
        Text(
            listOfNotNull(
                "${formatRupees(s.paidOut)} paid",
                s.coveredByOthers.takeIf { it > 0.004 }?.let { "${formatRupees(it)} covered by others" },
                s.refunded.takeIf { it > 0.004 }?.let { "${formatRupees(it)} refunded" },
                "${formatRupees(s.income)} in",
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PendingCard(t: Transaction, onOpen: () -> Unit, onSplit: () -> Unit, onFullyMine: () -> Unit) {
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
                    t.counterparty?.takeIf { it.isNotBlank() } ?: "Payment",
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
    }
}

@Composable
private fun WhereItWent(categories: List<CategoryTotal>, modifier: Modifier = Modifier) {
    val total = categories.sumOf { it.amount }
    val top = categories.take(4)
    val rest = categories.drop(4).sumOf { it.amount }
    val base = MaterialTheme.colorScheme.onSurface
    val shades: List<Color> = listOf(
        MaterialTheme.colorScheme.primary, base.copy(alpha = 0.85f), base.copy(alpha = 0.55f), base.copy(alpha = 0.32f),
    )
    val restColor = base.copy(alpha = 0.16f)
    Column(modifier) {
        SectionHeader("Where it went")
        StackedBar(
            top.mapIndexed { i, c -> BarSegment(c.amount, shades[i]) } + (if (rest > 0) listOf(BarSegment(rest, restColor)) else emptyList()),
            Modifier.semantics {
                contentDescription = "Spending by category. " + top.joinToString(". ") { "${it.name} ${(it.amount / total * 100).roundToInt()} percent" }
            },
        )
        Spacer(Modifier.height(8.dp))
        top.forEachIndexed { i, c ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
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

@Composable
private fun HomeSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        SkeletonBlock(Modifier.fillMaxWidth(0.6f), 72.dp)
        SkeletonBlock(Modifier.fillMaxWidth(), 200.dp)
        SkeletonBlock(Modifier.fillMaxWidth(), 160.dp)
    }
}
