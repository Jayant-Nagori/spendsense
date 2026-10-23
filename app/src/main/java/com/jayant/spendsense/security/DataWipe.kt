package com.jayant.spendsense.security

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.TransactionDb

/** Irreversibly deletes the database, all Keystore keys, stored settings and notifications. */
object DataWipe {
    fun wipeAndRestart(activity: Activity) {
        val app = activity.applicationContext
        Thread {
            runCatching { TransactionDb.wipe(app) }
            runCatching { Settings(app).clearAll() }
            runCatching { com.jayant.spendsense.imports.email.EmailSync.cancelSchedule(app) }
            runCatching { com.jayant.spendsense.imports.email.EmailCredentials(app).clear() }
            runCatching { KeystoreCipher.deleteKey(KeystoreCipher.ALIAS_PREFS) }
            runCatching { app.getSystemService(NotificationManager::class.java)?.cancelAll() }
            Handler(Looper.getMainLooper()).post {
                // Repositories hold the old DB helper and in-memory caches: restart the process.
                app.packageManager.getLaunchIntentForPackage(app.packageName)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    app.startActivity(it)
                }
                activity.finishAffinity()
                Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
            }
        }.start()
    }
}
