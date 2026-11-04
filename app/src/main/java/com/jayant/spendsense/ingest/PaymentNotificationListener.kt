package com.jayant.spendsense.ingest

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jayant.spendsense.data.TxnSource
import java.util.concurrent.Executors

/** Watches only whitelisted UPI / bank / wallet apps for completed payments. Never logs contents. */
class PaymentNotificationListener : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()

    /** Last text seen per notification key: apps re-post/update the same notification, which is not a new payment. */
    private val lastTextByKey = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (com.jayant.spendsense.security.AppIntegrity.tampered) return
        val receivedAt = android.os.SystemClock.elapsedRealtime()
        val pkg = sbn.packageName?.let { sbn.debugAlias(it) } ?: return
        if (pkg !in TransactionParser.PAYMENT_PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return
        val postTime = sbn.postTime
        val app = applicationContext
        val key = sbn.key
        worker.execute {
            // Only a fingerprint is kept in memory, never the notification text (which may be an OTP).
            val content = com.jayant.spendsense.data.Dedupe.fingerprint("$title\u0000$text")
            if (lastTextByKey[key] == content) return@execute
            lastTextByKey[key] = content
            val parsed = TransactionParser.parseNotification(pkg, title, text, postTime) ?: return@execute
            val raw = listOfNotNull(title, text).joinToString(" — ")
            com.jayant.spendsense.notify.PopupTiming.begin(receivedAt)
            try {
                ingestWithRetry { TransactionIngestor.ingest(app, parsed, TxnSource.NOTIFICATION, pkg, raw, live = true) }
            } finally {
                com.jayant.spendsense.notify.PopupTiming.end()
            }
        }
    }

    /**
     * Debug builds only (R8 removes this in release): `adb shell cmd notification post -t <title> <package> <text>`
     * posts as com.android.shell; its tag names the payment app to emulate, for pop-up latency checks.
     */
    private fun StatusBarNotification.debugAlias(pkg: String): String =
        if (pkg == "com.android.shell" && com.jayant.spendsense.platform.DebugGate.enabled()) tag ?: pkg else pkg

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (com.jayant.spendsense.security.AppIntegrity.tampered) return
        // Open the encrypted database now, so the first payment after a cold start isn't slowed by SQLCipher's open.
        worker.execute { runCatching { com.jayant.spendsense.data.TransactionRepository.get(applicationContext) } }
        com.jayant.spendsense.ingest.CaptureService.sync(applicationContext)
    }

    override fun onListenerDisconnected() {
        // Ask the system to bind us again (e.g. after One UI killed the process) so capture keeps working.
        runCatching { requestRebind(android.content.ComponentName(this, PaymentNotificationListener::class.java)) }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
