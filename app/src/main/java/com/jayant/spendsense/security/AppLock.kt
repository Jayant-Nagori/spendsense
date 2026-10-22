package com.jayant.spendsense.security

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jayant.spendsense.data.Settings

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
        LockState.ensureInit(activity.application)
    }
}

/** Wraps app content; shows a lock screen until biometric / device-credential auth succeeds. */
@Composable
fun AppLockGate(activity: FragmentActivity, content: @Composable () -> Unit) {
    LockState.ensureInit(activity.application)
    val settings = remember { Settings(activity) }
    val locked by LockState.locked
    val holder = rememberSaveableStateHolder()
    if (!locked || !settings.appLockEnabled) {
        // Keyed holder keeps rememberSaveable state (e.g. selected tab) across lock/unlock.
        holder.SaveableStateProvider("gate-content") { content() }
    } else {
        LockScreen(activity)
    }
}

@Composable
private fun LockScreen(activity: FragmentActivity) {
    val secure = remember { Authenticator.isDeviceSecure(activity) }
    var error by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    var autoPrompted by remember { mutableStateOf(false) }

    fun startAuth() {
        if (inFlight || !secure) return
        inFlight = true
        error = null
        Authenticator.prompt(
            activity,
            title = "Unlock SpendSense",
            subtitle = "Confirm it's you to view your finances",
            onSuccess = { inFlight = false; LockState.unlock() },
            onError = { _, msg -> inFlight = false; error = msg },
        )
    }

    // Auto-prompt once per lock screen, only while the activity is actually resumed.
    LaunchedEffect(secure) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!autoPrompted) { autoPrompted = true; startAuth() }
        }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(88.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(28.dp))
            Text("SpendSense is locked", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your transactions and investments are encrypted on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            if (secure) {
                Button(onClick = { startAuth() }, enabled = !inFlight, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Unlock")
                }
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No screen lock is set on this device, so SpendSense cannot verify you. " +
                            "Set a PIN, pattern, password or fingerprint in system Settings > Security. " +
                            "Until then, anyone holding this phone can open your financial data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { LockState.unlock() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Continue without lock")
                }
            }
        }
    }
}
