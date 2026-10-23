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

class SpendSenseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SplitPromptNotifier.createChannel(this)
        // No-op unless the user connected Gmail; otherwise schedules the 6h job and syncs if stale.
        com.jayant.spendsense.imports.email.EmailSync.onAppStart(this)
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
