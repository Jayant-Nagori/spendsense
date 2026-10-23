package com.jayant.spendsense.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.ingest.SmsBackfill
import com.jayant.spendsense.security.SecuritySettingsSection
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.ThemeMode
import com.jayant.spendsense.ui.theme.ThemeState
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

private fun smsGranted(c: Context) = c.granted(Manifest.permission.RECEIVE_SMS) && c.granted(Manifest.permission.READ_SMS)

private fun listenerGranted(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

private fun postNotificationsGranted(c: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) c.granted(Manifest.permission.POST_NOTIFICATIONS)
    else NotificationManagerCompat.from(c).areNotificationsEnabled()

@Composable
fun SettingsScreen(onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(context) }

    // Bumped on every resume so permission rows reflect changes made in system settings.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val sms = remember(tick) { smsGranted(context) }
    val listener = remember(tick) { listenerGranted(context) }
    val notifs = remember(tick) { postNotificationsGranted(context) }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    var askSplit by remember { mutableStateOf(settings.askSplitAfterPayment) }
    var months by remember { mutableIntStateOf(6) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }

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
            SettingsGroup("Capture") {
                SettingsRow("Bank SMS", "Reads payment alerts from your bank as they arrive.",
                    onClick = if (sms) null else ({ smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }),
                ) { StatusChevron(sms) }
                SettingsRow("Payment app notifications", "Catches UPI payments from GPay, PhonePe and others.",
                    onClick = if (listener) null else ({
                        context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }),
                ) { StatusChevron(listener) }
                SettingsRow("Show notifications", "Needed to ask whether each payment was all yours.",
                    onClick = if (notifs) null else ({
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }),
                ) { StatusChevron(notifs) }
            }

            SettingsGroup("History") {
                SettingsRow("Import past SMS", "Added as already answered, so you won't be asked about them.")
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 6, 12).forEach { m -> SquareToggle("$m months", months == m, { months = m }) }
                }
                PrimaryButton(
                    if (importing) "Importing..." else "Import",
                    enabled = sms && !importing,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    onClick = {
                        importing = true; importResult = null
                        scope.launch {
                            val since = System.currentTimeMillis() - months * 30L * 86_400_000L
                            val count = withContext(Dispatchers.IO) { runCatching { SmsBackfill.run(context.applicationContext, since) }.getOrNull() }
                            importing = false
                            if (count == null) importResult = "Import failed. Check the SMS permission and try again."
                            else {
                                settings.smsBackfillDone = true
                                importResult = if (count == 1) "Imported 1 transaction" else "Imported $count transactions"
                            }
                        }
                    },
                )
                if (!sms) {
                    Text("Allow Bank SMS first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                AnimatedVisibility(visible = importResult != null) {
                    Text(
                        importResult.orEmpty(), style = MaterialTheme.typography.titleSmall,
                        color = if (importResult?.startsWith("Imported") == true) MaterialTheme.spend.credit else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp).semantics { contentDescription = importResult.orEmpty() },
                    )
                }
            }

            SettingsGroup("Splits") {
                SettingsRow("Ask after each payment", "A notification asks whether you paid it all yourself.",
                    onClick = { askSplit = !askSplit; settings.askSplitAfterPayment = askSplit },
                ) { OnOffToggle(askSplit) { askSplit = it; settings.askSplitAfterPayment = it } }
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
            }

            SettingsGroup("About") {
                val version = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
                }
                SettingsRow("SpendSense", "All data stays on this phone.") {
                    Text("v$version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ApiKeyBlock(settings: Settings) {
    var saved by remember { mutableStateOf(settings.anthropicApiKey) }
    var editing by remember { mutableStateOf(saved.isEmpty()) }
    var draft by remember { mutableStateOf("") }

    SettingsRow(
        "Anthropic API key",
        if (saved.isEmpty()) "Optional. Without one, Ask still answers from your data offline." else "Saved. Ask can answer open-ended questions.",
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
            SquareToggle("Remove", false, { settings.anthropicApiKey = ""; saved = ""; editing = true })
        }
    } else {
        SquareField(
            value = draft, onChange = { draft = it }, placeholder = "sk-ant-...",
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton("Save key", {
                settings.anthropicApiKey = draft
                saved = settings.anthropicApiKey; draft = ""; editing = saved.isEmpty()
            }, Modifier.weight(1f), enabled = draft.isNotBlank())
            if (saved.isNotEmpty()) GhostButton("Cancel", { editing = false; draft = "" }, Modifier.weight(1f))
        }
    }
}

@Suppress("unused")
private val keepUri = Uri.EMPTY
