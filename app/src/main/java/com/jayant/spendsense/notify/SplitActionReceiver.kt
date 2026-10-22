package com.jayant.spendsense.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.jayant.spendsense.data.TransactionRepository
import kotlin.concurrent.thread

/** Handles the "Fully mine" notification action without opening the app. */
class SplitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SplitPromptNotifier.ACTION_FULLY_MINE) return
        val id = intent.getLongExtra(SplitActivity.EXTRA_TXN_ID, -1L)
        if (id < 0) return
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "split-fully-mine") {
            try {
                TransactionRepository.get(app).setSplit(id, false, null)
                NotificationManagerCompat.from(app).cancel(id.toInt())
            } finally {
                pending.finish()
            }
        }
    }
}
