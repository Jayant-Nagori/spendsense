package com.jayant.spendsense.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.jayant.spendsense.security.StorageHealth
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shown instead of the app when the database can't be opened (its key was lost, or the file is unreadable). Two ways
 * out: restore from a backup file, or start fresh (asked twice). Either way the unreadable files are moved aside,
 * never deleted, and nothing happens without a tap.
 */
@Composable
fun RecoveryScreen(state: StorageHealth.State) {
    val context = platformContext()
    val scope = rememberCoroutineScope()
    val files = Platform.services.rememberBackupFiles()
    val gold = MaterialTheme.colorScheme.primary
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    var picked by remember { mutableStateOf<ByteArray?>(null) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmFresh by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(gold))
                Spacer(Modifier.size(10.dp))
                Text("RECOVERY", style = MaterialTheme.typography.labelSmall, color = gold)
            }
            Text("Your data can't be opened", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                when (state) {
                    StorageHealth.State.KEY_LOST -> "The key that unlocks SpendSense's encrypted database is no longer available on this phone " +
                        "(this can happen after a security reset or restoring the phone). Your old file is kept, but it can't be read without that key."
                    else -> "SpendSense couldn't open its database. The file is kept as it is."
                },
                style = MaterialTheme.typography.bodyMedium, color = grey,
            )

            if (done != null) {
                Text(done!!, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                PrimaryButton("Open SpendSense", { Platform.services.restartApp() }, Modifier.fillMaxWidth())
                return@Column
            }

            SettingsGroup("Restore from backup") {
                if (picked == null) {
                    PrimaryButton("Choose backup file", enabled = files != null && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        msg = null
                        files?.open { b -> if (b != null) { if (BackupFormat.isLikelyBackup(b)) picked = b else msg = false to "That isn't a SpendSense backup file." } }
                    })
                } else {
                    SquareField(pass, { pass = it.take(200) }, "Backup passphrase",
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton(if (busy) "Restoring…" else "Restore", enabled = pass.isNotEmpty() && !busy, modifier = Modifier.weight(1f), onClick = {
                            val bytes = picked ?: return@PrimaryButton
                            busy = true; msg = null
                            scope.launch {
                                val result = withContext(ioDispatcher) {
                                    // Check the passphrase first, so a typo never resets anything.
                                    val (payload, err) = Backups.unlock(bytes, pass)
                                    if (payload == null) return@withContext false to (err ?: "Couldn't open the backup.")
                                    if (!com.jayant.spendsense.backup.BackupData.looksValid(payload)) return@withContext false to "The backup is damaged or incomplete."
                                    Platform.services.resetUnreadableStorage(keyLost = state == StorageHealth.State.KEY_LOST)
                                    when (val o = Backups.apply(context, payload)) {
                                        is Backups.Outcome.Restored -> true to Backups.summary(o.result)
                                        is Backups.Outcome.Failed -> false to o.message
                                    }
                                }
                                busy = false
                                if (result.first) done = result.second else msg = result
                            }
                        })
                        GhostButton("Cancel", { picked = null; pass = "" }, Modifier.weight(1f))
                    }
                }
                msg?.let { (good, m) -> Hint(m, error = !good, good = good) }
            }

            SettingsGroup("Start fresh") {
                Text(
                    "Begin with an empty SpendSense. The unreadable file stays on the phone, set aside; you can still restore a backup later from Settings › Backup.",
                    style = MaterialTheme.typography.bodySmall, color = grey,
                )
                Spacer(Modifier.height(16.dp))
                if (!confirmFresh) {
                    GhostButton("Start fresh", { confirmFresh = true }, Modifier.fillMaxWidth(), enabled = !busy)
                } else {
                    Text("Are you sure? Payments in the unreadable database won't be shown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PrimaryButton("Yes, start fresh", enabled = !busy, modifier = Modifier.weight(1f), onClick = {
                            busy = true
                            scope.launch {
                                withContext(ioDispatcher) { Platform.services.resetUnreadableStorage(keyLost = state == StorageHealth.State.KEY_LOST) }
                                busy = false
                                done = "Started fresh."
                            }
                        })
                        GhostButton("Cancel", { confirmFresh = false }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
