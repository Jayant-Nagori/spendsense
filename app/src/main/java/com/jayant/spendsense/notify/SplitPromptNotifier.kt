package com.jayant.spendsense.notify

import androidx.core.app.RemoteInput
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jayant.spendsense.R
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.ui.components.formatRupees

/** "Was it all yours?" notification shown right after a debit is detected. */
object SplitPromptNotifier {
    const val CHANNEL_ID = "payments"
    const val ACTION_FULLY_MINE = "com.jayant.spendsense.action.FULLY_MINE"
    const val ACTION_FOR_SOMEONE = "com.jayant.spendsense.action.FOR_SOMEONE"
    const val ACTION_NOTE = "com.jayant.spendsense.action.NOTE"
    const val KEY_NOTE = "note"
    const val KEY_WHO = "who_for"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Payments", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks whether each payment was fully yours or split"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * [fullScreen]: also attach a full-screen intent to [PaymentPopupActivity] (screen off / locked: it opens like an
     * incoming call; in use: heads-up). Tapping the notification always opens the pop-up itself.
     */
    fun notifyNewDebit(context: Context, txn: Transaction, fullScreen: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)

        val nid = txn.id.toInt()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun splitIntent(requestCode: Int, startAsSplit: Boolean = false): PendingIntent {
            val i = Intent(context, SplitActivity::class.java)
                .putExtra(SplitActivity.EXTRA_TXN_ID, txn.id)
                .putExtra(SplitActivity.EXTRA_START_SPLIT, startAsSplit)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, requestCode, i, flags)
        }

        val fullyMine = PendingIntent.getBroadcast(
            context, nid * 4 + 1,
            Intent(context, SplitActionReceiver::class.java).setAction(ACTION_FULLY_MINE)
                .putExtra(SplitActivity.EXTRA_TXN_ID, txn.id),
            flags,
        )

        // Inline reply: type who it was for. Saves "paid for someone" in one step.
        val forSomeoneIntent = PendingIntent.getBroadcast(
            context, nid * 4 + 4,
            Intent(context, SplitActionReceiver::class.java).setAction(ACTION_FOR_SOMEONE)
                .putExtra(SplitActivity.EXTRA_TXN_ID, txn.id),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )
        val forSomeoneAction = NotificationCompat.Action.Builder(0, "For someone", forSomeoneIntent)
            .addRemoteInput(RemoteInput.Builder(KEY_WHO).setLabel("Who for? (optional)").build())
            .setAllowGeneratedReplies(false)
            .build()

        // Inline reply: type what it was for straight from the notification. Must be mutable so the system can add the text.
        val noteIntent = PendingIntent.getBroadcast(
            context, nid * 4 + 3,
            Intent(context, SplitActionReceiver::class.java).setAction(ACTION_NOTE)
                .putExtra(SplitActivity.EXTRA_TXN_ID, txn.id),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )
        val noteAction = NotificationCompat.Action.Builder(0, "What for?", noteIntent)
            .addRemoteInput(RemoteInput.Builder(KEY_NOTE).setLabel("What was it for?").build())
            .setAllowGeneratedReplies(false)
            .build()
        val note = txn.note?.takeIf { it.isNotBlank() }

        val who = com.jayant.spendsense.data.Payee.display(txn.counterparty)
        val amount = formatRupees(txn.amount)
        val title = if (who != null) "$amount paid to $who" else "$amount paid"

        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle("New payment detected")
            .setContentText("Open SpendSense to review it")
            .build()

        val popup = PendingIntent.getActivity(
            context, nid * 4,
            Intent(context, PaymentPopupActivity::class.java).putExtra(SplitActivity.EXTRA_TXN_ID, txn.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            flags,
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle(title)
            .setContentText(if (note != null) "$note · was it all yours?" else "What was it for? Was it all yours?")
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setColor(0xFFD6B97C.toInt())
            .setAutoCancel(true)
            .setContentIntent(popup)
            .apply { if (fullScreen) setFullScreenIntent(popup, true) }
            // Own group per payment: when the system auto-groups an app's notifications it silences the children,
            // which also suppresses the full-screen intent (seen on Android 15/16).
            .setGroup("payment-${txn.id}")
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            // Android shows at most 3 actions, so "What for?" (also in the pop-up and the app) goes last and yields.
            .addAction(0, "Fully mine", fullyMine)
            .addAction(0, "I split it", splitIntent(nid * 4 + 2, startAsSplit = true))
            .addAction(forSomeoneAction)
            .apply { if (note == null) addAction(noteAction) }
            .build()

        val nm = NotificationManagerCompat.from(context)
        // A re-post with onlyAlertOnce would stay silent and could skip the full-screen launch: start fresh.
        if (fullScreen) runCatching { nm.cancel(nid) }
        runCatching { nm.notify(nid, n) }
    }
}
