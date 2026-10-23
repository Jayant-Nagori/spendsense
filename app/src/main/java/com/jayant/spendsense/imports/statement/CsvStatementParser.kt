package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.TxnType

/** CSV / XLS-exported-as-CSV statements: finds the header row (Date, Narration, Withdrawal Amt., Deposit Amt., ...). */
object CsvStatementParser {
    private val IC = RegexOption.IGNORE_CASE

    /** RFC-4180-ish tokenizer with automatic delimiter detection (comma, semicolon, tab, pipe). */
    fun tokenize(text: String): List<List<String>> {
        val body = text.removePrefix("﻿")
        val sample = body.lineSequence().filter { it.isNotBlank() }.take(30).toList()
        val delim = listOf(',', ';', '\t', '|').maxByOrNull { d -> sample.sumOf { l -> l.count { it == d } } } ?: ','
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        fun endCell() { row.add(cell.toString().trim()); cell.setLength(0) }
        fun endRow() {
            endCell()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = mutableListOf()
        }
        while (i < body.length) {
            val c = body[i]
            when {
                quoted && c == '"' && i + 1 < body.length && body[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                quoted -> cell.append(c)
                c == delim -> endCell()
                c == '\n' -> endRow()
                c == '\r' -> {}
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private class Columns(
        val date: Int, val desc: Int, val ref: Int, val debit: Int, val credit: Int,
        val amount: Int, val type: Int, val balance: Int,
    )

    private val DATE_H = Regex("^(txn|transaction|tran|posting|trans)?\\.?\\s*date\\.?$", IC)
    private val VALUE_DATE_H = Regex("value\\s*(date|dt)", IC)
    private val DESC_H = Regex("narration|description|particulars|details|remarks", IC)
    private val REF_H = Regex("chq|cheque|ref|utr", IC)
    private val BAL_H = Regex("balance|bal\\.?$", IC)
    private val TYPE_H = Regex("^(dr\\s*/\\s*cr|cr\\s*/\\s*dr|type|txn type|transaction type|debit\\s*/\\s*credit|credit\\s*/\\s*debit|d\\s*/\\s*c|cr/dr)$", IC)
    private val DEBIT_H = Regex("withdraw|debit|\\bdr\\b|paid out|spent", IC)
    private val CREDIT_H = Regex("deposit|credit|\\bcr\\b|paid in|received", IC)
    private val AMOUNT_H = Regex("^(txn |transaction )?amount( \\(.*\\))?\\.?$|^amt\\.?$", IC)

    private fun detect(row: List<String>): Columns? {
        fun first(p: (String) -> Boolean) = row.indexOfFirst { p(it.trim()) }
        val date = first { DATE_H.matches(it) }.takeIf { it >= 0 } ?: first { it.contains("date", true) && !VALUE_DATE_H.containsMatchIn(it) }
        if (date < 0) return null
        val bal = first { BAL_H.containsMatchIn(it) }
        val type = first { TYPE_H.matches(it) }
        val desc = first { DESC_H.containsMatchIn(it) }
        val ref = first { REF_H.containsMatchIn(it) && !DESC_H.containsMatchIn(it) }
        val taken = setOf(date, bal, type, desc, ref)
        val debit = row.indices.firstOrNull { it !in taken && DEBIT_H.containsMatchIn(row[it]) } ?: -1
        val credit = row.indices.firstOrNull { it !in taken && it != debit && CREDIT_H.containsMatchIn(row[it]) } ?: -1
        val amount = row.indices.firstOrNull { it !in taken && it != debit && it != credit && AMOUNT_H.matches(row[it].trim()) } ?: -1
        if (desc < 0 || (debit < 0 && credit < 0 && amount < 0)) return null
        return Columns(date, desc, ref, debit, credit, amount, type, bal)
    }

    private fun money(s: String?): Pair<Double, TxnType?>? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty() || t == "-") return null
        val m = Regex("^\\(?\\s*([-+])?\\s*(?:₹|Rs\\.?|INR)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(Cr|Dr)?\\.?\\s*\\)?$", IC).find(t) ?: return null
        val v = m.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        if (v == 0.0) return null
        val marker = when (m.groupValues[3].lowercase()) {
            "cr" -> TxnType.CREDIT
            "dr" -> TxnType.DEBIT
            else -> if (m.groupValues[1] == "-") TxnType.CREDIT else null
        }
        return v to marker
    }

    fun parse(rows: List<List<String>>): StatementParse {
        if (rows.isEmpty()) return StatementParse(emptyList(), hadText = false)
        val headerIdx = rows.indexOfFirst { detect(it) != null }
        if (headerIdx < 0) return StatementParse(emptyList(), hadText = true)
        val cols = detect(rows[headerIdx])!!
        val preamble = rows.take(headerIdx).joinToString("\n") { it.joinToString(" ") }
        val bank = StatementImporter.detectBank(preamble)
        val account = StatementImporter.detectAccount(preamble)
        val isCard = Regex("credit\\s*card|card\\s+statement", IC).containsMatchIn(preamble)
        val out = mutableListOf<StatementEntry>()
        for (r in rows.drop(headerIdx + 1)) {
            fun cell(i: Int) = if (i >= 0) r.getOrNull(i).orEmpty().trim() else ""
            val date = StatementDates.parse(cell(cols.date)) ?: continue
            val desc = cell(cols.desc).replace(Regex("\\s+"), " ")
            if (desc.isBlank() || Regex("^(opening|closing)\\s+bal", IC).containsMatchIn(desc)) continue
            val debit = money(cell(cols.debit))
            val credit = money(cell(cols.credit))
            val (amount, type) = when {
                debit != null && credit == null -> debit.first to TxnType.DEBIT
                credit != null && debit == null -> credit.first to TxnType.CREDIT
                debit != null && credit != null -> debit.first to TxnType.DEBIT
                cols.amount >= 0 -> {
                    val a = money(cell(cols.amount)) ?: continue
                    val t = cell(cols.type).lowercase()
                    val ty = when {
                        t.startsWith("c") -> TxnType.CREDIT
                        t.startsWith("d") || t.startsWith("w") -> TxnType.DEBIT
                        else -> a.second ?: TxnType.DEBIT
                    }
                    a.first to ty
                }
                else -> continue
            }
            out += StatementImporter.entryFrom(date, desc, amount, type, cell(cols.ref), r.joinToString(", ").take(600), bank, account, isCard)
        }
        return StatementParse(out, bank, account)
    }
}
