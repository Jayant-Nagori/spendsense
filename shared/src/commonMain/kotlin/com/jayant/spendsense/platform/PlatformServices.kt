package com.jayant.spendsense.platform

import androidx.compose.runtime.Composable
import com.jayant.spendsense.data.Transaction

/**
 * Everything the shared UI needs from the host OS. Installed once at start-up: Android in SpendSenseApp.onCreate,
 * iOS in MainViewController. Keeping it one interface makes the per-platform surface easy to audit.
 */
interface PlatformServices {
    /** User-visible version ("1.1"). */
    val appVersion: String

    /** Name for the unlock method in copy: "Face ID", "fingerprint, face or screen lock". */
    val unlockMethodLabel: String

    /** Sentence about how data at rest is protected on this platform (Settings > Security). */
    val encryptionBlurb: String

    /** Home empty state: how payments arrive on this platform. */
    val emptyStateHint: String
        get() = "Payments from SMS and payment-app notifications show up here on their own. You can also add one by hand."

    /** True when the device has a passcode / screen lock, so app lock can actually verify the user. */
    fun isDeviceSecure(): Boolean

    /** Biometric / device-credential prompt. Callbacks run on the main thread. */
    fun authenticate(title: String, subtitle: String?, onSuccess: () -> Unit, onError: (String) -> Unit)

    /** Irreversibly deletes the database, keys, settings and notifications, then restarts / resets the app. */
    fun wipeAllData()

    /** Removes the "what was it for?" notification for a transaction once it has been answered in the UI. */
    fun cancelPaymentNotification(txnId: Long)

    /** Light tick used by toggles and the tab bar (UIKit selection feedback on iOS). */
    fun selectionHaptic() {}

    /** Settings: how payments get captured (Android: SMS / notification access / history; iOS: Shortcuts setup). */
    @Composable
    fun CaptureSettings()

    /** Settings > Splits: extra platform rows (Android: "Full-screen pop-up over other apps"). */
    @Composable
    fun SplitsExtraSettings() {}

    /** Settings row for [importScreen]: title and one line of description. */
    val importRow: Pair<String, String> get() = "Bank statements & Gmail" to "Import PDF/CSV statements or bank alert emails"

    /** Full-screen import UI (statements / email), or null when this platform has none yet. */
    val importScreen: (@Composable (onBack: () -> Unit) -> Unit)?

    /** True in debug builds (enables the fake order server switch). */
    val isDebugBuild: Boolean get() = false

    /** System browser sign-in for Connected apps (Custom Tabs / ASWebAuthenticationSession); null if unsupported. */
    val authBrowser: com.jayant.spendsense.orders.AuthBrowser? get() = null

    /** Debug fake order server as seen from this device (emulator: host loopback is 10.0.2.2). */
    val defaultFakeOrderServer: String get() = ""

    /** A delivery app was connected or disconnected (Android: (un)schedule the periodic order sync). */
    fun onOrderConnectionsChanged() {}

    /** Shared screens call this after a debit is stored from the UI (e.g. manual entry) — no-op by default. */
    fun onTransactionStored(txn: Transaction) {}

    /** Save / open backup files: Storage Access Framework on Android, the document picker on iOS. Null: unsupported. */
    @Composable
    fun rememberBackupFiles(): BackupFiles? = null

    /** Settings › Backup: platform extras (Android: weekly auto-backup to a folder). */
    @Composable
    fun AutoBackupSettings() {}

    /**
     * Recovery: the database can't be opened (e.g. its key was lost). Moves the unreadable files aside (never deletes
     * them) so the next open creates a fresh empty database. The old key is kept with the set-aside file unless it is the
     * part that's broken ([keyLost]). Blocking.
     */
    fun resetUnreadableStorage(keyLost: Boolean) {}

    /** Restart (Android) / reload (iOS) after a recovery so every screen opens the new database. */
    fun restartApp() {}

    /** Splitwise was connected or disconnected (Android: (un)schedule the periodic sync). */
    fun onSplitwiseChanged() {}
}

/** Platform file pickers for backups. Callbacks run on the main thread. */
interface BackupFiles {
    /** Lets the user choose where to save [bytes] (suggested [name]); [done] gets true when written. */
    fun save(name: String, bytes: ByteArray, done: (Boolean) -> Unit)

    /** Lets the user pick a backup file; [done] gets its bytes (capped), or null when cancelled / unreadable. */
    fun open(done: (ByteArray?) -> Unit)
}

object Platform {
    private var installed: PlatformServices? = null

    val services: PlatformServices
        get() = installed ?: error("PlatformServices not installed")

    fun install(services: PlatformServices) { installed = services }
}
