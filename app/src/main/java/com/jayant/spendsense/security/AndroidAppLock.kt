package com.jayant.spendsense.security

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity

/** Call in every Activity.onCreate before setContent: FLAG_SECURE etc. */
object SecureWindow {
    fun apply(activity: FragmentActivity) {
        // No screenshots, screen recording or recents thumbnails. Debug builds allow them for visual QA only.
        if (!com.jayant.spendsense.BuildConfig.DEBUG) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.setRecentsScreenshotEnabled(false)
            }
        }
        AndroidLockLifecycle.ensureInit(activity.application)
    }
}

/** Android entry point: hooks lifecycle tracking, then the shared gate. */
@Composable
fun AppLockGate(activity: FragmentActivity, content: @Composable () -> Unit) {
    AndroidLockLifecycle.ensureInit(activity.application)
    AppLockGate(content)
}
