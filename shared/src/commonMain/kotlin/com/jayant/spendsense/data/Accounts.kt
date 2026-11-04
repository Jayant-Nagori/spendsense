package com.jayant.spendsense.data

import com.jayant.spendsense.ingest.TextSafety

/**
 * Card / account identity of a transaction: issuing bank, instrument (credit card, debit card, UPI from account …)
 * and last 4 digits, plus transfer detection. Pure; used by the parsers at ingest and by the v6 backfill, which
 * re-reads the stored fields of old rows (never stores new message text).
 */
object Accounts {
    /** Canonical issuer names, matched on (upper-cased) aliases from parsers, sender ids and message text. */
    private val ISSUERS: List<Pair<String, List<String>>> = listOf(
        "Axis Bank" to listOf("AXIS"),
        "HDFC Bank" to listOf("HDFC"),
        "ICICI Bank" to listOf("ICICI"),
        "SBI" to listOf("SBI", "STATE BANK"),
        "Kotak" to listOf("KOTAK"),
        "IDFC FIRST" to listOf("IDFC"),
        "AU Bank" to listOf("AU BANK", "AU SMALL", "AUBANK", "AUSFB"),
        "Yes Bank" to listOf("YES BANK", "YESB"),
        "IndusInd" to listOf("INDUSIND", "INDUS"),
        "OneCard" to listOf("ONECARD", "ONE CARD", "ONECRD"),
        "Federal Bank" to listOf("FEDERAL", "FEDBNK"),
        "Amex" to listOf("AMEX", "AMERICAN EXPRESS"),
        "Bank of Baroda" to listOf("BANK OF BARODA", "BARODA", "BOB"),
        "PNB" to listOf("PNB", "PUNJAB NATIONAL"),
        "Canara Bank" to listOf("CANARA", "CANBNK"),
        "Union Bank" to listOf("UNION BANK", "UNIONB", "UBOI"),
        "RBL Bank" to listOf("RBL"),
        "IDBI Bank" to listOf("IDBI"),
        "Bank of India" to listOf("BANK OF INDIA", "BOIIND"),
        "Standard Chartered" to listOf("STANDARD CHARTERED", "SCBANK"),
        "HSBC" to listOf("HSBC"),
        "Citi" to listOf("CITI"),
        "Slice" to listOf("SLICE"),
        "Jupiter" to listOf("JUPITER"),
        "Fi" to listOf("FIMONY"),
    )

    private val ISSUER_RX: List<Pair<String, Regex>> = ISSUERS.flatMap { (name, aliases) ->
        aliases.map { a -> name to Regex("(?<![A-Z])${Regex.escape(a)}(?![A-Z])") }
    }

    /** Issuers that only issue credit cards. */
    private val CARD_ONLY = setOf("OneCard", "Amex", "Slice")

    /** "HDFC Bank" / "Kotak Bank" / "IDFC First" (parser names) → canonical; unknown names pass through trimmed. */
    fun canonicalIssuer(bank: String?, text: String? = null): String? {
        bank?.trim()?.takeIf { it.isNotEmpty() }?.let { b ->
            val up = b.uppercase()
            ISSUER_RX.firstOrNull { (_, rx) -> rx.containsMatchIn(up) }?.let { return it.first }
            return b.take(40)
        }
        val up = text?.let { TextSafety.bound(it, 600).uppercase() } ?: return null
        return ISSUER_RX.firstOrNull { (_, rx) -> rx.containsMatchIn(up) }?.first
    }

    private val IC = setOf(RegexOption.IGNORE_CASE)
    private val CREDIT_CARD = Regex("credit ?card|\\bcc\\b|avl\\.? ?l(?:i)?mt|available limit|credit limit|avl limit|card limit", IC)
    private val DEBIT_CARD = Regex("debit ?card|\\bdc\\b|atm card|rupay debit|visa debit", IC)
    private val CARD = Regex("\\bcard\\b", IC)
    private val BALANCE = Regex("\\b(?:avl|avbl|available)\\.? ?bal|balance", IC)

    /** Instrument from the message text and parsed payment mode. */
    fun instrument(text: String, mode: PaymentMode?, issuer: String?): Instrument? {
        val t = TextSafety.bound(text, 800)
        return when {
            mode == PaymentMode.CASH -> Instrument.CASH
            CREDIT_CARD.containsMatchIn(t) -> Instrument.CREDIT_CARD
            DEBIT_CARD.containsMatchIn(t) -> Instrument.DEBIT_CARD
            mode == PaymentMode.UPI -> Instrument.UPI
            mode == PaymentMode.CARD || CARD.containsMatchIn(t) ->
                // A plain "card" with an account balance is a debit card; with a limit, or from a card-only issuer, credit.
                if (issuer !in CARD_ONLY && BALANCE.containsMatchIn(t)) Instrument.DEBIT_CARD else Instrument.CREDIT_CARD
            mode == PaymentMode.NETBANKING -> Instrument.NETBANKING
            mode == PaymentMode.WALLET -> Instrument.WALLET
            issuer != null -> Instrument.OTHER
            else -> null
        }
    }

