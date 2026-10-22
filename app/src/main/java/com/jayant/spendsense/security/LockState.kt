package com.jayant.spendsense.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.jayant.spendsense.data.Settings

/**
 * Process-wide lock state shared by every activity (MainActivity, SplitActivity, ...).
 * Tracks foreground/background via ActivityLifecycleCallbacks registered lazily on first use
 * (SecureWindow.apply, called from each Activity.onCreate).
 */
object LockState {
    /** Compose-observable. True means the UI must not be shown. */
    val locked = mutableStateOf(true)

    private var initialized = false
    private var started = 0
    private var backgroundedAt = 0L

    @Synchronized
    fun ensureInit(app: Application) {
        if (initialized) return
        initialized = true
        locked.value = Settings(app).appLockEnabled
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started == 0) evaluateOnForeground(app)
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // A rotation/recreate stops and restarts the activity; that is not "leaving the app".
                if (started == 0 && !activity.isChangingConfigurations) {
                    backgroundedAt = SystemClock.elapsedRealtime()
                }
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }

    private fun evaluateOnForeground(app: Application) {
        val settings = Settings(app)
        if (!settings.appLockEnabled) {
            locked.value = false
        } else if (!locked.value && backgroundedAt != 0L) {
            val away = SystemClock.elapsedRealtime() - backgroundedAt
            if (away >= settings.lockTimeoutSeconds * 1000L) locked.value = true
        }
        backgroundedAt = 0L
    }

    fun unlock() { locked.value = false; backgroundedAt = 0L }
    fun lock() { locked.value = true }
}
