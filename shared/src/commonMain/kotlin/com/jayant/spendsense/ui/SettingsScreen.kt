package com.jayant.spendsense.ui

import com.jayant.spendsense.ioDispatcher

import com.jayant.spendsense.currentTimeMillis

import com.jayant.spendsense.platformContext

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.ai.AiProvider
import com.jayant.spendsense.ai.detectProvider
import com.jayant.spendsense.ai.isHttpsUrl
import com.jayant.spendsense.ai.resolveAiConfig
import com.jayant.spendsense.data.ChartType
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.security.SecuritySettingsSection
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.ThemeMode
import com.jayant.spendsense.ui.theme.ThemeState
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    onOpenImport: (() -> Unit)? = null,
    onOpenConnectedApps: (() -> Unit)? = null,
    onOpenBackup: (() -> Unit)? = null,
    onOpenAccounts: (() -> Unit)? = null,
    onOpenSplitwise: (() -> Unit)? = null,
) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(context) }

    var askSplit by remember { mutableStateOf(settings.askSplitAfterPayment) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (onBack != null) 8.dp else 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        }

        Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 72.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            com.jayant.spendsense.platform.Platform.services.CaptureSettings()

            if (onOpenImport != null && com.jayant.spendsense.platform.Platform.services.importScreen != null) {
                SettingsGroup("Import") {
                    SettingsRow(
                        com.jayant.spendsense.platform.Platform.services.importRow.first,
                        com.jayant.spendsense.platform.Platform.services.importRow.second, onClick = onOpenImport,
                        trailing = { Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
            if (onOpenBackup != null || onOpenAccounts != null) {
                SettingsGroup("Your data") {
                    onOpenAccounts?.let {
                        SettingsRow("Accounts & cards", "Rename, hide or merge the cards and accounts in \"By card & account\".", onClick = it, trailing = { Arrow() })
                    }
                    onOpenBackup?.let {
                        SettingsRow("Backup", "Encrypted backup file and restore. Install updates over the top, don't uninstall.", onClick = it, trailing = { Arrow() })
                    }
                }
            }
            if (onOpenConnectedApps != null) {
                val orderStatus by com.jayant.spendsense.orders.OrderSync.status.collectAsState()
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    withContext(ioDispatcher) { com.jayant.spendsense.orders.OrderSync.refreshStatus(context) }
                }
                val connected = orderStatus.filterValues { it.connected }.keys.map { it.displayName }
                SettingsGroup("Connected apps") {
                    SettingsRow(
                        "Food & grocery orders",
                        if (connected.isEmpty()) "Zomato, Swiggy, Zepto: fill in what you ordered" else "Connected: " + connected.joinToString(", "),
                        onClick = onOpenConnectedApps,
                        trailing = { Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    onOpenSplitwise?.let {
                        val sw by com.jayant.spendsense.splitwise.SplitwiseSync.status.collectAsState()
                        androidx.compose.runtime.LaunchedEffect(Unit) { withContext(ioDispatcher) { com.jayant.spendsense.splitwise.SplitwiseSync.refreshStatus(context) } }
                        SettingsRow(
                            "Splitwise",
                            if (sw.connected) "Connected" + (sw.userName?.let { " as $it" } ?: "") else "Who paid for you, and your share as an expense",
                            onClick = it, trailing = { Arrow() },
                        )
                    }
                }
            }
            SettingsGroup("Splits") {
                SettingsRow("Ask after each payment", "Asks what it was for and whether you paid it all yourself.",
                    onClick = { askSplit = !askSplit; settings.askSplitAfterPayment = askSplit },
                ) { OnOffToggle(askSplit) { askSplit = it; settings.askSplitAfterPayment = it } }
                com.jayant.spendsense.platform.Platform.services.SplitsExtraSettings()
                // "Not a payment" rules (from the pop-up, the detail screen or a restored backup), with a way back.
                var rules by remember { mutableIntStateOf(-1) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    rules = withContext(ioDispatcher) { runCatching { com.jayant.spendsense.data.TransactionRepository.get(context).ignoreRuleCount() }.getOrDefault(0) }
                }
                if (rules > 0) {
                    SettingsRow(
                        "Ignored alerts",
                        "$rules \"Not a payment\" rule${if (rules == 1) "" else "s"}: similar alerts from those senders are skipped. Tap to clear them all.",
                        onClick = {
                            scope.launch {
                                withContext(ioDispatcher) { com.jayant.spendsense.data.TransactionRepository.get(context).clearIgnoreRules() }
                                rules = 0
                            }
                        },
                    ) { Text("CLEAR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            SettingsGroup("AI") { ApiKeyBlock(settings) }

            SettingsGroup("Security") { SecuritySettingsSection() }

            SettingsGroup("Appearance") {
                val mode by ThemeState.mode
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SquareToggle("Dark", mode == ThemeMode.DARK, { ThemePrefs.save(context, ThemeMode.DARK) })
                    SquareToggle("Light", mode == ThemeMode.LIGHT, { ThemePrefs.save(context, ThemeMode.LIGHT) })
                    SquareToggle("System", mode == ThemeMode.SYSTEM, { ThemePrefs.save(context, ThemeMode.SYSTEM) })
                }
                var chartType by remember { mutableStateOf(settings.chartType) }
                Spacer(Modifier.height(20.dp))
                AiFieldLabel("Dashboard chart")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChartType.entries.forEach { t -> SquareToggle(t.label, chartType == t, { chartType = t; settings.chartType = t }) }
                }
            }

            SettingsGroup("About") {
                val version = com.jayant.spendsense.platform.Platform.services.appVersion
                SettingsRow("SpendSense", "All data stays on this device.") {
                    Text("v$version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(16.dp))
                com.jayant.spendsense.ui.components.ScamWarningCard()
            }
        }
    }
}

@Composable
private fun Arrow() = Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiKeyBlock(settings: Settings) {
    var saved by remember { mutableStateOf(settings.aiApiKey) }
    var editing by remember { mutableStateOf(saved.isEmpty()) }
    var draft by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(settings.aiProvider) }
    var savedModel by remember { mutableStateOf(settings.aiModel) }
    var savedBase by remember { mutableStateOf(settings.aiBaseUrl) }
    var modelDraft by remember { mutableStateOf(savedModel) }
    var baseDraft by remember { mutableStateOf(savedBase) }

    val effective = provider ?: if (saved.isNotEmpty()) detectProvider(saved) else null
    val config = resolveAiConfig(saved, provider, savedModel, savedBase)
    val baseOk = effective != AiProvider.CUSTOM || isHttpsUrl(baseDraft)
    val dirty = modelDraft.trim() != savedModel || (effective == AiProvider.CUSTOM && baseDraft.trim() != savedBase)

    SettingsRow(
        "AI API key",
        if (saved.isEmpty()) "Optional. Paste a key from Claude, OpenAI, Gemini, OpenRouter, Groq, OpenCode or any OpenAI-compatible service. Without one, Ask still answers from your data offline."
        else "Saved. Ask can answer open-ended questions.",
    ) { if (!editing) StatusText(true) }
    Spacer(Modifier.height(16.dp))
    if (!editing && saved.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022 ${saved.takeLast(4)}",
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { contentDescription = "API key saved, ends in ${saved.takeLast(4)}" },
            )
            SquareToggle("Replace", false, { editing = true; draft = "" })
            SquareToggle("Remove", false, { settings.aiApiKey = ""; saved = ""; editing = true })
        }
    } else {
        SquareField(
            value = draft, onChange = { draft = it }, placeholder = effective?.keyHint ?: "Paste any AI API key",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Save key", {
                settings.aiApiKey = draft
                saved = settings.aiApiKey; draft = ""; editing = saved.isEmpty()
            }, Modifier.weight(1f), enabled = draft.isNotBlank())
            if (saved.isNotEmpty()) GhostButton("Cancel", { editing = false; draft = "" }, Modifier.weight(1f))
        }
    }

    if (config != null && !editing) {
        Spacer(Modifier.height(12.dp))
        Text(
            (if (provider == null) "Detected: " else "Using: ") + "${config.provider.displayName} \u00B7 ${config.model}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(20.dp))
    AiFieldLabel("Provider")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SquareToggle("Auto", provider == null, { provider = null; settings.aiProvider = null })
        AiProvider.entries.forEach { p ->
            SquareToggle(p.toggleLabel, provider == p, { provider = p; settings.aiProvider = p })
        }
    }

    Spacer(Modifier.height(16.dp))
    AiFieldLabel("Model")
    SquareField(
        value = modelDraft, onChange = { modelDraft = it },
        placeholder = effective?.modelFieldHint ?: "Leave blank for the provider default",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    if (effective == AiProvider.CUSTOM) {
        Spacer(Modifier.height(16.dp))
        AiFieldLabel("Base URL")
        SquareField(
            value = baseDraft, onChange = { baseDraft = it }, placeholder = "https://your-host/v1",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        if (baseDraft.isNotBlank() && !baseOk) {
            Spacer(Modifier.height(4.dp))
            Text("Base URL must start with https://", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (dirty || (effective == AiProvider.CUSTOM && baseDraft.isNotBlank() && !baseOk)) {
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Save AI settings", {
            settings.aiModel = modelDraft; savedModel = settings.aiModel; modelDraft = savedModel
            if (effective == AiProvider.CUSTOM) { settings.aiBaseUrl = baseDraft; savedBase = settings.aiBaseUrl; baseDraft = savedBase }
        }, Modifier.fillMaxWidth(), enabled = dirty && baseOk)
    }
}

@Composable
private fun AiFieldLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp),
    )
}
