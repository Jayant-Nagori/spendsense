package com.jayant.spendsense

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.jayant.spendsense.notify.PaymentPopup
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.notify.SplitPromptNotifier
import com.jayant.spendsense.notify.RefundNotifier
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.ingest.IngestEvents
import com.jayant.spendsense.ingest.TransactionIngestor

class SpendSenseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.jayant.spendsense.platform.Platform.install(AndroidPlatformServices(this))
        // Anti-tamper: a release APK not signed with our certificate never opens the database, captures payments
        // or syncs; MainActivity shows the "not genuine" screen instead.
        if (com.jayant.spendsense.security.SignatureCheck.check(this) == com.jayant.spendsense.security.SignatureCheck.Verdict.NOT_GENUINE) {
            com.jayant.spendsense.security.AppIntegrity.markTampered()
            return
        }
        // Shared ingest pipeline -> Android reactions (notification with reply actions + overlay pop-up).
        TransactionIngestor.events = object : IngestEvents {
            override fun onRefundLinked(context: Context, credit: Transaction, purchase: Transaction) =
                RefundNotifier.notifyRefundLinked(context, credit, purchase)

            override fun onNewDebit(context: Context, txn: Transaction) =
                PaymentPopup.present(context, txn, com.jayant.spendsense.notify.PopupTiming.current())

            override fun onDebitWithdrawn(context: Context, id: Long) = PaymentPopup.withdraw(context, id)
        }
        SplitPromptNotifier.createChannel(this)
        // No-op unless the user connected Gmail; otherwise schedules the 6h job and syncs if stale.
        com.jayant.spendsense.imports.email.EmailSync.onAppStart(this)
        // Periodic order sync only while a delivery app is connected (re-checked after connect / disconnect).
        com.jayant.spendsense.orders.OrderSyncJobService.sync(this)
        fixCategoriesOnce()
        // Pop-ups for payments that came in while the phone was locked appear right after unlock. The process stays
        // alive through the notification listener, so a runtime receiver is enough (USER_PRESENT can't be in the manifest).
        ContextCompat.registerReceiver(
            this,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = PaymentPopup.onUnlock(context)
            },
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The process may have been dead at unlock: show anything still queued now.
        PaymentPopup.onUnlock(this)
        com.jayant.spendsense.ingest.CaptureService.sync(this)
    }

    private fun fixCategoriesOnce() {
        val settings = Settings(this)
        if (settings.categoryFixVersion >= CATEGORY_FIX_VERSION) return
        Thread({
            runCatching { TransactionRepository.get(this).recategorizeFalseInvestments() }
                .onSuccess { settings.categoryFixVersion = CATEGORY_FIX_VERSION }
        }, "category-fix").start()
    }

    private companion object {
        const val CATEGORY_FIX_VERSION = 1
    }
}
