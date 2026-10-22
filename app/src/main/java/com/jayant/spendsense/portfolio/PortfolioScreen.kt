package com.jayant.spendsense.portfolio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment
import com.jayant.spendsense.data.PortfolioRepository
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val DETECT_DAYS = 90L

/** What the bottom sheet is currently showing. */
private sealed interface SheetTarget {
    /** [prefill] is null for a blank form, or a draft (id == 0) built from a detected payment. */
    data class Add(val prefill: Investment?) : SheetTarget
    data class Edit(val holding: Investment) : SheetTarget
    data class UpdateValue(val holding: Investment) : SheetTarget
}

private data class DetectedPayment(val txn: Transaction, val recurring: Boolean)

private fun Transaction.info() = PaymentInfo(id, counterparty, amount, timestamp)

@Composable
fun PortfolioScreen() {
    val context = LocalContext.current.applicationContext
    val portfolioRepo = remember { PortfolioRepository.get(context) }
    val txnRepo = remember { TransactionRepository.get(context) }
    val pTick by portfolioRepo.changes.collectAsState()
    val tTick by txnRepo.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var holdings by remember { mutableStateOf<List<Investment>>(emptyList()) }
    var payments by remember { mutableStateOf<List<DetectedPayment>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }
    // The intro entrance plays once; anything composed after it settles renders immediately.
    var intro by remember { mutableStateOf(true) }

    LaunchedEffect(pTick, tTick) {
        val (h, p) = withContext(Dispatchers.IO) {
            val all = portfolioRepo.all()
            val now = System.currentTimeMillis()
            val debits = txnRepo.search(
                fromMillis = now - DETECT_DAYS * 24 * 60 * 60 * 1000L,
                toMillis = now + 60_000L,
                type = TxnType.DEBIT,
                category = "Investment",
                limit = 100,
            )
            val infos = debits.map { it.info() }
            val untracked = debits.filter { !isPaymentTracked(it.info(), all) }
            all to untracked.map { DetectedPayment(it, looksRecurring(it.info(), infos)) }
        }
        holdings = h
        payments = p
        loaded = true
    }
    LaunchedEffect(loaded) {
        if (loaded) { delay(1600); intro = false }
    }

    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val now = remember(holdings) { System.currentTimeMillis() }
    val totals = remember(holdings) { totals(holdings) }
    val slices = remember(holdings) { allocation(holdings) }
    val groups = remember(holdings) { groupByType(holdings) }
    val upcoming = remember(holdings, now) { upcomingMaturities(holdings, now) }
    val sipTotal = remember(holdings) { monthlySipTotal(holdings) }

    fun save(inv: Investment) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch(Dispatchers.IO) { portfolioRepo.upsert(inv) }
        sheet = null
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 20.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            if (loaded && holdings.isEmpty()) {
                item(key = "empty") { Staggered(0, intro) { EmptyState(onAdd = { sheet = SheetTarget.Add(null) }) } }
            } else if (holdings.isNotEmpty()) {
                item(key = "hero") { Staggered(0, intro) { Hero(totals, holdings.size, sipTotal) } }
                if (slices.isNotEmpty()) {
                    item(key = "alloc") { Staggered(1, intro) { AllocationSection(slices) } }
                }
                if (upcoming.isNotEmpty()) {
                    item(key = "upcoming") {
                        Staggered(2, intro) { UpcomingStrip(upcoming, now, onClick = { sheet = SheetTarget.Edit(it) }) }
                    }
                }
                groups.forEachIndexed { i, (type, list) ->
                    item(key = "group-${type.name}") {
                        Staggered(3 + i, intro) {
                            GroupLedger(
                                type = type,
                                list = list,
                                now = now,
                                onUpdate = { sheet = SheetTarget.UpdateValue(it) },
                                onEdit = { sheet = SheetTarget.Edit(it) },
                            )
                        }
                    }
                }
            }

            if (payments.isNotEmpty()) {
                item(key = "detected") {
                    Staggered(2, intro) {
                        DetectedSection(payments) { p ->
                            sheet = SheetTarget.Add(prefillFromPayment(p.txn.info(), p.recurring, formatDate(p.txn.timestamp)))
                        }
                    }
                }
            }
        }

        val fabSource = remember { MutableInteractionSource() }
        ExtendedFloatingActionButton(
            onClick = { sheet = SheetTarget.Add(null) },
            expanded = fabExpanded,
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Add holding", fontWeight = FontWeight.SemiBold) },
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            interactionSource = fabSource,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).pressScale(fabSource),
        )
    }

    when (val s = sheet) {
        null -> Unit
        is SheetTarget.Add -> HoldingSheet(
            initial = s.prefill,
            onDismiss = { sheet = null },
            onSave = ::save,
        )
        is SheetTarget.Edit -> HoldingSheet(
            initial = s.holding,
            onDismiss = { sheet = null },
            onSave = ::save,
            onDelete = { inv ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                scope.launch(Dispatchers.IO) { portfolioRepo.delete(inv.id) }
                sheet = null
            },
        )
        is SheetTarget.UpdateValue -> UpdateValueSheet(
            holding = s.holding,
            onDismiss = { sheet = null },
            onSave = { v -> save(s.holding.copy(currentValue = v)) },
        )
    }
}

