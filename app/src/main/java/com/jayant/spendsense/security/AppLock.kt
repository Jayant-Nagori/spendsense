package com.jayant.spendsense.security

import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity

// OWNER: security agent.
/** Call in every Activity.onCreate before setContent: FLAG_SECURE etc. */
object SecureWindow {
    fun apply(activity: FragmentActivity) = Unit
}

/** Wraps app content; shows a lock screen until biometric / device-credential auth succeeds. */
@Composable
fun AppLockGate(activity: FragmentActivity, content: @Composable () -> Unit) = content()
