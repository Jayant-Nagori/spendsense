package com.jayant.spendsense.notify

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jayant.spendsense.data.Payee
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.orders.OrderRepository
import com.jayant.spendsense.orders.StoredOrder
import com.jayant.spendsense.ui.components.OrderReceipt
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.MoneyText
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.SpendCategories
import com.jayant.spendsense.ui.components.SpendChip
import com.jayant.spendsense.ui.components.SpendTextField
import com.jayant.spendsense.ui.components.formatRupees
import com.jayant.spendsense.ui.components.SplitEditor
import com.jayant.spendsense.ui.components.formatTime
import com.jayant.spendsense.ui.components.label
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.rememberSplitState
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import kotlinx.coroutines.launch
import com.jayant.spendsense.AppScope
import com.jayant.spendsense.ioDispatcher
import com.jayant.spendsense.platform.Platform
import com.jayant.spendsense.platformContext

/**
 * The full-screen "what was that payment for?" pop-up. Android hosts it in an overlay window over any app;
 * iOS shows it inside the app (opened by the "Log bank SMS" App Intent or a notification tap).
 */
@Composable
fun PaymentPopupContent(txn: Transaction, onClose: () -> Unit) {
    val context = platformContext()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val gold = MaterialTheme.colorScheme.primary
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val enter = remember { Animatable(0f) }
    var note by remember { mutableStateOf(txn.note.orEmpty()) }
    var noteEdited by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(txn.category) }
    var categoryEdited by remember { mutableStateOf(false) }
    // The order lookup runs in the background; follow the stored row so a prefilled note / receipt appears live.
    var live by remember { mutableStateOf(txn) }
    var order by remember { mutableStateOf<StoredOrder?>(null) }
    val txnChanges by TransactionRepository.get(context).changes.collectAsState()
    val orderChanges by OrderRepository.get(context).changes.collectAsState()
    LaunchedEffect(txnChanges, orderChanges) {
        val (t, o) = withContext(ioDispatcher) {
            runCatching { TransactionRepository.get(context).get(txn.id) }.getOrNull() to
                runCatching { OrderRepository.get(context).forTransaction(txn.id) }.getOrNull()
        }
        if (t != null) {
            live = t
            if (!noteEdited && !t.note.isNullOrBlank()) note = t.note
            if (!categoryEdited) category = t.category
        }
        order = o
    }
    var splitting by remember { mutableStateOf(false) }
    var notPayment by remember { mutableStateOf(false) }
    var pushShare by remember { mutableStateOf<Double?>(null) }
    val pushEnabled = remember { runCatching { Settings(context).splitwisePush }.getOrDefault(false) }
    var forOther by remember { mutableStateOf(false) }
    var whoFor by remember { mutableStateOf("") }
    val split = rememberSplitState(txn.amount, key = txn.id)

    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        enter.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }

    fun close() {
        scope.launch { enter.animateTo(0f, tween(200)); onClose() }
    }

    fun save(isSplit: Boolean, myShare: Double?, forOthers: Boolean = false, paidFor: String? = null) {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val text = note.trim().take(200).ifBlank { null }
        val edited = noteEdited
        val cat = category
        val catEdited = categoryEdited
        // App-wide scope: the pop-up's own composition is torn down by close() before this finishes.
        AppScope.launch(ioDispatcher) {
            val repo = TransactionRepository.get(context)
            repo.get(txn.id)?.let {
                // Keep a note typed in the notification reply meanwhile unless the user edited the field here.
                repo.update(it.copy(note = if (edited) text else it.note, category = if (catEdited) cat else it.category, isSplit = isSplit, myShare = if (isSplit) myShare else null, reviewed = true, paidForOthers = forOthers, paidFor = paidFor))
            }
            Platform.services.cancelPaymentNotification(txn.id)
        }
        // Optional push: offer "Also add to Splitwise" (off by default; nothing is sent without another tap).
        if (isSplit && !forOthers && myShare != null && myShare < txn.amount && pushEnabled &&
            com.jayant.spendsense.splitwise.SplitwiseSync.status.value.connected
        ) {
            pushShare = myShare
            return
        }
        close()
    }

    /** Offer / promo / reminder that slipped through: remove it and ignore similar alerts from this sender. */
    fun markNotPayment() {
        AppScope.launch(ioDispatcher) {
            TransactionRepository.get(context).markNotPayment(txn.id)
            Platform.services.cancelPaymentNotification(txn.id)
        }
        close()
    }

    // Slow gold glow breathing behind the amount.
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        0.08f, 0.24f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "glowAlpha",
    )
    val lift = with(LocalDensity.current) { 140.dp.toPx() }
    val p = enter.value

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = p)).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(gold.copy(alpha = glow * p), Color.Transparent),
                    center = Offset(size.width * 0.32f, size.height * 0.26f), radius = size.width * 0.8f,
                ),
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().graphicsLayer { translationY = (1f - p) * lift; alpha = p }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 32.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(gold))
                Spacer(Modifier.size(10.dp))
                Text(
                    "PAYMENT DETECTED  ·  ${formatTime(txn.timestamp).uppercase()}",
                    style = MaterialTheme.typography.labelSmall, color = gold, modifier = Modifier.weight(1f),
                )
                Text(
                    "LATER", style = MaterialTheme.typography.labelMedium, color = grey,
                    modifier = Modifier.pressable(role = null) { close() }.padding(12.dp),
                )
            }

            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.CenterStart) {
                MoneyText(
                    txn.amount, animate = true,
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 88.sp, fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.42f,
                )
            }

            Text(
                Payee.display(live.counterparty)?.let { "to $it" } ?: "paid",
                style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(Payee.vpaForMeta(live.counterparty), txn.bank, txn.account?.let { "ending " + it.takeLast(4) }, txn.paymentMode?.label()).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = grey, modifier = Modifier.padding(top = 6.dp))
            }

            Spacer(Modifier.height(36.dp))
            SpendTextField(note, { note = it.take(200); noteEdited = true }, "What was it for?")
            order?.let {
                Spacer(Modifier.height(16.dp))
                OrderReceipt(it, maxItems = 5)
            }

            Spacer(Modifier.height(24.dp))
            Text("CATEGORY", style = MaterialTheme.typography.labelSmall, color = grey)
            Spacer(Modifier.height(10.dp))
            val cats = remember(live.category) { listOf(live.category) + SpendCategories.filter { it != live.category } }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c -> SpendChip(c, category == c, { category = c; categoryEdited = true }) }
            }

            Spacer(Modifier.height(32.dp))
            if (pushShare != null) {
                com.jayant.spendsense.splitwise.SplitwisePushStep(
                    amount = txn.amount, myShare = pushShare!!,
                    description = note.trim().ifBlank { Payee.display(live.counterparty) ?: "Payment" },
                    onDone = { close() },
                )
            } else if (forOther) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("PAID FOR SOMEONE ELSE", style = MaterialTheme.typography.labelSmall, color = gold)
                    SpendTextField(whoFor, { whoFor = it.take(60) }, "Who for? (optional)")
                    Text(
                        "Counts as ₹0 of your spend. " + (whoFor.trim().ifEmpty { "They" }) + " owes you the full ${formatRupees(txn.amount)}.",
                        style = MaterialTheme.typography.bodySmall, color = grey,
                    )
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Save", { save(true, 0.0, true, whoFor.trim().take(60).ifBlank { null }) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                GhostButton("Back", { forOther = false }, Modifier.fillMaxWidth())
            } else if (splitting) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp)) {
                    SplitEditor(split)
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton("Save", { save(split.isSplit, split.myShare, split.forOthers, split.paidForName) }, Modifier.fillMaxWidth(), enabled = split.isValid)
                Spacer(Modifier.height(12.dp))
                GhostButton("It was all mine", { save(false, null) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                GhostButton("For someone else", { splitting = false; forOther = true }, Modifier.fillMaxWidth())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("All mine", { save(false, null) }, Modifier.weight(1f))
                    GhostButton("I split it", { split.fullyMine = false; split.touched = true; splitting = true }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                GhostButton("FOR SOMEONE ELSE", { forOther = true }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                if (!notPayment) {
                    Text(
                        "NOT A PAYMENT?", style = MaterialTheme.typography.labelMedium, color = grey,
                        modifier = Modifier.pressable(role = null) { notPayment = true }.padding(vertical = 12.dp),
                    )
                } else {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Remove this and ignore similar alerts from this sender (offers, cashback promos, reminders)?",
                            style = MaterialTheme.typography.bodySmall, color = grey,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton("Remove", ::markNotPayment, Modifier.weight(1f))
                            GhostButton("Cancel", { notPayment = false }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
