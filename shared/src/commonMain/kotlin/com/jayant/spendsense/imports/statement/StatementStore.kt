package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.ingest.CategoryGuesser
import com.jayant.spendsense.time.atStartOfDayMillis
import com.jayant.spendsense.time.localDateOf
import com.jayant.spendsense.time.plusDays

/** Platform-neutral pieces of statement import (iOS uses these; Android's view model has the same rules). */
object StatementStore {
    /** PDF text (PDFKit / PDFBox) → trimmed non-empty lines, the input [StatementImporter.parseLines] expects. */
    fun splitLines(text: String): List<String> =
        text.split(Regex("\r\n|\r|\n| | ")).map { it.replace(' ', ' ').trim() }.filter { it.isNotEmpty() }

    /** Saves [entries], skipping payments already captured from SMS / notifications. Returns (added, duplicates). Blocking. */
    fun store(context: PlatformContext, entries: List<StatementEntry>): Pair<Int, Int> {
        val repo = TransactionRepository.get(context)
        val claimed = mutableSetOf<Long>()
        val byDay = HashMap<kotlinx.datetime.LocalDate, List<Transaction>>()
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
            val day = localDateOf(p.timestamp, StatementDates.IST)
            val sameDay = byDay.getOrPut(day) {
                repo.between(day.atStartOfDayMillis(StatementDates.IST), day.plusDays(1).atStartOfDayMillis(StatementDates.IST))
            }
            val dupOf = repo.findDuplicate(txn)?.also { claimed += it.id } ?: StatementDedupe.matchOnSameDay(txn, sameDay, claimed)
            if (dupOf != null || repo.insertIfNew(txn) == null) dup++ else added++
        }
        return added to dup
    }
}
