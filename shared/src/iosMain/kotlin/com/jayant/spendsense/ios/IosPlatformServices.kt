package com.jayant.spendsense.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.BuildInfo
import com.jayant.spendsense.IosContext
import com.jayant.spendsense.data.keyValueStore
import com.jayant.spendsense.data.wipeIosData
import com.jayant.spendsense.platform.PlatformServices
import com.jayant.spendsense.ui.SettingsGroup
import com.jayant.spendsense.ui.SettingsRow
import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LABiometryTypeFaceID
import platform.LocalAuthentication.LABiometryTypeTouchID
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.UIKit.UIApplication
import platform.UIKit.UISelectionFeedbackGenerator
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.exit

/** iOS side of the shared UI. */
@OptIn(ExperimentalForeignApi::class)
internal object IosPlatformServices : PlatformServices {
    override val appVersion: String = BuildInfo.VERSION_NAME

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    override val isDebugBuild: Boolean = kotlin.native.Platform.isDebugBinary

    override val authBrowser: com.jayant.spendsense.orders.AuthBrowser = com.jayant.spendsense.orders.IosAuthBrowser

    @OptIn(kotlin.experimental.ExperimentalNativeApi::class)
    override val defaultFakeOrderServer: String = if (kotlin.native.Platform.isDebugBinary) "http://127.0.0.1:8787" else ""

    override val unlockMethodLabel: String
        get() {
            val ctx = LAContext()
            ctx.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)
            return when (ctx.biometryType) {
                LABiometryTypeFaceID -> "Face ID or passcode"
                LABiometryTypeTouchID -> "Touch ID or passcode"
                else -> "device passcode"
            }
        }

    override val emptyStateHint =
        "Bank SMS show up here once the Shortcuts automation is set up (Settings > Capture). You can also add one by hand."

    override val encryptionBlurb =
        "Data is stored in a database protected by iOS Data Protection (encrypted with your passcode while the phone is locked); " +
            "your AI key is kept in the Keychain on this device only."

    override fun isDeviceSecure(): Boolean = LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, null)

    override fun authenticate(title: String, subtitle: String?, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val reason = listOfNotNull(title, subtitle).joinToString(". ")
        LAContext().evaluatePolicy(LAPolicyDeviceOwnerAuthentication, reason) { ok, error ->
            dispatch_async(dispatch_get_main_queue()) {
                if (ok) onSuccess() else onError(error?.localizedDescription ?: "Authentication failed")
            }
        }
    }

    override fun wipeAllData() {
        wipeIosData()
        listOf("settings", "ui_prefs", "orders", "splitwise", "backup").forEach { keyValueStore(IosContext, it).clear() }
        UNUserNotificationCenter.currentNotificationCenter().removeAllDeliveredNotifications()
        UNUserNotificationCenter.currentNotificationCenter().removeAllPendingNotificationRequests()
        // Repositories hold the old database handle; iOS cannot relaunch an app, so quit and let the user reopen it.
        exit(0)
    }

    override fun cancelPaymentNotification(txnId: Long) = IosApp.cancelNotification(txnId)

    @Composable
    override fun rememberBackupFiles(): com.jayant.spendsense.platform.BackupFiles = IosBackupFiles

    override fun resetUnreadableStorage(keyLost: Boolean) = com.jayant.spendsense.data.setAsideIosDatabase()

    /** iOS can't relaunch itself: re-check storage so the root swaps the recovery screen for the app. */
    override fun restartApp() = IosApp.recheckStorage()

    override fun selectionHaptic() = UISelectionFeedbackGenerator().selectionChanged()

    override val importRow = "Bank statements" to "Import a PDF or CSV statement from Files"

    override val importScreen: (@Composable (onBack: () -> Unit) -> Unit)? = { onBack -> IosImportScreen(onBack) }

    @Composable
    override fun CaptureSettings() {
        SettingsGroup("Capture") {
            SettingsRow(
                "Bank SMS via Shortcuts",
                "iPhone apps can't read SMS. Add an automation once and every bank alert is logged automatically:",
            )
            Column(Modifier.padding(top = 12.dp)) {
                listOf(
                    "1. Open Shortcuts > Automation > + > Message.",
                    "2. Message Contains: debited (add another for \"spent\" if your bank uses it). Choose Run Immediately.",
                    "3. Add action \"Log bank SMS\" (SpendSense) and pass Shortcut Input > Content.",
                    "4. Done. SpendSense opens on the payment so you can say what it was for.",
                ).forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            SettingsRow("Manual entry", "Cash and anything the automation missed: + ADD on Home.")
        }
    }

    @Suppress("unused")
    private fun app() = UIApplication.sharedApplication
}
