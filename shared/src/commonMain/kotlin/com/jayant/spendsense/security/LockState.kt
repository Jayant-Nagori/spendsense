package com.jayant.spendsense.security

import androidx.compose.runtime.mutableStateOf
import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.elapsedRealtimeMillis

/**
 * Process-wide lock state shared by every screen. The platform reports foreground/background transitions
 * (Android: ActivityLifecycleCallbacks; iOS: scene phase) via [onAppBackground] / [onAppForeground].
 */
object LockState {
    /** Compose-observable. True means the UI must not be shown. */
    val locked = mutableStateOf(true)

    private var initialized = false
    private var backgroundedAt = 0L

    fun ensureInit(context: PlatformContext) {
        if (initialized) return
        initialized = true
        locked.value = Settings(context).appLockEnabled
    }

    /** The app left the foreground (not a configuration change). */
    fun onAppBackground() {
        backgroundedAt = elapsedRealtimeMillis()
    }

    /** The app came back: re-lock when it was away at least the configured timeout. */
    fun onAppForeground(context: PlatformContext) {
        val settings = Settings(context)
        if (!settings.appLockEnabled) {
            locked.value = false
        } else if (!locked.value && backgroundedAt != 0L) {
            val away = elapsedRealtimeMillis() - backgroundedAt
            if (away >= settings.lockTimeoutSeconds * 1000L) locked.value = true
        }
        backgroundedAt = 0L
    }

    fun unlock() { locked.value = false; backgroundedAt = 0L }
    fun lock() { locked.value = true }
}
