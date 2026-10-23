package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.ParsedTransaction
import com.jayant.spendsense.data.TxnType
import kotlin.math.abs

/**
 * Pure-Kotlin statement reader. Feed it the text lines of a PDF statement ([parseLines]) or the rows of an
 * exported CSV ([parseCsvText] / [parseCsvRows]). Never logs or stores statement text.
 *
 * Text layout handled: a line starting with a date opens a record; following lines without a date are
 * narration continuations. The amount cluster (withdrawal / deposit / balance, or a single amount with a
 * Dr/Cr marker) is the run of numbers at the end of a line.
 */
object StatementImporter {
    private const val MAX_RECORD_LINES = 6
    private val IC = RegexOption.IGNORE_CASE

    // ---- amount cluster ---------------------------------------------------------------------------------

    private const val NUM = "(?:\\d{1,3}(?:,\\d{2,3})+|\\d+)\\.\\d{2}"
    private const val ITEM =
        "(?:(?:Cr|Dr|C|D)\\s+)?[-+]?(?:(?:₹|Rs\\.?|INR)\\s?)?$NUM\\s*(?:\\(?\\s*(?:Cr|Dr)\\.?\\s*\\)?)?"
    private const val PLACEHOLDER = "-{1,2}"
    private val CLUSTER = Regex("(?<![\\w.,])(?:$ITEM|$PLACEHOLDER)(?:\\s+(?:$ITEM|$PLACEHOLDER))*\\s*$", IC)
    private val ITEM_SPLIT = Regex("(?:$ITEM)|(?<=\\s|^)$PLACEHOLDER(?=\\s|$)", IC)
    private val NUM_ONLY = Regex(NUM)

    private data class Item(val value: Double?, val marker: TxnType?, val negative: Boolean)

    private val MARKER = Regex("\\b(Cr|Dr|C|D)\\b", IC)

    private fun parseItems(cluster: String): List<Item> =
        ITEM_SPLIT.findAll(cluster.trim()).map { m ->
            val t = m.value.trim()
            val num = NUM_ONLY.find(t)
            val n = num?.value?.replace(",", "")?.toDoubleOrNull()
            if (num == null || n == null) Item(null, null, false) else {
                val before = t.substring(0, num.range.first)
                val after = t.substring(num.range.last + 1)
                val tag = MARKER.find(after)?.value ?: MARKER.find(before)?.value
                val marker = when (tag?.lowercase()) {
                    "cr", "c" -> TxnType.CREDIT
                    "dr", "d" -> TxnType.DEBIT
                    else -> null
                }
                Item(if (n == 0.0) null else n, marker, before.contains('-'))
            }
        }.toList()

    // ---- misc patterns ----------------------------------------------------------------------------------

    private val SKIP_DESC = Regex(
        "^(opening|closing)\\s+bal|^balance\\s+(b/?f|c/?f|brought|carried)|^b/?f\\b|^c/?f\\b|^brought forward|^carried forward|" +
            "^total\\b|^statement summary|^grand total|^page \\d|^opening|^closing",
        IC,
    )
    private val STOP_LINE = Regex(
        "^(page\\s+\\d+|statement\\s+(of|summary|period|date)|opening\\s+balance|closing\\s+balance|total\\b|" +
            "this is a computer|registered office|end of statement|\\*+\\s*end|account\\s+(no|number|summary|statement)|" +
            "customer\\s+(id|name)|gstin|date\\s+(narration|description|particulars|transaction)|" +
            "txn\\s+date|transaction\\s+date\\s|sr\\.?\\s*no|s\\.?\\s*no\\b|generated\\s+(on|by)|" +
            "withdrawal|deposit|debit\\s+credit|important\\s+information|hdfc bank limited|branch\\b|ifsc\\b|micr\\b|nominee\\b)",
        IC,
    )
    private val OPENING_LINE = Regex("(opening|brought forward|b/f)[^\\d]{0,30}(?:₹|Rs\\.?|INR)?\\s*($NUM)", IC)

