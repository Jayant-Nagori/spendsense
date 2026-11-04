package com.jayant.spendsense

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.imports.ImportScreen
import com.jayant.spendsense.imports.email.EmailImportSection
import com.jayant.spendsense.ingest.SmsBackfill
import com.jayant.spendsense.platform.PlatformServices
import com.jayant.spendsense.security.AndroidLockLifecycle
import com.jayant.spendsense.security.Authenticator
import com.jayant.spendsense.security.DataWipe
import com.jayant.spendsense.ui.OnOffToggle
import com.jayant.spendsense.ui.SettingsGroup
import com.jayant.spendsense.ui.SettingsRow
import com.jayant.spendsense.ui.SquareToggle
import com.jayant.spendsense.ui.StatusChevron
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Android side of the shared UI: permissions, biometric prompt, wipe, imports. */
class AndroidPlatformServices(private val app: Application) : PlatformServices {

    override val appVersion: String = BuildConfig.VERSION_NAME

    override val isDebugBuild: Boolean = BuildConfig.DEBUG

    override val authBrowser: com.jayant.spendsense.orders.AuthBrowser = com.jayant.spendsense.orders.AndroidAuthBrowser(app)

    // Emulator host loopback; constant-folded away in release (BuildConfig.DEBUG is false there).
    override val defaultFakeOrderServer: String = if (BuildConfig.DEBUG) "http://10.0.2.2:8787" else ""

    override fun onOrderConnectionsChanged() = com.jayant.spendsense.orders.OrderSyncJobService.sync(app)

    override val unlockMethodLabel = "fingerprint, face or screen lock"

    override val encryptionBlurb =
        "Data is stored in an AES-256 encrypted database; the key never leaves the device's secure hardware."

    override fun isDeviceSecure() = Authenticator.isDeviceSecure(app)

    override fun authenticate(title: String, subtitle: String?, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val activity = AndroidLockLifecycle.currentActivity ?: return onError("Open SpendSense and try again.")
        Authenticator.prompt(activity, title, subtitle, onSuccess = onSuccess, onError = { _, msg -> onError(msg) })
    }

    override fun wipeAllData() {
        AndroidLockLifecycle.currentActivity?.let { DataWipe.wipeAndRestart(it) }
    }

    override fun cancelPaymentNotification(txnId: Long) {
        NotificationManagerCompat.from(app).cancel(txnId.toInt())
    }

    override val importScreen: (@Composable (onBack: () -> Unit) -> Unit) = { onBack ->
        ImportScreen(onBack = onBack, emailSection = { EmailImportSection() })
    }

    @Composable
    override fun CaptureSettings() = AndroidCaptureSettings()

    @Composable
    override fun SplitsExtraSettings() = AndroidPopupSetting()

    @Composable
    override fun rememberBackupFiles(): com.jayant.spendsense.platform.BackupFiles = com.jayant.spendsense.backup.rememberAndroidBackupFiles()

    @Composable
    override fun AutoBackupSettings() = com.jayant.spendsense.backup.AndroidAutoBackupSettings()

    override fun resetUnreadableStorage(keyLost: Boolean) = com.jayant.spendsense.data.TransactionDb.setAside(app, keyLost)

    override fun restartApp() {
        val activity = AndroidLockLifecycle.currentActivity
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            app.startActivity(it)
        }
        activity?.finishAffinity()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
    }

    override fun onSplitwiseChanged() = com.jayant.spendsense.orders.OrderSyncJobService.sync(app)
}

private fun Context.granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

private fun smsGranted(c: Context) = c.granted(Manifest.permission.RECEIVE_SMS) && c.granted(Manifest.permission.READ_SMS)

private fun listenerGranted(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

private fun postNotificationsGranted(c: Context) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) c.granted(Manifest.permission.POST_NOTIFICATIONS)
    else NotificationManagerCompat.from(c).areNotificationsEnabled()

/** Bumped on every resume so permission rows reflect changes made in system settings. */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

@Composable
private fun AndroidCaptureSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { Settings(context) }
    var bump by remember { mutableIntStateOf(0) }
    val tick = rememberResumeTick() + bump
    val sms = remember(tick) { smsGranted(context) }
    val listener = remember(tick) { listenerGranted(context) }
    val notifs = remember(tick) { postNotificationsGranted(context) }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { bump++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { bump++ }

    var months by remember { mutableIntStateOf(6) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(40.dp)) {
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
                            val since = currentTimeMillis() - months * 30L * 86_400_000L
                            val count = withContext(ioDispatcher) { runCatching { SmsBackfill.run(context.applicationContext, since) }.getOrNull() }
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
    }
}

@Composable
private fun AndroidPopupSetting() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val tick = rememberResumeTick()
                val overlayAllowed = remember(tick) { AndroidSettings.canDrawOverlays(context) }
                var popup by remember { mutableStateOf(settings.paymentPopup) }
                fun setPopup(on: Boolean) {
                    if (on && !overlayAllowed) {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    popup = on; settings.paymentPopup = on
                }
                SettingsRow(
                    "Full-screen pop-up",
                    if (overlayAllowed) "Takes over the screen right after you pay, over any app."
                    else "Needs \"Display over other apps\". Tap to allow.",
                    onClick = { setPopup(!(popup && overlayAllowed)) },
                ) { OnOffToggle(popup && overlayAllowed) { setPopup(it) } }
    if (Build.VERSION.SDK_INT >= 34) {
        val fsi = remember(tick) { com.jayant.spendsense.notify.PaymentPopup.canUseFullScreen(context) }
        SettingsRow(
            "Pop-up when locked",
            if (fsi) "Opens like an incoming call when the screen is off or locked." else "Needs \"Full-screen notifications\". Tap to allow.",
            onClick = if (fsi) null else ({ openFullScreenIntentSettings(context) }),
        ) { StatusChevron(fsi) }
    }
    var keepRunning by remember { mutableStateOf(settings.captureService) }
    fun setKeep(on: Boolean) {
        keepRunning = on; settings.captureService = on
        com.jayant.spendsense.ingest.CaptureService.sync(context)
    }
    SettingsRow(
        "Keep watching in the background",
        "A silent \"watching for payments\" notification stops the phone from putting SpendSense to sleep, so pop-ups are on time.",
        onClick = { setKeep(!keepRunning) },
    ) { OnOffToggle(keepRunning) { setKeep(it) } }
}

/** Android 14+: Settings › Apps › SpendSense › Full-screen notifications (falls back to App info). */
fun openFullScreenIntentSettings(context: Context) {
    val pkg = android.net.Uri.parse("package:" + context.packageName)
    val i = Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(i) }.onFailure {
        runCatching { context.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
