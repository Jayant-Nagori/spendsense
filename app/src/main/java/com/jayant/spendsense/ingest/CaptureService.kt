package com.jayant.spendsense.ingest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.jayant.spendsense.R
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.security.AppIntegrity
import com.jayant.spendsense.ui.MainActivity

/**
 * Lightweight foreground service that runs while capture is on, so One UI's battery management doesn't freeze or
 * unbind the notification listener (the cause of late pop-ups). Holds no data and does no work: it only keeps the
 * process at foreground priority and asks the system to rebind the listener. Low-priority "watching" notification.
 */
class CaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spend)
            .setContentTitle("SpendSense is watching for payments")
            .setContentText("Keeps payment pop-ups on time. Nothing leaves your phone.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }.isSuccess
        // startForeground first, always: stopping a startForegroundService() service before it would crash the app.
        if (!ok || AppIntegrity.tampered || !wanted(this)) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        rebindListener(this)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "capture"
        private const val ID = 0x5EED

        private fun granted(c: Context, p: String) = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

        /** Capture is on (SMS or notification access granted) and the user hasn't turned the service off. */
        fun wanted(c: Context): Boolean {
            if (!runCatching { Settings(c).captureService }.getOrDefault(true)) return false
            val sms = granted(c, Manifest.permission.RECEIVE_SMS)
            val listener = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
            return sms || listener
        }

        /** Starts or stops the service to match [wanted]. Safe from anywhere; a refused background start is ignored. */
        fun sync(context: Context) {
            val app = context.applicationContext
            if (AppIntegrity.tampered) return
            val i = Intent(app, CaptureService::class.java)
            if (wanted(app)) runCatching { ContextCompat.startForegroundService(app, i) }
            else runCatching { app.stopService(i) }
        }

        fun rebindListener(c: Context) {
            if (!NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)) return
            runCatching { NotificationListenerService.requestRebind(ComponentName(c, PaymentNotificationListener::class.java)) }
        }

        private fun createChannel(c: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val ch = NotificationChannel(CHANNEL, "Payment capture", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while SpendSense watches for payments"
                setShowBadge(false)
            }
            c.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}

/** Restarts capture after a reboot or an app update (both are allowed to start a foreground service). */
class CaptureBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        CaptureService.sync(context)
    }
}
