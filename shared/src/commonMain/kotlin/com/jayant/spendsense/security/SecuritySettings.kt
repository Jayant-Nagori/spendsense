package com.jayant.spendsense.security

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.platform.Platform
import com.jayant.spendsense.platformContext
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.ui.OnOffToggle
import com.jayant.spendsense.ui.SettingsRow
import com.jayant.spendsense.ui.SquareToggle
import com.jayant.spendsense.ui.components.GhostButton

private val timeoutChoices = listOf(0 to "Instantly", 30 to "30 sec", 60 to "1 min", 300 to "5 min")

/** Drop-in settings rows: app-lock toggle, re-lock timeout, and "Wipe all data". The caller supplies the section label. */
@Composable
fun SecuritySettingsSection(modifier: Modifier = Modifier) {
    val context = platformContext()
    val services = Platform.services
    val settings = remember { Settings(context) }
    var lockEnabled by remember { mutableStateOf(settings.appLockEnabled) }
    var timeout by remember { mutableStateOf(settings.lockTimeoutSeconds) }
    var confirmWipe by remember { mutableStateOf(false) }
    val deviceSecure = remember { services.isDeviceSecure() }

    fun setLock(want: Boolean) {
        if (want) {
            settings.appLockEnabled = true; lockEnabled = true
        } else if (deviceSecure) {
            // Turning protection off needs proof of presence.
            services.authenticate("Turn off app lock", null, onSuccess = {
                settings.appLockEnabled = false; lockEnabled = false
            }, onError = {})
        } else {
            settings.appLockEnabled = false; lockEnabled = false
        }
    }

    Column(modifier.fillMaxWidth()) {
        SettingsRow(
            "App lock",
            if (deviceSecure) "${services.unlockMethodLabel.replaceFirstChar { it.uppercase() }} to open."
            else "Set a screen lock on this device to make this effective.",
            onClick = { setLock(!lockEnabled) },
        ) { OnOffToggle(lockEnabled) { setLock(it) } }

        if (lockEnabled) {
            SettingsRow("Lock after leaving the app", null)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                timeoutChoices.forEach { (secs, label) ->
                    SquareToggle(label, timeout == secs, { settings.lockTimeoutSeconds = secs; timeout = secs })
                }
            }
        }

        Text(
            services.encryptionBlurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )

        GhostButton(
            "Wipe all data", { confirmWipe = true },
            Modifier.fillMaxWidth().padding(top = 16.dp), color = MaterialTheme.colorScheme.error,
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            shape = MaterialTheme.shapes.medium,
            title = { Text("Wipe all data?") },
            text = {
                Text(
                    "This permanently deletes every transaction, split and investment, your API key, " +
                        "and the encryption keys. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    if (lockEnabled && deviceSecure) {
                        services.authenticate("Confirm wipe", "All data will be permanently deleted", onSuccess = { services.wipeAllData() }, onError = {})
                    } else {
                        services.wipeAllData()
                    }
                }) { Text("WIPE", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("CANCEL") } },
        )
    }
}
