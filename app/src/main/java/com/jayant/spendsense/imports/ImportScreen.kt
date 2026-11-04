package com.jayant.spendsense.imports

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.imports.statement.ImportPhase
import com.jayant.spendsense.imports.statement.PreviewRow
import com.jayant.spendsense.imports.statement.StatementDates
import com.jayant.spendsense.imports.statement.StatementImportViewModel
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.formatRupees
import com.jayant.spendsense.ui.theme.spend
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MIME_TYPES = arrayOf("application/pdf", "text/csv", "text/comma-separated-values", "application/vnd.ms-excel", "text/plain")
private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * Import transactions from a bank / card statement (PDF or CSV). [initialUri] pre-loads a shared file.
 * [emailSection] is rendered under an "EMAIL" header when no statement preview is open.
 */
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    initialUri: Uri? = null,
    emailSection: @Composable () -> Unit = {},
) {
    val vm: StatementImportViewModel = viewModel()
    val phase = vm.phase
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.open(uri) }
    LaunchedEffect(initialUri) { initialUri?.let { vm.openInitialOnce(it) } }

    val previewing = phase is ImportPhase.Preview || phase is ImportPhase.Importing
    val selected = vm.rows.count { it.selected }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                BackRow(onBack)
                Spacer(Modifier.height(24.dp))
                Text("Import", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(32.dp))
                SectionLabel("BANK STATEMENT")
                Spacer(Modifier.height(12.dp))
            }
            item { StatementControls(vm, phase) { picker.launch(MIME_TYPES) } }
            if (previewing) {
                item { PreviewHeader(vm) }
                itemsIndexed(vm.rows, key = { i, r -> "$i-${r.entry.txn.timestamp}-${r.entry.txn.amount}" }) { i, row ->
                    PreviewRowItem(row) { vm.toggle(i) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            } else {
                item {
                    Spacer(Modifier.height(40.dp))
                    SectionLabel("EMAIL")
                    Spacer(Modifier.height(12.dp))
                    emailSection()
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
        if (previewing) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                PrimaryButton(
                    text = if (phase is ImportPhase.Importing) "Importing…" else "Import $selected transaction" + if (selected == 1) "" else "s",
                    onClick = { vm.importSelected() },
                    enabled = selected > 0 && phase is ImportPhase.Preview,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (phase is ImportPhase.NeedPassword) {
        PasswordDialog(wrong = phase.wrong, onSubmit = vm::submitPassword, onCancel = vm::cancelPassword)
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Box(
        Modifier.size(40.dp).border(1.dp, MaterialTheme.spend.hairline, RectangleShape)
            .semantics { contentDescription = "Back"; role = Role.Button }
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = MaterialTheme.colorScheme.onBackground) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatementControls(vm: StatementImportViewModel, phase: ImportPhase, pick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (phase) {
            is ImportPhase.Reading -> Muted(phase.label + "…")
            is ImportPhase.Done -> {
                Text(
                    "Added ${phase.added} · ${phase.duplicates} already recorded",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground,
                )
                GhostButton("Import another", onClick = { vm.reset(); pick() }, modifier = Modifier.fillMaxWidth())
            }
            is ImportPhase.Preview, is ImportPhase.Importing -> {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("Change file", onClick = { vm.reset(); pick() }, modifier = Modifier.weight(1f), enabled = phase is ImportPhase.Preview)
                }
            }
            else -> {
                Muted("PDF or CSV from your bank or card. Password-protected PDFs are fine. Everything is read on this phone.")
                if (phase is ImportPhase.Failed) {
                    Text(phase.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                PrimaryButton("Choose statement", onClick = pick, modifier = Modifier.fillMaxWidth())
            }
        }
        if (phase !is ImportPhase.Done && phase !is ImportPhase.Preview && phase !is ImportPhase.Importing) AiToggle(vm)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AiToggle(vm: StatementImportViewModel) {
    val enabled = vm.hasApiKey
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { vm.toggleAi(!vm.useAi) }
            .semantics { role = Role.Checkbox },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
    ) {
        SquareCheck(vm.useAi, enabled)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Use AI to read this statement", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.4f),
            )
            Muted(
                if (enabled) "Only used if the built-in reader finds fewer than 3 rows. The statement text is sent to ${vm.aiRecipient}."
                else "Add an AI key in Settings to enable. The statement text would be sent to ${vm.aiRecipient}.",
            )
        }
    }
}

@Composable
private fun SquareCheck(checked: Boolean, enabled: Boolean = true) {
    val fg = MaterialTheme.colorScheme.onBackground
    Box(
        Modifier.size(24.dp)
            .background(if (checked) fg else Color.Transparent, RectangleShape)
            .border(1.dp, if (checked) fg else fg.copy(alpha = if (enabled) 0.45f else 0.2f), RectangleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PreviewHeader(vm: StatementImportViewModel) {
    val chosen = vm.rows.filter { it.selected }
    val out = chosen.filter { it.entry.txn.type == TxnType.DEBIT }.sumOf { it.entry.txn.amount }
    val inn = chosen.filter { it.entry.txn.type == TxnType.CREDIT }.sumOf { it.entry.txn.amount }
    val allOn = chosen.size == vm.rows.size
    Column(Modifier.padding(top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "${chosen.size} of ${vm.rows.size} selected",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground,
        )
        Muted("${formatRupees(out)} out · ${formatRupees(inn)} in")
        Text(
            if (allOn) "SELECT NONE" else "SELECT ALL",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.clickable { vm.selectAll(!allOn) }.padding(vertical = 8.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.spend.hairline))
    }
}

@Composable
private fun PreviewRowItem(row: PreviewRow, onToggle: () -> Unit) {
    val t = row.entry.txn
    val credit = t.type == TxnType.CREDIT
    val date = com.jayant.spendsense.time.formatPattern(t.timestamp, StatementDates.IST, "d MMM yyyy")
    val meta = listOfNotNull(
        date, t.paymentMode?.name?.takeIf { it != "OTHER" }, if (row.entry.suggestSkip) "LIKELY COUNTED ALREADY" else null,
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).semantics { role = Role.Checkbox }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { SquareCheck(row.selected) }
            Column(Modifier.weight(1f)) {
                Text(
                    t.counterparty ?: row.entry.description, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                (if (credit) "+" else "") + formatRupees(t.amount, forceDecimals = false),
                style = MaterialTheme.typography.titleMedium,
                color = if (credit) MaterialTheme.spend.credit else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.spend.hairline))
    }
}

@Composable
private fun PasswordDialog(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    // Kept only in composition state; never saved or logged.
    var pw by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background, RoundedCornerShape(2.dp))
                .border(1.dp, MaterialTheme.spend.hairline, RoundedCornerShape(2.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("PDF PASSWORD")
            Text(
                if (wrong) "That password did not work. Try again." else "This statement is locked. Banks usually use your date of birth or customer ID.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (wrong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pw, onValueChange = { pw = it }, singleLine = true, shape = RectangleShape,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.onBackground, unfocusedIndicatorColor = MaterialTheme.spend.hairline,
                    cursorColor = MaterialTheme.colorScheme.onBackground,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("Cancel", onClick = onCancel, modifier = Modifier.weight(1f))
                PrimaryButton("Unlock", onClick = { onSubmit(pw); pw = "" }, enabled = pw.isNotEmpty(), modifier = Modifier.weight(1f))
            }
        }
    }
}
