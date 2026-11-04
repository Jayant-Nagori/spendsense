package com.jayant.spendsense.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference

/**
 * Feeds Android activity lifecycle into the shared [LockState] (registered lazily on first use from
 * SecureWindow.apply / AppLockGate) and remembers the resumed activity for biometric prompts.
 */
object AndroidLockLifecycle {
    private var initialized = false
    private var started = 0
    private var resumed: WeakReference<FragmentActivity>? = null

    /** The activity currently in front, if it can host a BiometricPrompt. */
    val currentActivity: FragmentActivity? get() = resumed?.get()

    @Synchronized
    fun ensureInit(app: Application) {
        if (initialized) return
        initialized = true
        LockState.ensureInit(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started == 0) LockState.onAppForeground(app)
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // A rotation/recreate stops and restarts the activity; that is not "leaving the app".
                if (started == 0 && !activity.isChangingConfigurations) LockState.onAppBackground()
            }

            override fun onActivityResumed(a: Activity) {
                if (a is FragmentActivity) resumed = WeakReference(a)
            }

            override fun onActivityPaused(a: Activity) {
                if (resumed?.get() === a) resumed = null
            }

            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivitySaveInstanceState(a: Activity, o: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }
}
