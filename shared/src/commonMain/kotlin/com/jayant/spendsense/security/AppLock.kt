package com.jayant.spendsense.security

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
import androidx.compose.ui.graphics.RectangleShape
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jayant.spendsense.platform.Platform
import com.jayant.spendsense.platformContext
import com.jayant.spendsense.data.Settings

/** Wraps app content; shows a lock screen until biometric / device-credential auth succeeds. */
@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = platformContext()
    LockState.ensureInit(context)
    val settings = remember { Settings(context) }
    val locked by LockState.locked
    val holder = rememberSaveableStateHolder()
    if (!locked || !settings.appLockEnabled) {
        // Keyed holder keeps rememberSaveable state (e.g. selected tab) across lock/unlock.
        holder.SaveableStateProvider("gate-content") { content() }
    } else {
        LockScreen()
    }
}

@Composable
private fun LockScreen() {
    val services = Platform.services
    val secure = remember { services.isDeviceSecure() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    var autoPrompted by remember { mutableStateOf(false) }

    fun startAuth() {
        if (inFlight || !secure) return
        inFlight = true
        error = null
        services.authenticate(
            title = "Unlock SpendSense",
            subtitle = "Confirm it's you to view your finances",
            onSuccess = { inFlight = false; LockState.unlock() },
            onError = { msg -> inFlight = false; error = msg },
        )
    }

    // Auto-prompt once per lock screen, only while the activity is actually resumed.
    LaunchedEffect(secure) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
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
            // App mark: small white square tile with a black lock.
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.onBackground, RectangleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(32.dp))
            Text(
                "SpendSense is locked", style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your data is encrypted on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            if (secure) {
                PrimaryButton("Unlock", { startAuth() }, Modifier.fillMaxWidth(), enabled = !inFlight)
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                Text(
                    "No screen lock is set on this device, so SpendSense cannot verify you. " +
                        "Set a PIN, pattern, password or fingerprint in system Settings. " +
                        "Until then, anyone holding this phone can open your financial data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                GhostButton("Continue without lock", { LockState.unlock() }, Modifier.fillMaxWidth())
            }
        }
    }
}
