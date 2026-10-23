package com.jayant.spendsense.notify

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jayant.spendsense.R
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.ui.MainActivity
import java.text.NumberFormat
import java.util.Locale

/** "Refund of ₹499 from Amazon received — matched to your 12 Oct purchase." Uses the existing payments channel. */
object RefundNotifier {
    fun notifyRefundLinked(context: Context, credit: Transaction, purchase: Transaction) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val rupees = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }
        val open = PendingIntent.getActivity(
            context, (credit.id % Int.MAX_VALUE).toInt() + 500_000,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val who = purchase.counterparty ?: "your purchase"
        val publicVersion = NotificationCompat.Builder(context, SplitPromptNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spend).setContentTitle("Refund received").build()
        val n = NotificationCompat.Builder(context, SplitPromptNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle("Refund of ${rupees.format(credit.amount)} received")
            .setContentText("Matched to your ${rupees.format(purchase.amount)} payment to $who")
            .setAutoCancel(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((credit.id % Int.MAX_VALUE).toInt() + 500_000, n) }
    }
}
