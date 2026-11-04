package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.PaymentMode

/** Reads counterparty, payment mode and UPI RRN out of a bank narration. Pure Kotlin. */
object Narration {
    private val IC = RegexOption.IGNORE_CASE

    private val RRN = Regex("(?<!\\d)(\\d{12})(?!\\d)")
    private val IFSC = Regex("^[A-Za-z]{4}0[A-Za-z0-9]{6}$")
    private val VPA = Regex("^[A-Za-z0-9._\\-]{2,}@[A-Za-z][A-Za-z0-9.]*$")
    private val UPI_START = Regex("(?<![A-Za-z])UPI(?=[\\-/ :*]|$)", IC)
    private val MASKED_CARD = Regex("\\b\\d{4,6}[Xx*]{2,}\\d{2,4}\\b|\\b[Xx*]{4,}\\d{2,4}\\b")

    private val ATM = Regex("\\b(ATM|NWD|EAW|ATW|CASH WDL|CASH WITHDRAWAL|CASH WITHDRAWL)\\b", IC)
    private val CARD = Regex("\\b(POS|ECOM|E-COM|PUR|PURCHASE|DEBIT CARD|CREDIT CARD|DC INTL|VPS|IPS)\\b", IC)
    private val NETBANKING = Regex("\\b(NEFT|IMPS|RTGS|FUND TRANSFER|FT|TPT|IB|MB|NET BANKING|NETBANKING|INB)\\b", IC)
    private val AUTODEBIT = Regex("\\b(ACH|NACH|ECS|SI|AUTOPAY|MANDATE)\\b", IC)

    /** Segments that carry no counterparty information. */
    private val MARKERS = setOf(
        "P2M", "P2A", "P2P", "P2B", "DR", "CR", "DEBIT", "CREDIT", "UPI", "IMPS", "NEFT", "RTGS", "ACH", "NACH", "ECS",
        "TRF", "TRANSFER", "TO", "BY", "NEFT CR", "NEFT DR", "IMPS CR", "IMPS DR", "ACH D", "ACH C", "MUT", "IB", "MB",
        "INB", "FT", "TPT", "REF", "SELF", "NA", "NIL", "OTHERS",
    )
    private val GENERIC_TAIL = Regex("^(payment|paid|pay|payme|pymt|sent|collect|mandate|ref|txn|upi|trf|transfer|nach|ach)\\b.*", IC)
    private val UTR = Regex("^[A-Za-z]{0,6}[0-9]{8,}[A-Za-z0-9]*$|^[A-Za-z0-9]{16,}$")
    private val BANK_CODE_ONLY = Regex("^[A-Z]{4}$")

    private val ACRONYMS = setOf("ATM", "POS", "UPI", "LIC", "KFC", "PVR", "HDFC", "SBI", "BSNL", "IRCTC", "ICICI", "AXIS", "IDFC", "UPS", "OLA", "GST", "EMI", "NPS", "PPF", "SIP", "LLP", "HP", "BP", "IOCL", "HPCL", "BPCL", "KSEB", "BESCOM", "MSEDCL", "TNEB", "NSDL", "CDSL", "ICCL", "CAMS", "DMART", "IKEA", "IDBI", "RBL", "AU")

    /** Longest narration the regexes look at (real ones are < 200 chars). */
    const val MAX_NARRATION = 500

    fun upiRef(rawDesc: String, refColumn: String? = null): String? {
        val desc = com.jayant.spendsense.ingest.TextSafety.bound(rawDesc, MAX_NARRATION)
        val mode = mode(desc, false)
        val imps = Regex("\\bIMPS\\b", IC).containsMatchIn(desc)
        if (mode == PaymentMode.UPI || imps) {
            RRN.find(desc)?.let { return it.groupValues[1] }
            normalizeRef(refColumn)?.let { return it }
        }
        return null
    }

    /** "0000529512345678" (HDFC) -> "529512345678"; a plain 12-digit ref passes through; anything else is null. */
    fun normalizeRef(raw: String?): String? {
        val d = raw?.trim().orEmpty()
        if (d.isEmpty() || !d.all(Char::isDigit)) return null
        return when {
            d.length == 12 -> d
            d.length in 13..20 && d.take(d.length - 12).all { it == '0' } -> d.takeLast(12)
            else -> null
        }
    }