    private val BANKS = listOf(
        Regex("HDFC\\s*BANK", IC) to "HDFC Bank",
        Regex("STATE\\s+BANK\\s+OF\\s+INDIA|\\bSBI\\b", IC) to "SBI",
        Regex("ICICI", IC) to "ICICI Bank",
        Regex("AXIS\\s*BANK", IC) to "Axis Bank",
        Regex("KOTAK", IC) to "Kotak Bank",
        Regex("IDFC", IC) to "IDFC First",
        Regex("YES\\s*BANK", IC) to "Yes Bank",
        Regex("INDUSIND", IC) to "IndusInd Bank",
        Regex("FEDERAL\\s*BANK", IC) to "Federal Bank",
        Regex("PUNJAB\\s+NATIONAL|\\bPNB\\b", IC) to "PNB",
        Regex("BANK\\s+OF\\s+BARODA", IC) to "Bank of Baroda",
        Regex("CANARA\\s*BANK", IC) to "Canara Bank",
        Regex("AMERICAN\\s+EXPRESS|\\bAMEX\\b", IC) to "American Express",
        Regex("ONECARD", IC) to "OneCard",
        Regex("\\bRBL\\b", IC) to "RBL Bank",
    )
    private val ACCOUNT_NO = Regex("(?:A/?c(?:count)?\\.?\\s*(?:No\\.?|Number|#)?|Acct\\.?)\\s*[:\\-]?\\s*([Xx*\\d][Xx*\\d ]{5,})", IC)
    private val CARD_NO = Regex("Card\\s*(?:No\\.?|Number)?\\s*[:\\-]?\\s*((?:[\\dXx*]{4}[ \\-]?){3}[\\dXx*]{2,4})", IC)
    private val CARD_HINT = Regex("credit\\s*card|card\\s+statement|card\\s+(no|number)", IC)

    fun detectBank(text: String): String? =
        BANKS.mapNotNull { (re, name) -> re.find(text)?.let { it.range.first to name } }.minByOrNull { it.first }?.second

    fun detectAccount(text: String): String? {
        val raw = CARD_NO.find(text)?.groupValues?.get(1) ?: ACCOUNT_NO.find(text)?.groupValues?.get(1) ?: return null
        val digits = raw.filter(Char::isDigit)
        return if (digits.length >= 4) "XX" + digits.takeLast(4) else null
    }

    private fun clean(s: String): String =
        s.replace(' ', ' ').replace('|', ' ').replace('\t', ' ').replace(Regex("\\s+"), " ").trim()

    // ---- PDF / text lines -------------------------------------------------------------------------------

    fun parseLines(rawLines: List<String>): StatementParse {
        val lines = rawLines.map(::clean).filter { it.isNotEmpty() }
        if (lines.isEmpty()) return StatementParse(emptyList(), hadText = false)
        val head = lines.take(40).joinToString("\n")
        val bank = detectBank(head)
        val account = detectAccount(head)
        val isCard = CARD_HINT.containsMatchIn(head)

        val records = mutableListOf<List<String>>()
        var cur: MutableList<String>? = null
        var openingBalance: Double? = null
        for (line in lines) {
            val lead = StatementDates.matchLeading(line)
            when {
                lead != null -> {
                    cur?.let { records += it }
                    cur = mutableListOf(line)
                }
                cur != null -> {
                    if (STOP_LINE.containsMatchIn(line) || cur.size >= MAX_RECORD_LINES) {
                        records += cur; cur = null
                    } else cur.add(line)
                }
                records.isEmpty() && openingBalance == null ->
                    OPENING_LINE.find(line)?.let { openingBalance = it.groupValues[1].replace(",", "").toDoubleOrNull() }
            }
        }
        cur?.let { records += it }

        val out = mutableListOf<StatementEntry>()
        var prevBalance = openingBalance
        for (rec in records) {
            val row = readRecord(rec) ?: continue
            if (row.skip) {
                row.balance?.let { prevBalance = it }
                continue
            }
            val type = resolveType(row, prevBalance)
            row.balance?.let { prevBalance = it }
            out += toEntry(row.date, row.time, row.desc, row.refColumn, row.amount, type, rec.joinToString(" "), bank, account, isCard)
        }
        return StatementParse(out, bank, account)
    }

