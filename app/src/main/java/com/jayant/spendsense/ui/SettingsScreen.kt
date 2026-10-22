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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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
import com.jayant.spendsense.ui.components.Pill
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.SpendCard
import com.jayant.spendsense.ui.components.SpendChip
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.components.staggeredEntrance
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
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(context) }

    // Bumped on every resume so permission cards reflect changes made in system settings.
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${listOf(sms, listener, notifs).count { it }} of 3 permissions on",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Group("Capture payments", Modifier.staggeredEntrance(0)) {
            PermissionRow(
                Icons.Rounded.Sms, "Bank SMS", "Reads payment alerts from your bank as they arrive.",
                granted = sms, action = "Allow",
            ) { smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) }
            Divider()
            PermissionRow(
                Icons.Rounded.NotificationsActive, "Payment app notifications",
                "Catches UPI payments from apps like GPay and PhonePe. Turn on SpendSense in the list.",
                granted = listener, action = "Open settings",
            ) { context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Divider()
            PermissionRow(
                Icons.Rounded.Notifications, "Show notifications", "Needed to ask whether each payment was all yours.",
                granted = notifs, action = "Allow",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(
                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }

        Group("Past payments", Modifier.staggeredEntrance(1)) {
            Text(
                "Pull older bank SMS into your history. These are added as already answered, so you won't be asked about them.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 6, 12).forEach { m -> SpendChip("$m months", months == m, { months = m }) }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                if (importing) "Importing..." else "Import past SMS",
                enabled = sms && !importing,
                modifier = Modifier.fillMaxWidth(),
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

        Group("Splits", Modifier.staggeredEntrance(2)) {
            SwitchRow(
                "Ask about splits after each payment",
                "A notification asks whether you paid it all yourself.",
                askSplit,
            ) { askSplit = it; settings.askSplitAfterPayment = it }
        }

        Group("AI assistant", Modifier.staggeredEntrance(3)) { ApiKeyBlock(settings) }

        Group("Appearance", Modifier.staggeredEntrance(4)) {
            val mode by ThemeState.mode
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpendChip("Dark", mode == ThemeMode.DARK, { ThemePrefs.save(context, ThemeMode.DARK) })
                SpendChip("Light", mode == ThemeMode.LIGHT, { ThemePrefs.save(context, ThemeMode.LIGHT) })
                SpendChip("System", mode == ThemeMode.SYSTEM, { ThemePrefs.save(context, ThemeMode.SYSTEM) })
            }
        }

        // Security is owned by the security agent; it brings its own heading.
        SpendCard(Modifier.staggeredEntrance(5)) { SecuritySettingsSection() }

        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"
        }
        Text(
            "SpendSense $version  ·  all data stays on this phone",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun Group(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 12.dp))
        SpendCard(contentPadding = 20.dp) { content() }
    }
}

@Composable
private fun Divider() {
    Spacer(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(MaterialTheme.spend.hairline))
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, body: String, granted: Boolean, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val tint = if (granted) MaterialTheme.spend.credit else MaterialTheme.colorScheme.primary
        Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(if (granted) Icons.Rounded.Check else icon, null, tint = tint)
        }
        Column(Modifier.weight(1f).animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                if (granted) Pill("On", MaterialTheme.spend.credit.copy(alpha = 0.14f), MaterialTheme.spend.credit)
            }
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) {
                Spacer(Modifier.height(6.dp))
                GhostButton(action, onAction, Modifier.heightIn(min = 48.dp), color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .pressable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ApiKeyBlock(settings: Settings) {
    var saved by remember { mutableStateOf(settings.anthropicApiKey) }
    var editing by remember { mutableStateOf(saved.isEmpty()) }
    var draft by remember { mutableStateOf("") }

    Text(
        "Add an Anthropic API key to let the assistant answer open-ended questions. Without one it still answers from your data offline.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    if (!editing && saved.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Saved key", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "•••••••• ${saved.takeLast(4)}",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { contentDescription = "API key saved, ends in ${saved.takeLast(4)}" },
                )
            }
            GhostButton("Replace", { editing = true; draft = "" })
            GhostButton("Remove", { settings.anthropicApiKey = ""; saved = ""; editing = true }, color = MaterialTheme.colorScheme.error)
        }
    } else {
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, label = { Text("Anthropic API key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
@Suppress("unused")
private val keepCircle = CircleShape