// ---------- hero ----------

/** Net worth as one oversized figure on the page itself, no card. Size adapts to its digit count. */
@Composable
private fun Hero(t: Totals, count: Int, sipTotal: Double) {
    val animated = rememberCountUp(t.current)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Net worth")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val s = formatInr(t.current).drop(1).removePrefix("-")
            val digits = s.count { it.isDigit() }
            val commas = s.count { it == ',' }
            val em = digits * 0.6f + commas * 0.3f + 0.45f
            val size = (maxWidth.value / em).coerceIn(30f, 68f)
            MoneyText(
                animated,
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = size.sp, lineHeight = (size * 1.1f).sp, letterSpacing = (-2).sp,
                ),
                weight = FontWeight.Bold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GainText(t.gain, t.gainPct, style = MaterialTheme.typography.titleMedium, withAmount = true)
            Text("overall", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(18.dp))
        Hairline()
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 16.dp)) {
            Stat("Invested", Modifier.weight(1f)) {
                MoneyText(t.invested, style = MaterialTheme.typography.titleLarge, compact = true)
            }
            VerticalHairline()
            Stat("Monthly SIPs", Modifier.weight(1f).padding(start = 16.dp)) {
                if (sipTotal > 0) MoneyText(sipTotal, style = MaterialTheme.typography.titleLarge, compact = true)
                else Text("None", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VerticalHairline()
            Stat("Holdings", Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    "$count",
                    style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        value()
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun VerticalHairline() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)))
}

// ---------- allocation ----------

