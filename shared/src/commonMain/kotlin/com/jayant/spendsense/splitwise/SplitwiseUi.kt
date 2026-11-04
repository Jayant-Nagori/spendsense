package com.jayant.spendsense.splitwise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.ioDispatcher
import com.jayant.spendsense.platformContext
import com.jayant.spendsense.ui.OnOffToggle
import com.jayant.spendsense.ui.SettingsGroup
import com.jayant.spendsense.ui.SettingsRow
import com.jayant.spendsense.ui.SquareField
import com.jayant.spendsense.ui.SquareToggle
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.MoneyText
import com.jayant.spendsense.ui.components.Overline
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.SectionHeader
import com.jayant.spendsense.ui.components.SpendCard
import com.jayant.spendsense.ui.components.formatDateTime
import com.jayant.spendsense.ui.components.formatRupees
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Connected apps › Splitwise. */
@Composable
fun SplitwiseScreen(onBack: () -> Unit) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    val status by SplitwiseSync.status.collectAsState()
    val settings = remember { Settings(context) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var askKeep by remember { mutableStateOf(false) }
    var push by remember { mutableStateOf(settings.splitwisePush) }
    LaunchedEffect(Unit) { withContext(ioDispatcher) { SplitwiseSync.refreshStatus(context) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Text("Splitwise", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 72.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            if (!status.connected) {
                SettingsGroup("Connect") {
                    Text(
                        "See who paid for you and add your share as an expense. Read-only: SpendSense never changes anything on Splitwise unless you tap \"Add to Splitwise\".",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(16.dp))
                    Guide()
                    Spacer(Modifier.height(16.dp))
                    SquareField(key, { key = it.take(200) }, "Paste your Splitwise API key",
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(if (status.busy) "Checking…" else "Connect", enabled = key.isNotBlank() && !status.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        error = null
                        scope.launch { error = SplitwiseSync.connect(context, key); if (error == null) key = "" }
                    })
                    (error ?: status.message)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            } else {
                SettingsGroup("Connected") {
                    SettingsRow(
                        status.userName?.let { "Signed in as $it" } ?: "Connected",
                        if (status.lastSync > 0) "Last sync ${formatDateTime(status.lastSync)}" else "Not synced yet",
                    )
                    Spacer(Modifier.height(16.dp))
                    PrimaryButton(if (status.busy) "Syncing…" else "Sync now", enabled = !status.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch { SplitwiseSync.sync(context, force = true) }
                    })
                    status.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(8.dp))
                    SettingsRow(
                        "Offer \"Also add to Splitwise\"",
                        "After you split a payment in the pop-up. Off by default; nothing is sent without a tap.",
                        onClick = { push = !push; settings.splitwisePush = push },
                    ) { OnOffToggle(push) { push = it; settings.splitwisePush = it } }
                }
                SettingsGroup("Disconnect") {
                    if (!askKeep) {
                        GhostButton("Disconnect", { askKeep = true }, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Keep the expenses already imported from Splitwise?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton("Keep them", { scope.launch { SplitwiseSync.disconnect(context, keepEntries = true); askKeep = false } }, Modifier.weight(1f))
                            GhostButton("Remove", { scope.launch { SplitwiseSync.disconnect(context, keepEntries = false); askKeep = false } }, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Guide() {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("HOW TO GET YOUR KEY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        listOf(
            "1. In a browser, sign in at secure.splitwise.com/apps",
            "2. Tap \"Register your application\". Any name works (e.g. \"My SpendSense\"); for the homepage any URL, e.g. https://example.com",
            "3. Open the app you made and tap \"Create API key\"",
            "4. Copy the API key and paste it here. It stays encrypted on this phone.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = grey) }
        Text("Never paste your Splitwise password, only the API key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Home: "you owe / you are owed" by friend. Hidden when Splitwise isn't connected or everything is settled. */
@Composable
fun SplitwiseDashboardCard(modifier: Modifier = Modifier) {
    val context = platformContext()
    val status by SplitwiseSync.status.collectAsState()
    val changes by TransactionRepository.get(context).changes.collectAsState()
    var balances by remember { mutableStateOf<List<SwFriendBalance>>(emptyList()) }
    LaunchedEffect(status.connected, status.lastSync, changes) {
        balances = if (!status.connected) emptyList() else withContext(ioDispatcher) { runCatching { SplitwiseStore.get(context).balances() }.getOrDefault(emptyList()) }
    }
    if (!status.connected || balances.isEmpty()) return
    val owe = balances.filter { it.balance < 0 }
    val owed = balances.filter { it.balance > 0 }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        SectionHeader("Splitwise")
        SpendCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Overline("You owe")
                    MoneyText(-owe.sumOf { it.balance }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, symbolScale = 0.7f)
                }
                Column(Modifier.weight(1f)) {
                    Overline("You are owed")
                    MoneyText(owed.sumOf { it.balance }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.spend.credit, symbolScale = 0.7f)
                }
            }
            Spacer(Modifier.height(12.dp))
            balances.take(6).forEach { b ->
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(
                        if (b.balance > 0) "owes you ${formatRupees(b.balance)}" else "you owe ${formatRupees(-b.balance)}",
                        style = MaterialTheme.typography.bodySmall, color = if (b.balance > 0) MaterialTheme.spend.credit else grey,
                    )
                }
            }
            if (balances.size > 6) Text("+${balances.size - 6} more", style = MaterialTheme.typography.bodySmall, color = grey)
        }
    }
}

/** Transaction detail: who paid / who owes on the linked Splitwise expense (nothing when not linked). */
@Composable
fun SplitwiseBreakdown(txn: Transaction) {
    if (txn.splitwiseId == null) return
    val context = platformContext()
    var rec by remember(txn.id) { mutableStateOf<SwRecord?>(null) }
    LaunchedEffect(txn.id) { rec = withContext(ioDispatcher) { runCatching { SplitwiseStore.get(context).forTransaction(txn.id) }.getOrNull() } }
    val r = rec ?: return
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    SpendCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Overline(
            when (r.role) {
                SwRole.OWE -> "Splitwise · someone paid for you"
                SwRole.PAID -> "Splitwise · you paid"
                SwRole.SETTLE -> "Splitwise · settle-up"
                SwRole.NONE -> "Splitwise"
            },
        )
        Spacer(Modifier.height(6.dp))
        // Plain text only: Splitwise data is untrusted and is never formatted or linkified.
        Text(r.description ?: "Expense", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Text("Total ${formatRupees(r.cost)}", style = MaterialTheme.typography.bodySmall, color = grey)
        Spacer(Modifier.height(8.dp))
        r.shares.forEach { s ->
            Row(Modifier.fillMaxWidth()) {
                Text(if (s.userId == SplitwiseStore.ME) "You" else s.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(s.paid.takeIf { it > 0.004 }?.let { "paid ${formatRupees(it)}" }, "share ${formatRupees(s.owed)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = grey,
                )
            }
        }
    }
}

/**
 * Pop-up, after "I split it" was saved with push turned on: "Also add to Splitwise?". Pick a friend or group,
 * equal or custom split, and an explicit Add tap. [onDone] closes it either way.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitwisePushStep(amount: Double, myShare: Double, description: String, onDone: () -> Unit) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<SplitwiseSync.Target?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var friend by remember { mutableStateOf<SwFriend?>(null) }
    var group by remember { mutableStateOf<SwGroup?>(null) }
    var equal by remember { mutableStateOf(true) }
    var theirs by remember { mutableStateOf(com.jayant.spendsense.formatFixed((amount - myShare).coerceAtLeast(0.0), 2)) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            target = SplitwiseSync.targets(context) ?: run { loadError = "Connect Splitwise first."; null }
        } catch (e: SplitwiseException) {
            loadError = e.message
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            loadError = "Couldn't load your Splitwise friends."
        }
    }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("ALSO ADD TO SPLITWISE?", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        val t = target
        when {
            result != null -> Text(result!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            loadError != null -> Text(loadError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            t == null -> Text("Loading friends…", style = MaterialTheme.typography.bodySmall, color = grey)
            else -> {
                Text("With", style = MaterialTheme.typography.bodySmall, color = grey)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.friends.take(12).forEach { f -> SquareToggle(f.name.take(18), friend?.id == f.id, { friend = f; group = null }) }
                    t.groups.filter { it.memberIds.size in 2..20 }.take(6).forEach { g -> SquareToggle("${g.name.take(16)} (group)", group?.id == g.id, { group = g; friend = null; equal = true }) }
                }
                if (friend != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SquareToggle("Equal", equal, { equal = true })
                        SquareToggle("Custom", !equal, { equal = false })
                    }
                    if (!equal) SquareField(theirs, { theirs = it.filter { c -> c.isDigit() || c == '.' }.take(12) }, "Their share", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (result == null && t != null && t.me > 0 && (friend != null || group != null)) {
                PrimaryButton(if (busy) "Adding…" else "Add to Splitwise", enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                    val owedBy = pushShares(amount, t.me, friend?.id, group?.memberIds, equal, theirs.toDoubleOrNull())
                    busy = true
                    scope.launch {
                        val err = SplitwiseSync.push(context, amount, description, owedBy, group?.id)
                        busy = false
                        result = err ?: "Added to Splitwise."
                    }
                })
            }
            GhostButton(if (result != null || loadError != null) "Done" else "Skip", onDone, Modifier.weight(1f))
        }
    }
}

/** Owed shares for a push, in paise-exact amounts that add up to [amount]; the rounding remainder goes to [me]. */
internal fun pushShares(amount: Double, me: Long, friend: Long?, group: List<Long>?, equal: Boolean, theirs: Double?): Map<Long, Double> {
    val paise = kotlin.math.round(amount * 100).toLong()
    return when {
        group != null -> {
            val members = (group + me).distinct()
            val each = paise / members.size
            members.associateWith { (if (it == me) each + paise - each * members.size else each) / 100.0 }
        }
        friend != null && equal -> { val half = paise / 2; mapOf(friend to half / 100.0, me to (paise - half) / 100.0) }
        friend != null -> { val th = kotlin.math.round((theirs ?: 0.0).coerceIn(0.0, amount) * 100).toLong(); mapOf(friend to th / 100.0, me to (paise - th) / 100.0) }
        else -> mapOf(me to amount)
    }
}
