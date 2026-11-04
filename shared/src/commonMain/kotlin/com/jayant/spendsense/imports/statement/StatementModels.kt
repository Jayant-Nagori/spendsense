package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.ParsedTransaction

/** One transaction read from a statement, before it is persisted. */
data class StatementEntry(
    val txn: ParsedTransaction,
    /** Cleaned narration / description as it appears on the statement. */
    val description: String,
    /** The statement line(s) this row came from; stored as Transaction.rawText. */
    val rawLine: String,
    /** True for rows that are almost certainly already counted elsewhere (credit-card bill payments received). */
    val suggestSkip: Boolean = false,
    /** True when the statement printed a clock time for this row (otherwise the timestamp is a placeholder for the day). */
    val timed: Boolean = false,
)

data class StatementParse(
    val entries: List<StatementEntry>,
    val bank: String? = null,
    val account: String? = null,
    /** True when there was any text to read at all (false => likely a scanned / image-only PDF). */
    val hadText: Boolean = true,
)
