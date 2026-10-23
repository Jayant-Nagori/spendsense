package com.jayant.spendsense.notify

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

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Payments", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Asks whether each payment was fully yours or split"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun notifyNewDebit(context: Context, txn: Transaction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannel(context)

        val nid = txn.id.toInt()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        fun splitIntent(requestCode: Int): PendingIntent {
            val i = Intent(context, SplitActivity::class.java)
                .putExtra(SplitActivity.EXTRA_TXN_ID, txn.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            return PendingIntent.getActivity(context, requestCode, i, flags)
        }

        val fullyMine = PendingIntent.getBroadcast(
            context, nid * 4 + 1,
            Intent(context, SplitActionReceiver::class.java).setAction(ACTION_FULLY_MINE)
                .putExtra(SplitActivity.EXTRA_TXN_ID, txn.id),
            flags,
        )

        val who = txn.counterparty?.takeIf { it.isNotBlank() }
        val amount = formatRupees(txn.amount)
        val title = if (who != null) "$amount paid to $who" else "$amount paid"

        val public = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle("New payment detected")
            .setContentText("Open SpendSense to review it")
            .build()

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle(title)
            .setContentText("Total $amount — was it all yours?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setColor(0xFFD6B97C.toInt())
            .setAutoCancel(true)
            .setContentIntent(splitIntent(nid * 4))
            .addAction(0, "Fully mine", fullyMine)
            .addAction(0, "I split it", splitIntent(nid * 4 + 2))
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(nid, n) }
    }
}
