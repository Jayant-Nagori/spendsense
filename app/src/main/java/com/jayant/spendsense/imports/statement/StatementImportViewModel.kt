package com.jayant.spendsense.imports.statement

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jayant.spendsense.ai.ConfiguredLlmClient
import com.jayant.spendsense.ai.aiConfig
import com.jayant.spendsense.ai.AssistantException
import com.jayant.spendsense.ai.ErrorKind
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.ingest.CategoryGuesser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate

sealed interface ImportPhase {
    data object Idle : ImportPhase
    data class Reading(val label: String) : ImportPhase
    data class NeedPassword(val wrong: Boolean) : ImportPhase
    data object Preview : ImportPhase
    data object Importing : ImportPhase
    data class Done(val added: Int, val duplicates: Int) : ImportPhase
    data class Failed(val message: String) : ImportPhase
}

data class PreviewRow(val entry: StatementEntry, val selected: Boolean)

/**
 * Holds one statement import. The statement text and the PDF password live only in memory (never logged, never
 * written to disk) and are dropped by [reset] / onCleared.
 */
class StatementImportViewModel(app: Application) : AndroidViewModel(app) {
    var phase by mutableStateOf<ImportPhase>(ImportPhase.Idle)
        private set
    val rows = mutableStateListOf<PreviewRow>()
    var useAi by mutableStateOf(false)
        private set
    private val aiConfigNow = runCatching { Settings(app).aiConfig() }.getOrNull()
    val hasApiKey: Boolean = aiConfigNow != null
    /** Who would receive the statement text, for the privacy line. */
    val aiRecipient: String = aiConfigNow?.recipient ?: "your AI provider"

    private var uri: Uri? = null
    private var lines: List<String>? = null
    private var handledInitial: Uri? = null
    private var job: Job? = null

    /** Opens [initial] once per ViewModel (rotation-safe) when the screen was launched with a shared file. */
    fun openInitialOnce(initial: Uri) {
        if (handledInitial == initial) return
        handledInitial = initial
        open(initial)
    }

    fun open(newUri: Uri, password: String? = null) {
        if (newUri != uri) { lines = null; rows.clear() }
        uri = newUri
        job?.cancel()
        job = viewModelScope.launch {
            phase = ImportPhase.Reading("Reading statement")
            try {
                val loaded = withContext(Dispatchers.IO) { load(newUri, password) }
                lines = loaded.first
                finishParse(loaded.second)
            } catch (e: PasswordRequiredException) {
                phase = ImportPhase.NeedPassword(e.wrongPassword)
            } catch (e: StatementReadException) {
                phase = ImportPhase.Failed(e.message ?: "Could not read the file")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = ImportPhase.Failed("Could not read the file")
            }
        }
    }

    fun submitPassword(password: String) { uri?.let { open(it, password) } }

    fun cancelPassword() { reset() }

    fun toggleAi(on: Boolean) {
        useAi = on && hasApiKey
        val text = lines
        if (useAi && text != null && rows.size < 3 && phase !is ImportPhase.Reading) {
            job?.cancel()
            job = viewModelScope.launch { finishParse(StatementImporter.parseLines(text)) }
        }
    }

    private fun load(uri: Uri, password: String?): Pair<List<String>, StatementParse> {
        val resolver = getApplication<Application>().contentResolver
        val head = ByteArray(5)
        val n = resolver.openInputStream(uri)?.use { it.read(head) } ?: throw StatementReadException("Could not open the file")
        val isPdf = n >= 5 && String(head, Charsets.ISO_8859_1) == "%PDF-"
        return if (isPdf) {
            val l = PdfStatementReader.readLines(getApplication(), uri, password)
            l to StatementImporter.parseLines(l)
        } else {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw StatementReadException("Could not open the file")
            if (bytes.size > MAX_CSV_BYTES) throw StatementReadException("That file is too large to be a statement")
            val text = String(bytes, Charsets.UTF_8)
            val l = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            l to StatementImporter.parseCsvText(text)
        }
    }

