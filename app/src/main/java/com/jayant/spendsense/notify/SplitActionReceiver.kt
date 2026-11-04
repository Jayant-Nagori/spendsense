package com.jayant.spendsense.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.jayant.spendsense.data.TransactionRepository
import kotlin.concurrent.thread

/** Handles the "Fully mine", "For someone" and "What for?" notification actions without opening the app. */
class SplitActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.jayant.spendsense.security.AppIntegrity.tampered) return
        val action = intent.action
        if (action != SplitPromptNotifier.ACTION_FULLY_MINE && action != SplitPromptNotifier.ACTION_NOTE &&
            action != SplitPromptNotifier.ACTION_FOR_SOMEONE
        ) return
        val id = intent.getLongExtra(SplitActivity.EXTRA_TXN_ID, -1L)
        if (id < 0) return
        val note = if (action == SplitPromptNotifier.ACTION_NOTE) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SplitPromptNotifier.KEY_NOTE)?.toString()
        } else null
        val who = if (action == SplitPromptNotifier.ACTION_FOR_SOMEONE) {
            RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SplitPromptNotifier.KEY_WHO)?.toString().orEmpty()
        } else null
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "split-action") {
            try {
                val repo = TransactionRepository.get(app)
                if (action == SplitPromptNotifier.ACTION_FOR_SOMEONE) {
                    repo.setPaidFor(id, who)
                    NotificationManagerCompat.from(app).cancel(id.toInt())
                } else if (action == SplitPromptNotifier.ACTION_FULLY_MINE) {
                    repo.setSplit(id, false, null)
                    NotificationManagerCompat.from(app).cancel(id.toInt())
                } else {
                    repo.setNote(id, note)
                    // Re-post (the reply spinner needs it): shows the note and still asks whether it was all yours.
                    val t = repo.get(id)
                    if (t != null && !t.reviewed) SplitPromptNotifier.notifyNewDebit(app, t)
                    else NotificationManagerCompat.from(app).cancel(id.toInt())
                }
            } finally {
                pending.finish()
            }
        }
    }
}