    private class Row(
        val date: java.time.LocalDate,
        val time: java.time.LocalTime?,
        val desc: String,
        val refColumn: String?,
        val amount: Double,
        val marker: TxnType?,
        val positional: TxnType?,
        val balance: Double?,
        val skip: Boolean,
    )

    private fun readRecord(rec: List<String>): Row? {
        val first = rec[0]
        val lead = StatementDates.matchLeading(first) ?: return null
        var rest0 = first.substring(lead.end).trim()
        StatementDates.matchLeading(rest0)?.let { rest0 = rest0.substring(it.end).trim() }

        // The amount cluster is the run of numbers at the end of the last line that has one.
        var clusterLine = -1
        var cluster: MatchResult? = null
        for (i in rec.indices.reversed()) {
            val text = if (i == 0) rest0 else rec[i]
            val m = CLUSTER.find(text)
            if (m != null && NUM_ONLY.containsMatchIn(m.value)) { clusterLine = i; cluster = m; break }
        }
        if (cluster == null) return null

        val pieces = rec.indices.map { i ->
            var t = if (i == 0) rest0 else rec[i]
            if (i == clusterLine) {
                t = t.substring(0, cluster.range.first).trim()
                StatementDates.matchTrailing(t)?.let { t = t.substring(0, it.start).trim() }
            }
            t
        }.toMutableList()

        // Reference / cheque number column: digits at the end of the amount line's text (HDFC, SBI), or right after the date (ICICI card).
        var refColumn: String? = null
        val cl = pieces[clusterLine]
        val lastTok = cl.substringAfterLast(' ', "")
        if (lastTok.length >= 6 && lastTok.all { it.isDigit() } && cl.contains(' ')) {
            refColumn = lastTok
            pieces[clusterLine] = cl.removeSuffix(lastTok).trim()
        } else if (clusterLine == 0) {
            val firstTok = cl.substringBefore(' ')
            if (firstTok.length >= 8 && firstTok.all { it.isDigit() } && cl.contains(' ')) {
                refColumn = firstTok
                pieces[0] = cl.removePrefix(firstTok).trim()
            }
        }

        val desc = joinPieces(pieces)
        val items = parseItems(cluster.value)
        var amount: Double? = null
        var marker: TxnType? = null
        var positional: TxnType? = null
        var balance: Double? = null
        val skip = SKIP_DESC.containsMatchIn(desc.take(40)) || desc.isBlank()
        when {
            items.size >= 3 -> {
                val (d, c, b) = items.takeLast(3)
                balance = b.value
                when {
                    d.value != null && c.value == null -> { amount = d.value; positional = TxnType.DEBIT }
                    c.value != null && d.value == null -> { amount = c.value; positional = TxnType.CREDIT }
                    d.value != null && c.value != null -> { amount = d.value; positional = TxnType.DEBIT }
                }
            }
            items.size == 2 -> {
                val (a, b) = items
                if (a.value == null || b.value == null) {
                    // Debit/Credit columns with no balance column.
                    when {
                        a.value != null -> { amount = a.value; positional = TxnType.DEBIT }
                        b.value != null -> { amount = b.value; positional = TxnType.CREDIT }
                    }
                } else {
                    amount = a.value; marker = a.marker ?: if (a.negative) TxnType.CREDIT else null; balance = b.value
                }
            }
            items.size == 1 -> {
                val a = items[0]
                amount = a.value
                marker = a.marker ?: if (a.negative) TxnType.CREDIT else null
                // A lone number on an opening/closing line is a balance, not a movement.
                if (skip) balance = a.value
            }
        }
        if (amount == null) return if (skip) Row(lead.date, lead.time, desc, refColumn, 0.0, null, null, balance, true) else null
        return Row(lead.date, lead.time, desc, refColumn, amount, marker, positional, balance, skip)
    }

