package com.jayant.spendsense.imports.email

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.NeoPopSurface
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.theme.spend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** NeoPOP settings block for Gmail alert import. Drop it into a screen's column; it manages its own state. */
@Composable
fun EmailImportSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val creds = remember { EmailCredentials(context) }

    var configured by remember { mutableStateOf(creds.isConfigured) }
    var address by remember { mutableStateOf(creds.address) }
    var password by remember { mutableStateOf("") }
    var savedLast4 by remember { mutableStateOf(creds.passwordLast4) }
    var months by remember { mutableIntStateOf(creds.rangeMonths) }
    var lastSync by remember { mutableStateOf(creds.lastSyncMillis) }
    var testing by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val sync by EmailSync.state.collectAsState()
    val syncing = sync is EmailSyncState.Running

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("GMAIL ALERTS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "Reads only bank alert emails. Uses a Gmail App Password (myaccount.google.com/apppasswords, needs 2-Step Verification).",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SquareField(address, { address = it; error = null }, "Gmail address", KeyboardType.Email, enabled = !testing && !syncing)
        SquareField(
            password, { password = it; error = null },
            label = if (configured && savedLast4.isNotEmpty()) "App password  ····  $savedLast4" else "App password",
            keyboard = KeyboardType.Password, mask = true, enabled = !testing && !syncing,
        )

        PrimaryButton(
            text = if (testing) "Connecting..." else "Connect & test",
            enabled = !testing && !syncing && address.isNotBlank() && (password.isNotBlank() || configured),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                testing = true; info = null; error = null
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        runCatching {
                            val pw = if (password.isNotBlank()) EmailCredentials.normalizePassword(password).toCharArray() else creds.password()
                                ?: throw EmailImportException(EmailFailure.WRONG_PASSWORD)
                            try {
                                val n = GmailImapImporter.testConnection(address, pw)
                                if (password.isNotBlank() || address.trim() != creds.address) {
                                    creds.save(address, String(pw))
                                }
                                n
                            } finally { pw.fill('\u0000') }
                        }
                    }
                    testing = false
                    outcome.onSuccess { n ->
                        configured = true; savedLast4 = creds.passwordLast4; password = ""
                        creds.enabled = true
                        EmailSync.schedule(context)
                        info = "Connected. $n alert emails found in the last 30 days."
                    }.onFailure { e ->
                        error = (e as? EmailImportException)?.failure?.message ?: EmailFailure.classify(e).message
                    }
                }
            },
        )

        if (configured) {
            Text("IMPORT RANGE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EmailCredentials.RANGES.forEach { m ->
                    NeoToggle(
                        label = if (m == 1) "1 MONTH" else "$m MONTHS", selected = months == m,
                        onClick = { months = m; creds.rangeMonths = m }, modifier = Modifier.weight(1f),
                    )
                }
            }

            PrimaryButton(
                text = if (syncing) "Syncing..." else "Sync now", enabled = !syncing && !testing, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    info = null; error = null
                    scope.launch {
                        withContext(Dispatchers.IO) { EmailSync.run(context) }
                        lastSync = creds.lastSyncMillis
                    }
                },
            )

            when (val s = sync) {
                is EmailSyncState.Running -> {
                    if (s.total > 0) {
                        LinearProgressIndicator(
                            progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onBackground, trackColor = MaterialTheme.spend.hairline,
                        )
                        StatusText("Reading ${s.done} of ${s.total}")
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onBackground, trackColor = MaterialTheme.spend.hairline,
                        )
                        StatusText("Connecting to Gmail")
                    }
                }
                is EmailSyncState.Done -> StatusText(s.counts.summary(), MaterialTheme.spend.credit)
                is EmailSyncState.Failed -> StatusText(s.failure.message, MaterialTheme.colorScheme.error)
                EmailSyncState.Idle -> if (lastSync > 0) {
                    StatusText("Last synced " + DateUtils.getRelativeTimeSpanString(lastSync, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                }
            }

            GhostButton(
                text = "Disconnect", enabled = !syncing && !testing, modifier = Modifier.fillMaxWidth(),
                onClick = {
                    EmailSync.cancelSchedule(context)
                    creds.clear()
                    EmailSync.resetState()
                    configured = false; address = ""; password = ""; savedLast4 = ""; lastSync = 0L; info = null; error = null
                },
            )
        }

        info?.let { StatusText(it, MaterialTheme.spend.credit) }
        error?.let { StatusText(it, MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun StatusText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun SquareField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    mask: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        shape = RectangleShape,
        visualTransformation = if (mask) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.onBackground,
            unfocusedBorderColor = MaterialTheme.spend.hairline,
            focusedLabelColor = MaterialTheme.colorScheme.onBackground,
            cursorColor = MaterialTheme.colorScheme.onBackground,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Square NeoPOP toggle: selected = white face / black text, unselected = black face / hairline. */
@Composable
private fun NeoToggle(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.background
    val fg = MaterialTheme.colorScheme.onBackground
    NeoPopSurface(
        color = if (selected) fg else bg, onClick = onClick, modifier = modifier.heightIn(min = 48.dp), depth = 3.dp,
        border = if (selected) null else Color.White.copy(alpha = 0.22f),
        edge = if (selected) Color(0xFF8C8C8C) else Color(0xFF3A3A3A),
    ) {
        Text(
            label, style = MaterialTheme.typography.labelMedium, color = if (selected) bg else fg,
            maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
