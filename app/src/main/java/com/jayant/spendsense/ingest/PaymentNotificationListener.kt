package com.jayant.spendsense.ingest

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jayant.spendsense.data.TxnSource
import java.util.concurrent.Executors

/** Watches only whitelisted UPI / bank / wallet apps for completed payments. Never logs contents. */
class PaymentNotificationListener : NotificationListenerService() {

    private val worker = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in TransactionParser.PAYMENT_PACKAGES) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString() ?: return
        val postTime = sbn.postTime
        val app = applicationContext
        worker.execute {
            val parsed = TransactionParser.parseNotification(pkg, title, text, postTime) ?: return@execute
            val raw = listOfNotNull(title, text).joinToString(" — ")
            runCatching { TransactionIngestor.ingest(app, parsed, TxnSource.NOTIFICATION, pkg, raw, live = true) }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