    private fun joinPieces(pieces: List<String>): String {
        val sb = StringBuilder()
        for (p in pieces) {
            if (p.isEmpty()) continue
            if (sb.isEmpty()) { sb.append(p); continue }
            val tok = sb.toString().substringAfterLast(' ')
            val idLike = tok.length >= 12 && tok.any { it in "-/@" || it.isDigit() }
            if (!idLike) sb.append(' ')
            sb.append(p)
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private val CREDIT_WORDS = Regex(
        "\\b(salary|refund|reversal|reversed|cashback|interest (credit|paid|earned)|int\\.? ?pd|deposit|credited|received|rec'd|dividend|neft cr|imps cr|upi/cr|payment received)\\b|\\bcr\\b|^by\\b",
        IC,
    )

    private fun resolveType(row: Row, prevBalance: Double?): TxnType {
        row.positional?.let { return it }
        row.marker?.let { return it }
        val bal = row.balance
        if (bal != null && prevBalance != null) {
            if (abs(prevBalance + row.amount - bal) < 0.015) return TxnType.CREDIT
            if (abs(prevBalance - row.amount - bal) < 0.015) return TxnType.DEBIT
        }
        if (row.balance == null) return TxnType.DEBIT // lone amount, no marker: card purchase
        return if (CREDIT_WORDS.containsMatchIn(row.desc)) TxnType.CREDIT else TxnType.DEBIT
    }

    private fun toEntry(
        date: java.time.LocalDate,
        time: java.time.LocalTime?,
        desc: String,
        refColumn: String?,
        amount: Double,
        type: TxnType,
        raw: String,
        bank: String?,
        account: String?,
        isCard: Boolean,
    ): StatementEntry {
        val counterparty = Narration.counterparty(desc, isCard)
        val mode = Narration.mode(desc, isCard)
        val billPayment = isCard && type == TxnType.CREDIT && counterparty == "Credit card bill payment"
        return StatementEntry(
            txn = ParsedTransaction(
                amount = amount,
                type = type,
                counterparty = counterparty,
                account = account,
                bank = bank,
                upiRef = Narration.upiRef(desc, refColumn),
                timestamp = StatementDates.toMillis(date, time),
                paymentMode = mode,
            ),
            description = desc,
            rawLine = raw.take(600),
            suggestSkip = billPayment,
            timed = time != null,
        )
    }

    /** Builds an entry from already-structured fields (CSV rows, AI output). */
    internal fun entryFrom(
        date: java.time.LocalDate,
        desc: String,
        amount: Double,
        type: TxnType,
        refColumn: String?,
        raw: String,
        bank: String?,
        account: String?,
        isCard: Boolean,
    ) = toEntry(date, null, desc.replace(Regex("\\s+"), " ").trim(), refColumn, amount, type, raw, bank, account, isCard)

    /**
     * Rows with no printed time all sit at the same instant, which the repository's "same amount within 10
     * minutes" dedupe would collapse (two Rs 20 chai on one day). Spread untimed rows of each day 11 minutes
     * apart, in statement order, starting 08:00 IST.
     */
    fun spreadUntimed(entries: List<StatementEntry>): List<StatementEntry> {
        val perDay = HashMap<java.time.LocalDate, Int>()
        return entries.map { e ->
            if (e.timed) e else {
                val date = java.time.Instant.ofEpochMilli(e.txn.timestamp).atZone(StatementDates.IST).toLocalDate()
                val i = perDay.merge(date, 1, Int::plus)!! - 1
                val minutes = (8 * 60 + 11 * i).coerceAtMost(23 * 60 + 59)
                val t = java.time.LocalTime.of(minutes / 60, minutes % 60)
                e.copy(txn = e.txn.copy(timestamp = StatementDates.toMillis(date, t)))
            }
        }
    }

    // ---- CSV --------------------------------------------------------------------------------------------

    fun parseCsvText(text: String): StatementParse = parseCsvRows(CsvStatementParser.tokenize(text))

    fun parseCsvRows(rows: List<List<String>>): StatementParse = CsvStatementParser.parse(rows)
}
