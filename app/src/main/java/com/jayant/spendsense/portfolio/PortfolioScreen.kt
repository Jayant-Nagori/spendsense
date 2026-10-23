package com.jayant.spendsense.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment
import com.jayant.spendsense.data.PortfolioRepository
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.NeoPopSurface
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.spend
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
        if (loaded) { delay(700); intro = false }
    }

    val now = remember(holdings) { System.currentTimeMillis() }
    val totals = remember(holdings) { totals(holdings) }
    val slices = remember(holdings) { allocation(holdings) }
    val groups = remember(holdings) { groupByType(holdings) }
    val upcoming = remember(holdings, now) { upcomingMaturities(holdings, now) }

    fun save(inv: Investment) {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        scope.launch(Dispatchers.IO) { portfolioRepo.upsert(inv) }
        sheet = null
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp,
                bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(36.dp),
        ) {
            item(key = "title") {
                Text(
                    "WEALTH",
                    style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 1.6.sp),
                    modifier = Modifier.heightIn(min = 32.dp),
                )
            }
            if (loaded && holdings.isEmpty()) {
                item(key = "empty") { Staggered(0, intro) { EmptyState(onAdd = { sheet = SheetTarget.Add(null) }) } }
            } else if (holdings.isNotEmpty()) {
                item(key = "hero") { Staggered(0, intro) { Hero(totals) } }
                if (slices.isNotEmpty()) {
                    item(key = "alloc") { Staggered(1, intro) { AllocationSection(slices) } }
                }
                groups.forEachIndexed { i, (type, list) ->
                    item(key = "group-${type.name}") {
                        Staggered(2 + i, intro) {
                            GroupLedger(type, list, onEdit = { sheet = SheetTarget.Edit(it) })
                        }
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item(key = "upcoming") {
                        Staggered(3, intro) { UpcomingList(upcoming, onClick = { sheet = SheetTarget.Edit(it) }) }
                    }
                }
            }

            if (payments.isNotEmpty()) {
                item(key = "detected") {
                    Staggered(4, intro) {
                        DetectedSection(payments) { p ->
                            sheet = SheetTarget.Add(prefillFromPayment(p.txn.info(), p.recurring, formatDate(p.txn.timestamp)))
                        }
                    }
                }
            }
        }

        if (holdings.isNotEmpty()) {
            NeoPopSurface(
                color = MaterialTheme.colorScheme.onBackground,
                onClick = { sheet = SheetTarget.Add(null) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp).height(52.dp),
            ) {
                Text(
                    "+ ADD",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.6.sp),
                    color = MaterialTheme.colorScheme.background,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }
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
private fun Hero(t: Totals) {
    val animated = rememberCountUp(t.current)
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Net worth")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val s = formatInr(t.current).drop(1).removePrefix("-")
            val digits = s.count { it.isDigit() }
            val commas = s.count { it == ',' }
            val em = digits * 0.62f + commas * 0.3f + 0.5f
            val style = MaterialTheme.typography.displayLarge
            val size = (maxWidth.value / em).coerceIn(30f, style.fontSize.value)
            MoneyText(
                animated,
                style = style.copy(fontSize = size.sp, lineHeight = (size * 1.08f).sp),
                tabular = false,
            )
        }
        val sign = if (t.gain >= 0) "+" else "-"
        val gc = gainColor(t.gain)
        Text(
            buildAnnotatedString {
                append("${formatInr(t.invested)} invested · ")
                withStyle(SpanStyle(color = gc, fontWeight = FontWeight.Bold)) {
                    append("$sign${formatInr(abs(t.gain))} (${formatPct(t.gainPct, signed = false)})")
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = grey,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- allocation ----------

@Composable
private fun AllocationSection(slices: List<AllocationSlice>) {
    val ramp = allocationRamp()
    Column {
        AllocationBar(slices, ramp)
        Spacer(Modifier.height(8.dp))
        slices.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).background(ramp[i % ramp.size]))
                Spacer(Modifier.width(12.dp))
                Text(
                    s.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${Math.round(s.fraction * 100)}%",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(48.dp),
                )
                MoneyText(
                    s.value,
                    style = MaterialTheme.typography.bodyMedium,
                    compact = true,
                    weight = FontWeight.Bold,
                )
            }
        }
    }
}

// ---------- holdings ----------

@Composable
private fun GroupLedger(type: AssetType, list: List<Investment>, onEdit: (Investment) -> Unit) {
    val tot = totals(list)
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(type.label, Modifier.weight(1f))
            MoneyText(
                tot.current,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                compact = true,
            )
        }
        list.forEach { inv ->
            Hairline()
            HoldingRow(inv, onClick = { onEdit(inv) })
        }
    }
}

private fun holdingMeta(inv: Investment): String {
    val parts = mutableListOf<String>()
    inv.platform?.takeIf { it.isNotBlank() }?.let { parts += it }
    if (inv.isSip) parts += inv.sipAmount?.let { "SIP ${formatInr(it)}/mo" } ?: "SIP"
    inv.maturityDate?.takeIf { inv.assetType in COUNTDOWN_TYPES }?.let { parts += "Matures ${formatDate(it)}" }
    if (parts.isEmpty()) inv.units?.let { parts += "${plainNumber(it)} units" }
    return parts.joinToString(" · ")
}

@Composable
private fun HoldingRow(inv: Investment, onClick: () -> Unit) {
    val meta = holdingMeta(inv)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(inv.assetType)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                inv.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MoneyText(inv.valueNow, style = MaterialTheme.typography.titleSmall)
            GainText(inv.gain, inv.gainPct())
        }
    }
}

// ---------- upcoming maturities ----------

@Composable
private fun UpcomingList(items: List<Pair<Investment, Long>>, onClick: (Investment) -> Unit) {
    Column {
        SectionLabel("Upcoming maturities", Modifier.padding(bottom = 12.dp))
        items.forEach { (inv, days) ->
            Hairline()
            val tone = when {
                days < 0 -> MaterialTheme.colorScheme.error
                days <= 30 -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                Modifier.fillMaxWidth().clickable { onClick(inv) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(inv.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(maturityLabel(days), style = MaterialTheme.typography.bodySmall, color = tone, maxLines = 1)
                }
                MoneyText(inv.valueNow, style = MaterialTheme.typography.titleSmall, compact = true)
            }
        }
    }
}

// ---------- detected payments ----------

@Composable
private fun DetectedSection(payments: List<DetectedPayment>, onAdd: (DetectedPayment) -> Unit) {
    Column {
        SectionLabel("Detected payments")
        Text(
            "Investment payments from the last $DETECT_DAYS days that are not in your holdings yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        payments.forEach { p ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        p.txn.counterparty ?: "Investment payment",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatDate(p.txn.timestamp) + if (p.recurring) " · Looks recurring" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                MoneyText(p.txn.amount, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(12.dp))
                GhostButton("Add", { onAdd(p) }, Modifier.width(80.dp).height(40.dp))
            }
        }
    }
}

// ---------- empty ----------

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Track everything you own", style = MaterialTheme.typography.displaySmall)
        Text(
            "Funds, stocks, FDs and gold in one place, with your net worth and where it sits.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Add your first holding", onAdd, Modifier.fillMaxWidth())
    }
}
