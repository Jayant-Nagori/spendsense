package com.jayant.spendsense.portfolio

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.AutoGraph
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment
import com.jayant.spendsense.data.PortfolioRepository
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DETECT_DAYS = 90L

/** What the add/edit sheet is currently showing. */
private sealed interface SheetTarget {
    /** [prefill] may be null (blank form) or a draft (id == 0) built from a detected payment. */
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

    var holdings by remember { mutableStateOf<List<Investment>>(emptyList()) }
    var payments by remember { mutableStateOf<List<DetectedPayment>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<SheetTarget?>(null) }

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

    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val now = remember(holdings) { System.currentTimeMillis() }
    val totals = remember(holdings) { totals(holdings) }
    val slices = remember(holdings) { allocation(holdings) }
    val groups = remember(holdings) { groupByType(holdings) }
    val upcoming = remember(holdings, now) { upcomingMaturities(holdings, now) }

    fun save(inv: Investment) {
        scope.launch(Dispatchers.IO) { portfolioRepo.upsert(inv) }
        sheet = null
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp,
                bottom = 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title") {
                Staggered(0) {
                    Column {
                        Text("Wealth", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Everything you own, in one place",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (loaded && holdings.isEmpty()) {
                item(key = "empty") { Staggered(1) { EmptyState(onAdd = { sheet = SheetTarget.Add(null) }) } }
            } else if (holdings.isNotEmpty()) {
                item(key = "hero") { Staggered(1) { HeroCard(totals, holdings.size) } }
                if (slices.isNotEmpty()) {
                    item(key = "alloc") { Staggered(2) { AllocationCard(slices) } }
                }
                if (upcoming.isNotEmpty()) {
                    item(key = "upcoming") {
                        Staggered(3) { UpcomingStrip(upcoming, onClick = { sheet = SheetTarget.Edit(it) }) }
                    }
                }
                groups.forEachIndexed { i, (type, list) ->
                    item(key = "group-${type.name}") {
                        Staggered(4 + i) {
                            GroupCard(
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
                    Staggered(2) {
                        DetectedSection(payments) { p ->
                            sheet = SheetTarget.Add(prefillFromPayment(p.txn.info(), p.recurring, formatDate(p.txn.timestamp)))
                        }
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { sheet = SheetTarget.Add(null) },
            expanded = fabExpanded,
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Add holding") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
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

@Composable
private fun HeroCard(t: Totals, count: Int) {
    val animated = rememberCountUp(t.current)
    val container = MaterialTheme.colorScheme.primaryContainer
    val onContainer = MaterialTheme.colorScheme.onPrimaryContainer
    Box(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(container, MaterialTheme.colorScheme.surfaceContainerHigh)))
            .padding(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Net worth", style = MaterialTheme.typography.labelLarge, color = onContainer.copy(alpha = 0.75f))
            MoneyText(
                animated,
                style = MaterialTheme.typography.displayMedium,
                color = onContainer,
                weight = FontWeight.Bold,
            )
            GainPill(t.gain, t.gainPct)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text("Invested", style = MaterialTheme.typography.labelMedium, color = onContainer.copy(alpha = 0.7f))
                    MoneyText(t.invested, style = MaterialTheme.typography.titleMedium, color = onContainer)
                }
                Column {
                    Text("Holdings", style = MaterialTheme.typography.labelMedium, color = onContainer.copy(alpha = 0.7f))
                    Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = onContainer)
                }
            }
        }
    }
}

// ---------- allocation ----------

@Composable
private fun AllocationCard(slices: List<AllocationSlice>) {
    val palette = chartPalette()
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Allocation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DonutChart(
                    fractions = slices.map { it.fraction },
                    colors = palette,
                    modifier = Modifier.size(148.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${slices.size}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (slices.size == 1) "asset class" else "asset classes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slices.forEachIndexed { i, s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(palette[i % palette.size]))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                s.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${Math.round(s.fraction * 100)}%",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- upcoming maturities ----------

@Composable
private fun UpcomingStrip(items: List<Pair<Investment, Long>>, onClick: (Investment) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Upcoming maturities", Icons.Rounded.Event)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items.size) { i ->
                val (inv, days) = items[i]
                val source = remember { MutableInteractionSource() }
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier
                        .width(190.dp)
                        .pressScale(source)
                        .clickable(interactionSource = source, indication = null) { onClick(inv) },
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            inv.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        MoneyText(
                            inv.valueNow,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            maturityLabel(days),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (days < 0) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

// ---------- holdings ----------

@Composable
private fun GroupCard(
    type: AssetType,
    list: List<Investment>,
    now: Long,
    onUpdate: (Investment) -> Unit,
    onEdit: (Investment) -> Unit,
) {
    val tot = totals(list)
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.animateContentSize(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssetIconBadge(type)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(type.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                HorizontalDivider(
                    Modifier.padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                HoldingRow(inv, now, onUpdate, onEdit)
            }
        }
    }
}

@Composable
private fun GainText(gain: Double, pct: Double) {
    val color = if (gain >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Text(formatPct(pct), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color)
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
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    inv.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    if (inv.isSip) SipBadge(inv.sipAmount)
                }
                val maturity = inv.maturityDate
                if (inv.assetType in COUNTDOWN_TYPES && maturity != null) {
                    val days = daysUntil(maturity, now)
                    Text(
                        maturityLabel(days),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (days < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                MoneyText(inv.valueNow, style = MaterialTheme.typography.titleSmall)
                Text(
                    "of ${formatInr(inv.investedAmount)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GainText(inv.gain, inv.gainPct())
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                inv.units?.let { DetailLine("Units", plainNumber(it)) }
                inv.startDate?.let { DetailLine("Started", formatDate(it)) }
                inv.maturityDate?.let { DetailLine("Matures", formatDate(it)) }
                inv.interestRate?.let { DetailLine("Interest rate", "${plainNumber(it)}% p.a.") }
                DetailLine("Gain / loss", (if (inv.gain >= 0) "+" else "") + formatInr(inv.gain))
                inv.notes?.takeIf { it.isNotBlank() }?.let { DetailLine("Notes", it) }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { onUpdate(inv) }) {
                        Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Update value")
                    }
                    OutlinedButton(onClick = { onEdit(inv) }) {
                        Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SipBadge(amount: Double?) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Repeat, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(4.dp))
            Text(
                if (amount != null) "SIP ${formatInrCompact(amount)}" else "SIP",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

// ---------- detected payments ----------

@Composable
private fun DetectedSection(payments: List<DetectedPayment>, onAdd: (DetectedPayment) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Detected investment payments", Icons.Rounded.AutoGraph)
        Text(
            "Investment debits from the last $DETECT_DAYS days that aren't in your holdings yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.animateContentSize(),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                payments.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(
                        Modifier.padding(horizontal = 20.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.txn.counterparty ?: "Investment payment",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    formatDate(p.txn.timestamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (p.recurring) SipBadge(null)
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            MoneyText(p.txn.amount, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            FilledTonalButton(
                                onClick = { onAdd(p) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                            ) { Text("Add to holdings", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                }
            }
        }
    }
}

// ---------- empty / shared ----------

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(assetIcon(AssetType.SAVINGS), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text("Start your wealth picture", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Add mutual funds, stocks, FDs, gold, PPF, anything you own, and see your net worth and allocation at a glance.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAdd) {
                Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add your first holding")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
