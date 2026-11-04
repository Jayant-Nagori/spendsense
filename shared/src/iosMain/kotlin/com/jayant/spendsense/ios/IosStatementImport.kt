package com.jayant.spendsense.ios

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.IosContext
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.imports.statement.StatementEntry
import com.jayant.spendsense.imports.statement.StatementImporter
import com.jayant.spendsense.imports.statement.StatementStore
import com.jayant.spendsense.ioDispatcher
import com.jayant.spendsense.ui.SettingsRow
import com.jayant.spendsense.ui.SquareField
import com.jayant.spendsense.ui.SquareToggle
import com.jayant.spendsense.ui.components.GhostButton
import com.jayant.spendsense.ui.components.HairlineDivider
import com.jayant.spendsense.ui.components.PrimaryButton
import com.jayant.spendsense.ui.components.formatDateTime
import com.jayant.spendsense.ui.components.formatRupees
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.PDFKit.PDFDocument
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.darwin.NSObject
import platform.posix.memcpy

/** Text of a PDF via PDFKit, page by page. Throws [PdfLocked] when a password is needed (or was wrong). */
@OptIn(ExperimentalForeignApi::class)
internal object PdfKitText {
    class PdfLocked(val wrongPassword: Boolean) : Exception("locked")

    fun read(data: NSData, password: String?): String {
        val doc = PDFDocument(data = data)
        if (doc.isLocked()) {
            if (password.isNullOrEmpty() || !doc.unlockWithPassword(password)) throw PdfLocked(!password.isNullOrEmpty())
        }
        val sb = StringBuilder()
        for (i in 0 until doc.pageCount.toInt()) {
            doc.pageAtIndex(i.toULong())?.string?.let { sb.append(it).append('\n') }
        }
        return sb.toString()
    }
}

private sealed interface Phase {
    data object Idle : Phase
    data object Reading : Phase
    data class Password(val wrong: Boolean) : Phase
    data object Preview : Phase
    data class Done(val added: Int, val dup: Int) : Phase
    data class Failed(val message: String) : Phase
}

private class PickerDelegate(val onPick: (NSURL?) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPick(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = onPick(null)
}

private var activeDelegate: PickerDelegate? = null

private fun topViewController(): UIViewController? {
    var vc = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (vc?.presentedViewController != null) vc = vc.presentedViewController
    return vc
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun readFile(url: NSURL): NSData? {
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        return NSData.dataWithContentsOfURL(url)
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.isPdf(): Boolean {
    if (length < 5u) return false
    val head = ByteArray(5)
    head.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, 5u) }
    return head.decodeToString() == "%PDF-"
}

@OptIn(BetaInteropApi::class)
private fun NSData.utf8(): String = NSString.create(data = this, encoding = NSUTF8StringEncoding)?.toString().orEmpty()

/** Settings › Import on iOS: bank statement PDF (PDFKit) or CSV through the document picker, shared parser + dedupe. */
@Composable
internal fun IosImportScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
    var data by remember { mutableStateOf<NSData?>(null) }
    var password by remember { mutableStateOf("") }
    val rows = remember { mutableStateListOf<Pair<StatementEntry, Boolean>>() }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant

    fun parse(d: NSData, pwd: String?) {
        phase = Phase.Reading
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    val parsed = if (d.isPdf()) StatementImporter.parseLines(StatementStore.splitLines(PdfKitText.read(d, pwd)))
                    else StatementImporter.parseCsvText(d.utf8())
                    StatementImporter.spreadUntimed(parsed.entries)
                }
            }
            result.onSuccess { entries ->
                rows.clear(); rows.addAll(entries.map { it to !it.suggestSkip })
                phase = if (entries.isEmpty()) Phase.Failed("No transactions found. Scanned (image-only) PDFs can't be read.") else Phase.Preview
            }.onFailure { e ->
                phase = if (e is PdfKitText.PdfLocked) Phase.Password(e.wrongPassword) else Phase.Failed("Couldn't read that file.")
            }
        }
    }

    fun pick() {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypePDF, UTTypeCommaSeparatedText, UTTypePlainText), asCopy = true)
        val delegate = PickerDelegate { url ->
            activeDelegate = null
            val d = url?.let(::readFile) ?: return@PickerDelegate
            if (d.length > 15u * 1024u * 1024u) { phase = Phase.Failed("That file is too large to be a statement"); return@PickerDelegate }
            data = d; password = ""
            parse(d, null)
        }
        activeDelegate = delegate
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface) }
            Text("Import", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.padding(horizontal = 20.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsRow("Bank statement", "PDF or CSV from your bank. Read on this iPhone; payments already captured are skipped.")
            when (val p = phase) {
                Phase.Idle -> PrimaryButton("Choose statement", { pick() }, Modifier.fillMaxWidth())
                Phase.Reading -> Text("Reading…", style = MaterialTheme.typography.bodyMedium, color = grey)
                is Phase.Password -> {
                    Text(if (p.wrong) "Wrong password. Try again." else "This PDF is password-protected (often your customer ID or date of birth).",
                        style = MaterialTheme.typography.bodySmall, color = if (p.wrong) MaterialTheme.colorScheme.error else grey)
                    SquareField(password, { password = it }, "PDF password", visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    PrimaryButton("Unlock", { data?.let { parse(it, password) } }, Modifier.fillMaxWidth(), enabled = password.isNotEmpty())
                }
                Phase.Preview -> {
                    val picked = rows.count { it.second }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${rows.size} found · $picked selected", style = MaterialTheme.typography.bodySmall, color = grey, modifier = Modifier.weight(1f))
                        SquareToggle("All", picked == rows.size, { val on = picked != rows.size; for (i in rows.indices) rows[i] = rows[i].first to on })
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(rows) { i, (e, on) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SquareToggle(if (on) "✓" else " ", on, { rows[i] = e to !on })
                                Column(Modifier.weight(1f)) {
                                    Text(e.txn.counterparty ?: e.description, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(formatDateTime(e.txn.timestamp), style = MaterialTheme.typography.bodySmall, color = grey)
                                }
                                Text((if (e.txn.type == TxnType.CREDIT) "+" else "") + formatRupees(e.txn.amount), style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                            HairlineDivider()
                        }
                    }
                    PrimaryButton("Import $picked", {
                        val chosen = rows.filter { it.second }.map { it.first }
                        phase = Phase.Reading
                        scope.launch {
                            val (added, dup) = withContext(ioDispatcher) { StatementStore.store(IosContext, chosen) }
                            rows.clear(); data = null
                            phase = Phase.Done(added, dup)
                        }
                    }, Modifier.fillMaxWidth().padding(bottom = 16.dp), enabled = picked > 0)
                }
                is Phase.Done -> {
                    Text("Imported ${p.added}" + if (p.dup > 0) " · ${p.dup} already in SpendSense" else "", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    GhostButton("Import another", { phase = Phase.Idle }, Modifier.fillMaxWidth())
                }
                is Phase.Failed -> {
                    Text(p.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    PrimaryButton("Choose another file", { pick() }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
