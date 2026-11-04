package com.jayant.spendsense.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.AppScope
import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.ioDispatcher
import com.jayant.spendsense.platform.Platform
import com.jayant.spendsense.platformContext
import com.jayant.spendsense.ui.OnOffToggle
import com.jayant.spendsense.ui.SettingsGroup
import com.jayant.spendsense.ui.SettingsRow
import com.jayant.spendsense.ui.SquareField
import com.jayant.spendsense.ui.SquareToggle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Connected apps: connect Zomato / Swiggy / Zepto (read-only) to fill in what was ordered. */
@Composable
fun ConnectedAppsScreen(onBack: () -> Unit) {
    val context = platformContext()
    val status by OrderSync.status.collectAsState()
    var fake by remember { mutableStateOf(false) }
    var fakeBase by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        withContext(ioDispatcher) { OrderSync.refreshStatus(context) }
        fake = OrderSync.fakeEnabled(context)
        fakeBase = OrderSync.fakeBase(context) ?: Platform.services.defaultFakeOrderServer
    }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
            }
            Text("Connected apps", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 72.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            Text(
                "Fill in what you ordered: after a food or grocery payment, SpendSense adds the items as the note and keeps an itemised receipt. " +
                    "You sign in on the app's own page; SpendSense never sees your password or OTP. Read-only: it can only read past orders, " +
                    "never order, pay or change anything.",
                style = MaterialTheme.typography.bodySmall, color = grey,
            )
            SettingsGroup("Food & groceries") {
                OrderProvider.entries.forEach { p ->
                    val s = status[p] ?: ProviderStatus(false)
                    val line = when {
                        s.busy -> if (s.connected) "Fetching orders…" else "Waiting for sign-in…"
                        s.message != null -> s.message
                        s.connected -> "Connected" + (if (s.lastSync > 0) " · synced ${ago(s.lastSync)}" else "") + " · ${p.subtitle}"
                        else -> p.subtitle
                    }
                    SettingsRow(p.displayName, line) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (s.connected) {
                                SquareToggle("Sync", false, { if (!s.busy) AppScope.launch { OrderSync.sync(context, p) } })
                                SquareToggle("Disconnect", false, { if (!s.busy) AppScope.launch { OrderSync.disconnect(context, p) } })
                            } else {
                                SquareToggle(if (s.busy) "…" else "Connect", true, { if (!s.busy) AppScope.launch { OrderSync.connect(context, p) } })
                            }
                        }
                    }
                }
            }
            Text(
                "Swiggy, Zomato and Zepto decide which apps may connect. If one says it hasn't opened up yet, nothing was shared.",
                style = MaterialTheme.typography.bodySmall, color = grey,
            )

            if (com.jayant.spendsense.platform.DebugGate.enabled()) {
                SettingsGroup("Developer (debug build)") {
                    SettingsRow("Use fake order server", "Points every provider at a local fake MCP + OAuth server (scripts/fake_order_server.py). Switching forgets connections.") {
                        OnOffToggle(fake) { on -> fake = on; OrderSync.setFake(context, on, fakeBase) }
                    }
                    Spacer(Modifier.height(12.dp))
                    SquareField(
                        fakeBase, { fakeBase = it; if (fake) OrderSync.setFake(context, true, it) }, Platform.services.defaultFakeOrderServer,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                }
            }
        }
    }
}

private fun ago(t: Long): String {
    val m = ((currentTimeMillis() - t) / 60_000).coerceAtLeast(0)
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 48 * 60 -> "${m / 60} h ago"
        else -> "${m / 1440} days ago"
    }
}
