package com.jayant.spendsense.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.ingest.PaymentNotificationListener
import com.jayant.spendsense.ingest.SmsBackfill
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.HairlineDivider
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.spend

private const val PREFS = "ui_prefs"
private const val KEY_SETUP_DONE = "setup_done"
private const val BACKFILL_MONTHS = 3

fun isSetupDone(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false)

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

private fun smsOk(c: Context) = c.granted(Manifest.permission.RECEIVE_SMS) && c.granted(Manifest.permission.READ_SMS)

private fun notifsOk(c: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) c.granted(Manifest.permission.POST_NOTIFICATIONS)
    else NotificationManagerCompat.from(c).areNotificationsEnabled()

private fun listenerOk(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

private fun batteryOk(c: Context) = c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == true

private fun overlayOk(c: Context) = AndroidSettings.canDrawOverlays(c)

private fun fullScreenOk(c: Context) = com.jayant.spendsense.notify.PaymentPopup.canUseFullScreen(c)

/** Android 14+ asks the user for full-screen notifications; older versions grant them at install. */
private val NEEDS_FSI_STEP = Build.VERSION.SDK_INT >= 34

private fun Context.open(intent: Intent, fallback: Intent? = null) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { fallback?.let { f -> runCatching { startActivity(f.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } }
}

private fun appInfo(c: Context) = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName))

private const val RESTRICTED_HELP =
    "Greyed out or says \"Restricted setting\"? Tap App info, then ⋮ (top right) → Allow restricted settings, come back and try again."

/**
 * First-launch checklist: asks for everything capture needs in one place — bank SMS, pop-up notifications,
 * payment-app notification access and unrestricted battery — then imports recent SMS history.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val sms = remember(tick) { smsOk(context) }
    val notifs = remember(tick) { notifsOk(context) }
    val listener = remember(tick) { listenerOk(context) }
    val battery = remember(tick) { batteryOk(context) }
    val overlay = remember(tick) { overlayOk(context) }
    val fullScreen = remember(tick) { fullScreenOk(context) }
    // Apps installed from a browser on Android 13+ get SMS / notification access blocked as "restricted settings".
    var smsRefused by remember { mutableStateOf(false) }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        smsRefused = r.values.any { !it }; tick++
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun finish() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SETUP_DONE, true).apply()
        val app = context.applicationContext
        val settings = Settings(app)
        if (smsOk(app) && !settings.smsBackfillDone) {
            Thread({
                val since = System.currentTimeMillis() - BACKFILL_MONTHS * 30L * 86_400_000
                runCatching { SmsBackfill.run(app, since) }.onSuccess { settings.smsBackfillDone = true }
            }, "setup-backfill").start()
        }
        onDone()
    }

    val steps = listOfNotNull(sms, notifs, overlay, listener, battery, if (NEEDS_FSI_STEP) fullScreen else null)
    val doneCount = steps.count { it }
    val total = steps.size

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp).padding(top = 32.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SETUP  ·  $doneCount OF $total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Let's catch every payment", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Allow these once. Everything stays encrypted on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        com.jayant.spendsense.ui.components.ScamWarningCard()

        Column {
            SetupStep(
                1, "Read bank SMS", "Card, UPI, ATM and net-banking alerts from your bank, plus the last $BACKFILL_MONTHS months of history.",
                done = sms, action = "ALLOW",
                onAction = { smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)) },
                help = if (!sms && smsRefused) RESTRICTED_HELP else null,
                onHelp = { context.open(appInfo(context)) },
            )
            SetupStep(
                2, "Pop up after each payment", "Asks what it was for and whether it was all yours.",
                done = notifs, action = "ALLOW",
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.open(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
            SetupStep(
                3, "Full-screen pop-up over any app", "The moment you pay, SpendSense takes over the screen to ask what it was for. Turn on \"Allow display over other apps\".",
                done = overlay, action = "OPEN",
                onAction = {
                    context.open(
                        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)),
                        fallback = appInfo(context),
                    )
                },
            )
            SetupStep(
                4, "Read GPay, PhonePe & bank app alerts", "Catches UPI payments instantly, even before the bank SMS. Turn SpendSense on in the next screen.",
                done = listener, action = "OPEN",
                onAction = {
                    val list = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val cn = ComponentName(context, PaymentNotificationListener::class.java).flattenToString()
                        context.open(
                            Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn),
                            fallback = list,
                        )
                    } else context.open(list)
                },
                help = if (!listener) RESTRICTED_HELP else null,
                onHelp = { context.open(appInfo(context)) },
            )
            SetupStep(
                5, "Keep running in the background", "So the phone's battery saver never puts SpendSense to sleep and misses a payment.",
                done = battery, action = "ALLOW",
                onAction = {
                    context.open(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)),
                        fallback = appInfo(context),
                    )
                },
                last = !NEEDS_FSI_STEP,
            )
            if (NEEDS_FSI_STEP) {
                SetupStep(
                    6, "Pop up when the phone is locked", "Lets the payment pop-up open like an incoming call when the screen is off. Turn on \"Full-screen notifications\".",
                    done = fullScreen, action = "OPEN",
                    onAction = { com.jayant.spendsense.openFullScreenIntentSettings(context) },
                    last = true,
                )
            }
        }

        PrimaryButton(if (doneCount == total) "Start" else "Continue", ::finish, Modifier.fillMaxWidth())
        if (doneCount < total) {
            Text(
                "You can finish the rest later in Settings → Capture.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupStep(
    n: Int,
    title: String,
    body: String,
    done: Boolean,
    action: String,
    onAction: () -> Unit,
    help: String? = null,
    onHelp: () -> Unit = {},
    last: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                "0$n", style = MaterialTheme.typography.labelLarge,
                color = if (done) MaterialTheme.spend.credit else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp).padding(top = 2.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (done) {
                Text(
                    "DONE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.spend.credit,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
        if (!done) {
            Row(Modifier.padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(action, onAction, Modifier.weight(1f))
                if (help != null) GhostButton("App info", onHelp, Modifier.weight(1f))
            }
            help?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 36.dp))
            }
        }
    }
    if (!last) HairlineDivider()
}
