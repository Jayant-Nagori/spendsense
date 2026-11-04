package com.jayant.spendsense.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import com.jayant.spendsense.ui.AppBackHandler
import com.jayant.spendsense.ui.edgeSwipeBack
import androidx.compose.ui.window.ComposeUIViewController
import com.jayant.spendsense.AppScope
import com.jayant.spendsense.IosContext
import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.ingest.IngestEvents
import com.jayant.spendsense.ingest.TransactionIngestor
import com.jayant.spendsense.ingest.TransactionParser
import com.jayant.spendsense.notify.PaymentPopupContent
import com.jayant.spendsense.platform.Platform
import com.jayant.spendsense.security.AppLockGate
import com.jayant.spendsense.security.LockState
import com.jayant.spendsense.ui.AppShell
import com.jayant.spendsense.ui.ThemePrefs
import com.jayant.spendsense.ui.components.formatRupees
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import com.jayant.spendsense.ui.theme.resolveDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.UIKit.UIViewController
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS entry points called from Swift (SpendSenseApp.swift / LogBankSmsIntent.swift). Exposed to Swift as
 * `IosApp.shared`. Never logs message text.
 */
object IosApp {
    private var started = false

    /** The payment whose full-screen pop-up is showing (or about to). Observed by [Root]. */
    internal val popup = mutableStateOf<Transaction?>(null)

    /** Whether the database opened; null until checked. Anything but OK shows the recovery screen. */
    internal val storage = mutableStateOf<com.jayant.spendsense.security.StorageHealth.State?>(null)

    internal fun recheckStorage() {
        AppScope.launch {
            storage.value = withContext(Dispatchers.IO) {
                com.jayant.spendsense.security.StorageHealth.check { TransactionRepository.get(IosContext).pendingReview() }
            }
        }
    }

    /** Swift sets this to keep the status bar in step with the chosen theme. */
    var onThemeChanged: ((Boolean) -> Unit)? = null

    fun start() {
        if (started) return
        started = true
        Platform.install(IosPlatformServices)
        ThemePrefs.load(IosContext)
        LockState.ensureInit(IosContext)
        TransactionIngestor.events = object : IngestEvents {
            override fun onRefundLinked(context: PlatformContext, credit: Transaction, purchase: Transaction) {
                notify("refund-${credit.id}", "Refund of ${formatRupees(credit.amount)} received",
                    "Matched to your ${formatRupees(purchase.amount)} payment to ${purchase.counterparty ?: "a merchant"}", null)
            }

            override fun onNewDebit(context: PlatformContext, txn: Transaction) {
                notify("txn-${txn.id}", "${formatRupees(txn.amount)} to ${com.jayant.spendsense.data.Payee.display(txn.counterparty) ?: "someone"}",
                    "What was it for? Tap to answer.", txn.id)
                present(txn)
            }
        }
    }

    /** Debug builds: `spendsense://debug/...` QA links (see DebugLinks). */
    fun debugLink(url: String) = com.jayant.spendsense.ui.DebugLinks.handle(IosContext, url)

    fun onForeground() = LockState.onAppForeground(IosContext)

    fun onBackground() = LockState.onAppBackground()

    /**
     * The "Log bank SMS" App Intent (and the debug deep link). Parses [text] with the shared SMS parser, stores it
     * (deduplicated) and, for a new debit, opens the payment pop-up. Returns a short spoken/visible result.
     * Blocking — Swift calls it off the main actor.
     */
    fun logBankSms(text: String, sender: String?): String {
        start()
        val body = text.trim()
        if (body.isEmpty()) return "No message text was passed in."
        val parsed = TransactionParser.parseSms(sender.orEmpty(), body, currentTimeMillis())
            ?: return "That message doesn't look like a completed payment."
        val stored = runCatching {
            TransactionIngestor.ingest(IosContext, parsed, TxnSource.SMS, sender?.takeIf { it.isNotBlank() } ?: "Shortcuts", body, live = true)
        }.getOrElse { return "Couldn't save it. Open SpendSense and try again." }
            ?: return "Already logged."
        val what = "${formatRupees(stored.amount)} ${if (stored.type == TxnType.DEBIT) "to" else "from"} ${stored.counterparty ?: "unknown"}"
        return if (stored.type == TxnType.DEBIT) "Logged $what." else "Logged $what (money in)."
    }

