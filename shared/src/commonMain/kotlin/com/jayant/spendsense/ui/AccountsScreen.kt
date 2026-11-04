package com.jayant.spendsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.data.AccountRepository
import com.jayant.spendsense.data.KnownAccount
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.ioDispatcher
import com.jayant.spendsense.platformContext
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.formatRupees
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Accounts & cards: nickname, hide from "By card & account", merge duplicates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(onBack: () -> Unit) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    val repo = remember { AccountRepository.get(context) }
    val changes by repo.changes.collectAsState()
    val txnChanges by TransactionRepository.get(context).changes.collectAsState()
    var accounts by remember { mutableStateOf<List<KnownAccount>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(changes, txnChanges) { accounts = withContext(ioDispatcher) { runCatching { repo.known() }.getOrDefault(emptyList()) } }
    fun act(block: AccountRepository.() -> Unit) = scope.launch(ioDispatcher) { repo.block() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Text("Accounts & cards", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 72.dp)) {
            Text(
                "Every card and account SpendSense has seen in your payments. Give one a name, hide it from the dashboard split, " +
                    "or merge two that are really the same card.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            val list = accounts.orEmpty()
            if (accounts != null && list.isEmpty()) {
                Text("No payments yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            list.forEach { a ->
                val merged = a.pref?.mergedInto
                SettingsRow(
                    a.label,
                    listOfNotNull(
                        "${a.count} payment${if (a.count == 1) "" else "s"} · ${formatRupees(a.total)}",
                        if (a.pref?.hidden == true) "hidden" else null,
                        merged?.let { m -> "merged into " + (list.firstOrNull { it.key == m }?.label ?: com.jayant.spendsense.data.Accounts.labelForKey(m)) },
                    ).joinToString(" · "),
                    onClick = { open = if (open == a.key) null else a.key },
                    trailing = { Text(if (open == a.key) "−" else "+", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                if (open == a.key) {
                    var name by remember(a.key) { mutableStateOf(a.pref?.nickname.orEmpty()) }
                    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SquareField(name, { name = it.take(40) }, "Nickname, e.g. Axis Ace")
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PrimaryButton("Save name", { act { rename(a.key, name) } }, Modifier.weight(1f))
                            GhostButton(if (a.pref?.hidden == true) "Show" else "Hide", { act { setHidden(a.key, a.pref?.hidden != true) } }, Modifier.weight(1f))
                        }
                        if (merged != null) {
                            GhostButton("Unmerge", { act { unmerge(a.key) } }, Modifier.fillMaxWidth())
                        } else {
                            val others = list.filter { it.key != a.key && it.pref?.mergedInto == null }
                            if (others.isNotEmpty()) {
                                Text("MERGE INTO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    others.take(12).forEach { o -> SquareToggle(o.label, false, { act { merge(a.key, o.key) }; open = null }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