    /** "XX1234" / "**1234" / "1234" → "1234". */
    fun last4(account: String?): String? = account?.filter(Char::isDigit)?.takeIf { it.length >= 3 }?.takeLast(4)

    private val CARD_BILL = Regex(
        "credit ?card (?:bill|payment|dues)|towards your (?:\\w+ )?credit ?card|\\bcc (?:bill|payment)\\b|card bill" +
            "|paid to (?:\\w+ ){0,3}credit ?card|payment (?:of [^.]{0,30})?received (?:on|for|towards) (?:your )?(?:\\w+ )?credit ?card",
        IC,
    )
    private val SELF = Regex("\\bself[- ]?transfer\\b|\\bto self\\b|\\bown account\\b|\\bbetween your accounts\\b|\\bfund transfer to self\\b", IC)

    /** Card bill payments and own-account transfers are not spending (and their credits are not income). */
    fun transferKind(text: String, counterparty: String?): TransferKind? {
        val t = TextSafety.bound(text, 800)
        val cp = counterparty.orEmpty()
        return when {
            // Judged on the message, not on a payee name the payee controls (R3-11).
            CARD_BILL.containsMatchIn(t) || cp.equals("Credit card bill payment", true) -> TransferKind.CARD_BILL
            SELF.containsMatchIn(t) -> TransferKind.SELF
            else -> null
        }
    }

    /** Stable grouping key: "Axis Bank|CREDIT_CARD|1234". Rows without any identity group as "unknown". */
    fun key(issuer: String?, instrument: Instrument?, last4: String?): String =
        if (issuer == null && instrument == null && last4 == null) UNKNOWN
        else "${issuer.orEmpty()}|${instrument?.name.orEmpty()}|${last4.orEmpty()}"

    fun key(t: Transaction): String = key(t.issuer, t.instrument, t.last4)

    /**
     * Fills issuer / instrument / last4 / transfer from the row's own parsed fields when missing. Used for every new
     * row and by the v6 backfill. A detected transfer is never "spend" and never asks the split question.
     */
    fun derive(t: Transaction): Transaction {
        val issuer = t.issuer ?: canonicalIssuer(t.bank)
        val instrument = t.instrument ?: if (t.source == TxnSource.MANUAL && t.paymentMode == PaymentMode.CASH) Instrument.CASH
            else instrument(t.rawText, t.paymentMode, issuer)
        val kind = t.transferKind ?: transferKind(t.rawText, t.counterparty)
        return t.copy(
            issuer = issuer, instrument = instrument, last4 = t.last4 ?: last4(t.account),
            transferKind = kind, isTransfer = t.isTransfer || kind != null,
            reviewed = t.reviewed || kind != null,
        )
    }

    const val UNKNOWN = "unknown"

    /** "Axis Bank Credit Card ••1234", "HDFC Bank UPI ••5678", "Cash". */
    fun label(issuer: String?, instrument: Instrument?, last4: String?): String {
        if (instrument == Instrument.CASH && issuer == null) return "Cash"
        val parts = listOfNotNull(issuer, instrument?.takeIf { it != Instrument.OTHER || issuer == null }?.label).joinToString(" ")
            .ifEmpty { "Unknown account" }
        return if (last4 != null) "$parts ••$last4" else parts
    }

    fun labelForKey(key: String): String {
        if (key == UNKNOWN) return "Other / unknown"
        val (i, ins, l4) = (key.split('|') + listOf("", "", "")).take(3)
        return label(i.ifEmpty { null }, ins.ifEmpty { null }?.let { runCatching { Instrument.valueOf(it) }.getOrNull() }, l4.ifEmpty { null })
    }

    /** Two-letter monogram for the bank tile: "AX", "HD", "SB"; "₹" for cash. */
    fun monogram(key: String): String {
        if (key == UNKNOWN) return "··"
        val issuer = key.substringBefore('|')
        if (issuer.isEmpty()) return if (key.contains("|CASH|")) "₹" else "··"
        val words = issuer.split(' ').filter { it.isNotBlank() }
        return when {
            issuer.length <= 4 && issuer.all(Char::isUpperCase) -> issuer.take(3)
            words.size >= 2 && words[1].first().isUpperCase() && words[1] != "Bank" -> "${words[0].first()}${words[1].first()}"
            else -> issuer.take(2).uppercase()
        }
    }
}