    /** A notification for [txnId] was tapped: show its pop-up if it still needs an answer. */
    fun openTransaction(txnId: Long) {
        start()
        AppScope.launch {
            val t = withContext(Dispatchers.IO) { runCatching { TransactionRepository.get(IosContext).get(txnId) }.getOrNull() }
            if (t != null && !t.reviewed) present(t)
        }
    }

    private val queue = com.jayant.spendsense.notify.PopupQueue()
    private val queueLock = com.jayant.spendsense.ReentrantLock()

    /** Shows [txn] now, or after the pop-up that is already open (same rule as Android's overlay queue). */
    private fun present(txn: Transaction) {
        val showNow = queueLock.withLockCompat {
            val cur = popup.value
            if (cur == null || cur.id == txn.id) true else { queue.offer(txn.id); false }
        }
        if (showNow) popup.value = txn
    }

    /** The open pop-up was answered or dismissed: show the next queued payment that still needs an answer. */
    internal fun popupClosed(id: Long) {
        if (popup.value?.id == id) popup.value = null
        AppScope.launch {
            while (true) {
                val next = queue.poll() ?: break
                val t = withContext(Dispatchers.IO) { runCatching { TransactionRepository.get(IosContext).get(next) }.getOrNull() }
                if (t != null && !t.reviewed) { popup.value = t; break }
            }
        }
    }

    private inline fun <T> com.jayant.spendsense.ReentrantLock.withLockCompat(block: () -> T): T {
        lock(); try { return block() } finally { unlock() }
    }

    private fun notify(id: String, title: String, body: String, txnId: Long?) {
        if (txnId != null && !Settings(IosContext).askSplitAfterPayment) return
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setSound(UNNotificationSound.defaultSound)
            if (txnId != null) setUserInfo(mapOf<Any?, Any?>("txnId" to txnId.toString()))
        }
        val req = UNNotificationRequest.requestWithIdentifier(id, content, null)
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(req, null)
    }

    internal fun cancelNotification(txnId: Long) {
        UNUserNotificationCenter.currentNotificationCenter().apply {
            removeDeliveredNotificationsWithIdentifiers(listOf("txn-$txnId"))
            removePendingNotificationRequestsWithIdentifiers(listOf("txn-$txnId"))
        }
    }
}

/** Compose root hosted by SwiftUI. Same shell as Android, plus the in-app payment pop-up layer. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun Root() {
    val dark = resolveDark()
    LaunchedEffect(dark) { IosApp.onThemeChanged?.invoke(dark) }
    SpendSenseTheme(dark) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).edgeSwipeBack()) {
            var safetySeen by remember { mutableStateOf(com.jayant.spendsense.ui.components.FirstRunSafety.seen(IosContext)) }
            if (!safetySeen) {
                com.jayant.spendsense.ui.components.FirstRunSafetyScreen {
                    com.jayant.spendsense.ui.components.FirstRunSafety.markSeen(IosContext)
                    safetySeen = true
                }
                return@Box
            }
            val storage by IosApp.storage
            LaunchedEffect(Unit) { if (storage == null) IosApp.recheckStorage() }
            when (storage) {
                null -> return@Box
                com.jayant.spendsense.security.StorageHealth.State.OK -> Unit
                else -> { AppLockGate { com.jayant.spendsense.ui.RecoveryScreen(storage!!) }; return@Box }
            }
            AppLockGate {
                AppShell()
                val txn by IosApp.popup
                txn?.let { t ->
                    key(t.id) {
                        val close = { IosApp.popupClosed(t.id) }
                        AppBackHandler { close() }
                        SpendSenseTheme(darkTheme = true) { PaymentPopupContent(t, onClose = close) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController {
    IosApp.start()
    // Back = left-edge swipe, handled in Compose by Modifier.edgeSwipeBack (Root) for every AppBackHandler.
    return ComposeUIViewController(configure = {
        enableBackGesture = false
    }) { Root() }
}
