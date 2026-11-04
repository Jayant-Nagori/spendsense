package com.jayant.spendsense.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.backup.BackupFormat
import com.jayant.spendsense.backup.Backups
import com.jayant.spendsense.ioDispatcher
import com.jayant.spendsense.platform.Platform
import com.jayant.spendsense.platformContext
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The update advice shown in Settings › Backup, the README and the release notes. */
const val INSTALL_OVER_THE_TOP =
    "Install updates over the top — don't uninstall first. If Android says 'App not installed' you have an old test build: " +
        "take a backup in Settings → Backup first, then uninstall, install, and restore."

/** Settings › Backup: encrypted backup file, restore (merge), and the platform's auto-backup. */
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    val files = Platform.services.rememberBackupFiles()
    var pass by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var restorePass by remember { mutableStateOf("") }
    var restoreMsg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Text("Backup", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 72.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
            Text(INSTALL_OVER_THE_TOP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            SettingsGroup("Back up now") {
                SettingsRow(
                    "Encrypted backup file",
                    "Payments, splits, notes, order receipts and settings, locked with a passphrase you choose. API keys and sign-ins are never included.",
                )
                Spacer(Modifier.height(16.dp))
                SquareField(pass, { pass = it.take(200) }, "Passphrase (at least ${BackupFormat.MIN_PASSPHRASE} characters)",
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(Modifier.height(8.dp))
                SquareField(pass2, { pass2 = it.take(200) }, "Repeat passphrase",
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                val ok = pass.length >= BackupFormat.MIN_PASSPHRASE && pass == pass2
                if (pass2.isNotEmpty() && pass != pass2) Hint("Passphrases don't match.", error = true)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(if (busy) "Encrypting…" else "Save backup file", enabled = ok && !busy && files != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true; saveMsg = null
                    scope.launch {
                        val bytes = withContext(ioDispatcher) { runCatching { Backups.create(context, pass) }.getOrNull() }
                        if (bytes == null) { busy = false; saveMsg = false to "Couldn't create the backup."; return@launch }
                        files!!.save(Backups.fileName(), bytes) { saved ->
                            busy = false
                            saveMsg = if (saved) true to "Backup saved. Keep the passphrase somewhere safe: without it the file can't be opened." else false to "Not saved."
                            if (saved) { pass = ""; pass2 = "" }
                        }
                    }
                })
                saveMsg?.let { (good, m) -> Hint(m, error = !good, good = good) }
            }

            SettingsGroup("Restore") {
                SettingsRow("Restore from a backup file", "Adds what's in the backup to this phone. Payments already here are skipped, nothing is deleted.")
                Spacer(Modifier.height(16.dp))
                if (picked == null) {
                    GhostButton("Choose backup file", enabled = files != null && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        restoreMsg = null
                        files?.open { bytes ->
                            when {
                                bytes == null -> Unit
                                !BackupFormat.isLikelyBackup(bytes) -> restoreMsg = false to "That isn't a SpendSense backup file."
                                else -> picked = bytes
                            }
                        }
                    })
                } else {
                    SquareField(restorePass, { restorePass = it.take(200) }, "Backup passphrase",
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(if (busy) "Restoring…" else "Restore", enabled = restorePass.isNotEmpty() && !busy, modifier = Modifier.weight(1f), onClick = {
                            val bytes = picked ?: return@PrimaryButton
                            busy = true; restoreMsg = null
                            scope.launch {
                                val out = withContext(ioDispatcher) { Backups.restore(context, bytes, restorePass) }
                                busy = false
                                when (out) {
                                    is Backups.Outcome.Restored -> { restoreMsg = true to Backups.summary(out.result); picked = null; restorePass = "" }
                                    is Backups.Outcome.Failed -> restoreMsg = false to out.message
                                }
                            }
                        })
                        GhostButton("Cancel", { picked = null; restorePass = "" }, Modifier.weight(1f))
                    }
                }
                restoreMsg?.let { (good, m) -> Hint(m, error = !good, good = good) }
            }

            Platform.services.AutoBackupSettings()
        }
    }
}

@Composable
internal fun Hint(text: String, error: Boolean = false, good: Boolean = false) {
    Text(
        text, style = MaterialTheme.typography.bodySmall,
        color = when { error -> MaterialTheme.colorScheme.error; good -> MaterialTheme.spend.credit; else -> MaterialTheme.colorScheme.onSurfaceVariant },
        modifier = Modifier.padding(top = 8.dp),
    )
}