    fun mode(rawDesc: String, isCardStatement: Boolean): PaymentMode {
        val desc = com.jayant.spendsense.ingest.TextSafety.bound(rawDesc, MAX_NARRATION)
        return when {
            UPI_START.containsMatchIn(desc) -> PaymentMode.UPI
            ATM.containsMatchIn(desc) -> PaymentMode.CASH
            CARD.containsMatchIn(desc) -> PaymentMode.CARD
            NETBANKING.containsMatchIn(desc) -> PaymentMode.NETBANKING
            AUTODEBIT.containsMatchIn(desc) -> PaymentMode.OTHER
            isCardStatement -> PaymentMode.CARD
            else -> PaymentMode.OTHER
        }
    }

    fun counterparty(desc: String, isCardStatement: Boolean = false): String? {
        // Narrations are short; bound regex work so a malformed / hostile statement line can't stall the import.
        val text = com.jayant.spendsense.ingest.TextSafety.bound(desc, MAX_NARRATION).trim().trim('-', '/', ' ')
        if (text.isEmpty()) return null
        when {
            ATM.containsMatchIn(text) && !UPI_START.containsMatchIn(text) && !isCardStatement -> return "ATM"
            Regex("^(INT\\.?\\s*PD|INTEREST|CREDIT INTEREST|INT\\.?\\s*PAID|SAVINGS INT)", IC).containsMatchIn(text) -> return "Interest"
            Regex("CREDIT CARD (BILL )?PAYMENT|CC PAYMENT|BILLDESK.*CARD|PAYMENT RECEIVED|PAYMENT - THANK YOU|PAYMENT THANK YOU", IC).containsMatchIn(text) ->
                return "Credit card bill payment"
        }
        val upi = UPI_START.find(text)
        if (upi != null) fromSegments(text.substring(upi.range.first), delimiterAfterUpi(text, upi.range.first))?.let { return it }
        if (Regex("\\b(NEFT|IMPS|RTGS|ACH|NACH|ECS|FUND TRANSFER)\\b", IC).containsMatchIn(text)) {
            fromSegments(text, '-', alsoSplit = "/*")?.let { return it }
        }
        // Card swipes, bill payments and everything else: strip noise, keep the merchant.
        var s = text
            .replace(MASKED_CARD, " ")
            .replace(Regex("^(BY|TO)\\s+TRANSFER\\b[\\s\\-:]*", IC), "")
            .replace(Regex("^(POS|ECOM|E-COM|PUR|VPS|IPS|PURCHASE|DEBIT CARD|DC INTL)\\b[\\s\\-/:*]*", IC), "")
            .replace(Regex("\\b\\d{6,}\\b"), " ")
            .replace(Regex("\\s+(IN|IND|INDIA)$", IC), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '/', ':', '*', ',', '.')
        if (s.isEmpty() || s.none(Char::isLetter)) return null
        if (s.length > 40) s = s.take(40).trimEnd()
        return prettify(s)
    }

    private fun delimiterAfterUpi(text: String, from: Int): Char {
        val sub = text.substring(from + 3)
        val iSlash = sub.indexOf('/')
        val iDash = sub.indexOf('-')
        return when {
            iSlash >= 0 && (iDash < 0 || iSlash < iDash) -> '/'
            iDash >= 0 -> '-'
            else -> '/'
        }
    }

    private fun fromSegments(text: String, delim: Char, alsoSplit: String = ""): String? {
        val splitter = Regex("[" + Regex.escape(delim.toString()) + Regex.escape(alsoSplit) + "]")
        val parts = text.split(splitter).map { it.trim() }.filter { it.isNotEmpty() }
        for ((i, raw) in parts.withIndex()) {
            val p = raw.trim(' ', ':', '.', ',')
            if (p.isEmpty()) continue
            val up = p.uppercase()
            if (i == 0 && (up in MARKERS || up.startsWith("UPI") || up.startsWith("BY TRANSFER"))) continue
            if (up in MARKERS) continue
            if (p.all { it.isDigit() || it == ' ' }) continue
            if (IFSC.matches(p)) continue
            if (VPA.matches(p)) return p.lowercase()
            if (GENERIC_TAIL.matches(p)) continue
            if (UTR.matches(p) && p.none { it == ' ' }) continue
            if (BANK_CODE_ONLY.matches(p) && i > 1) continue
            if (p.none(Char::isLetter) || p.length < 2) continue
            return prettify(p.take(40).trimEnd())
        }
        return null
    }

    /** "ZEPTO MARKETPLACE" -> "Zepto Marketplace"; mixed-case names and VPAs are left alone. */
    fun prettify(s: String): String {
        if (s.any(Char::isLowerCase) || s.contains('@')) return s
        return s.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w ->
            if (w in ACRONYMS) w else w.lowercase().replaceFirstChar { it.titlecase() }
        }
    }
}