@Composable
private fun AllocationSection(slices: List<AllocationSlice>) {
    val ramp = allocationRamp()
    val haptics = LocalHapticFeedback.current
    var selected by remember(slices) { mutableStateOf<Int?>(null) }
    val focus = selected?.let { slices.getOrNull(it) } ?: slices.first()
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SectionLabel("Allocation")
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                fractions = slices.map { it.fraction },
                colors = ramp,
                selected = selected,
                modifier = Modifier.size(144.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 26.dp)) {
                    Text(
                        "${Math.round(focus.fraction * 100)}%",
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        focus.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                slices.forEachIndexed { i, s ->
                    val source = remember { MutableInteractionSource() }
                    val isSel = selected == i
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 40.dp)
                            .pressScale(source, 0.98f)
                            .clickable(interactionSource = source, indication = null) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                selected = if (isSel) null else i
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(ramp[i % ramp.size]))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            s.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (isSel) formatInrCompact(s.value) else "${Math.round(s.fraction * 100)}%",
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------- upcoming maturities ----------

@Composable
private fun UpcomingStrip(items: List<Pair<Investment, Long>>, now: Long, onClick: (Investment) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionLabel("Upcoming maturities")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 20.dp),
        ) {
            items(items.size, key = { items[it].first.id }) { i ->
                val (inv, days) = items[i]
                val source = remember { MutableInteractionSource() }
                val shape = MaterialTheme.shapes.large
                val overdue = days < 0
                Column(
                    Modifier
                        .width(196.dp)
                        .pressScale(source)
                        .clip(shape)
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                        .clickable(interactionSource = source, indication = null) { onClick(inv) }
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        maturityShort(days),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Text(inv.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MoneyText(inv.valueNow, style = MaterialTheme.typography.titleMedium, compact = true)
                    tenureProgress(inv.startDate, inv.maturityDate, now)?.let {
                        Spacer(Modifier.height(4.dp))
                        TenureBar(it, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

// ---------- holdings ledger ----------

@Composable
private fun GroupLedger(
    type: AssetType,
    list: List<Investment>,
    now: Long,
    onUpdate: (Investment) -> Unit,
    onEdit: (Investment) -> Unit,
) {
    val tot = totals(list)
    Column(Modifier.animateContentSize(tween(260))) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(type.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${list.size} ${if (list.size == 1) "holding" else "holdings"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(tot.current, style = MaterialTheme.typography.titleMedium)
                GainText(tot.gain, tot.gainPct)
            }
        }
        list.forEach { inv ->
            Hairline()
            HoldingRow(inv, now, onUpdate, onEdit)
        }
        Hairline()
    }
}

@Composable
private fun HoldingRow(
    inv: Investment,
    now: Long,
    onUpdate: (Investment) -> Unit,
    onEdit: (Investment) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(source, 0.985f)
            .clickable(interactionSource = source, indication = null) { expanded = !expanded }
            .padding(vertical = 16.dp)
            .animateContentSize(tween(260)),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    inv.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (inv.platform != null || inv.isSip) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        inv.platform?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (inv.isSip) SipMarker(inv.sipAmount)
                    }
                }
                val maturity = inv.maturityDate
                if (inv.assetType in COUNTDOWN_TYPES && maturity != null) {
                    val days = daysUntil(maturity, now)
                    Text(
                        maturityLabel(days),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (days < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    tenureProgress(inv.startDate, maturity, now)?.let {
                        TenureBar(it, Modifier.fillMaxWidth(0.85f))
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MoneyText(inv.valueNow, style = MaterialTheme.typography.titleMedium)
                GainText(inv.gain, inv.gainPct())
                Text(
                    "from ${formatInrCompact(inv.investedAmount)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Row(Modifier.padding(top = 14.dp).height(IntrinsicSize.Min)) {
                Box(
                    Modifier.fillMaxHeight().width(2.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                )
                Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    inv.units?.let { DetailLine("Units", plainNumber(it)) }
                    inv.startDate?.let { DetailLine("Started", formatDate(it)) }
                    inv.maturityDate?.let { DetailLine("Matures", formatDate(it)) }
                    inv.interestRate?.let { DetailLine("Interest rate", "${plainNumber(it)}% p.a.") }
                    DetailLine("Gain or loss", (if (inv.gain >= 0) "+" else "-") + formatInr(abs(inv.gain)))
                    inv.notes?.takeIf { it.isNotBlank() }?.let { DetailLine("Notes", it) }
                    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuietButton("Update value", { onUpdate(inv) })
                        QuietButton("Edit", { onEdit(inv) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- detected payments ----------

@Composable
private fun DetectedSection(payments: List<DetectedPayment>, onAdd: (DetectedPayment) -> Unit) {
    Column {
        SectionLabel("Detected investment payments")
        Text(
            "Spotted in your last $DETECT_DAYS days of transactions and not in your holdings yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        Column(Modifier.animateContentSize(tween(260))) {
            payments.forEach { p ->
                Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            p.txn.counterparty ?: "Investment payment",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                formatDate(p.txn.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (p.recurring) SipMarker(null)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(p.txn.amount, style = MaterialTheme.typography.titleMedium)
                        QuietButton("Add to holdings", { onAdd(p) })
                    }
                }
            }
            Hairline()
        }
    }
}

// ---------- empty ----------

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EmptyIllustration()
        Spacer(Modifier.height(12.dp))
        Text(
            "Nothing tracked yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            "Add a fund, FD, stock or gold holding and your net worth and allocation appear here. Investment payments found in your SMS can be added in one tap.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Add a holding", onAdd, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
    }
}