    private suspend fun finishParse(heuristic: StatementParse) {
        var result = heuristic
        val text = lines
        if (heuristic.entries.size < 3 && heuristic.hadText && useAi && hasApiKey && !text.isNullOrEmpty()) {
            phase = ImportPhase.Reading("Asking the AI to read it")
            try {
                val ai = withContext(Dispatchers.IO) {
                    AiStatementParser(ConfiguredLlmClient({ Settings(getApplication()).aiConfig() }))
                        .parse(text, heuristic.bank ?: StatementImporter.detectBank(text.take(40).joinToString("\n")), heuristic.account)
                }
                if (ai.entries.size > heuristic.entries.size) result = ai
            } catch (e: AssistantException) {
                phase = ImportPhase.Failed(aiMessage(e.kind))
                return
            }
        }
        val entries = StatementImporter.spreadUntimed(result.entries)
        rows.clear()
        entries.forEach { rows.add(PreviewRow(it, selected = !it.suggestSkip)) }
        phase = when {
            entries.isNotEmpty() -> ImportPhase.Preview
            !result.hadText -> ImportPhase.Failed("No text found in this file. It may be a scanned PDF.")
            else -> ImportPhase.Failed(
                if (hasApiKey && !useAi) "No transactions found. Tick \"Use AI to read this statement\" to try again."
                else "No transactions found in this file.",
            )
        }
    }

    private fun aiMessage(kind: ErrorKind) = when (kind) {
        ErrorKind.NO_KEY, ErrorKind.AUTH -> "The AI provider rejected the API key. Check it in Settings."
        ErrorKind.RATE_LIMIT, ErrorKind.OVERLOADED -> "The AI is busy right now. Try again in a minute."
        ErrorKind.NETWORK -> "No connection to the AI provider."
        else -> "The AI could not read this statement."
    }

    fun toggle(index: Int) {
        rows.getOrNull(index)?.let { rows[index] = it.copy(selected = !it.selected) }
    }

    fun selectAll(on: Boolean) {
        for (i in rows.indices) rows[i] = rows[i].copy(selected = on)
    }

    fun importSelected() {
        val picked = rows.filter { it.selected }.map { it.entry }
        if (picked.isEmpty() || phase !is ImportPhase.Preview) return
        phase = ImportPhase.Importing
        job = viewModelScope.launch {
            val (added, dup) = withContext(Dispatchers.IO) { store(picked) }
            rows.clear()
            lines = null
            phase = ImportPhase.Done(added, dup)
        }
    }

    private fun store(entries: List<StatementEntry>): Pair<Int, Int> {
        val repo = TransactionRepository.get(getApplication())
        val claimed = mutableSetOf<Long>()
        val byDay = HashMap<LocalDate, List<Transaction>>()
        var added = 0
        var dup = 0
        for (e in entries) {
            val p = e.txn
            val txn = Transaction(
                amount = p.amount, type = p.type, counterparty = p.counterparty, account = p.account, bank = p.bank,
                upiRef = p.upiRef, source = TxnSource.MANUAL, sourceApp = "statement", rawText = e.rawLine,
                timestamp = p.timestamp, category = CategoryGuesser.guess(p.counterparty, e.description),
                reviewed = true, note = "Statement import", paymentMode = p.paymentMode,
            )
            val day = Instant.ofEpochMilli(p.timestamp).atZone(StatementDates.IST).toLocalDate()
            val sameDay = byDay.getOrPut(day) {
                val from = day.atStartOfDay(StatementDates.IST).toInstant().toEpochMilli()
                repo.between(from, day.plusDays(1).atStartOfDay(StatementDates.IST).toInstant().toEpochMilli())
            }
            val dupOf = repo.findDuplicate(txn)?.also { claimed += it.id } ?: StatementDedupe.matchOnSameDay(txn, sameDay, claimed)
            if (dupOf != null || repo.insertIfNew(txn) == null) dup++ else added++
        }
        return added to dup
    }

    fun reset() {
        job?.cancel()
        lines = null
        uri = null
        rows.clear()
        phase = ImportPhase.Idle
    }

    override fun onCleared() {
        lines = null
        rows.clear()
        super.onCleared()
    }

    private companion object { const val MAX_CSV_BYTES = 15 * 1024 * 1024 }
}
