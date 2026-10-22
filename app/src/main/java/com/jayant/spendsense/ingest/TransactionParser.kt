package com.jayant.spendsense.ingest

import com.jayant.spendsense.data.ParsedTransaction
import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.TxnType

/** Pure Kotlin (no android.*) so it is unit-testable on the JVM. */
object TransactionParser {

    private val IC = setOf(RegexOption.IGNORE_CASE)

    // Things that look like money messages but are not completed transactions.
    private val REJECT = Regex(
        listOf(
            "\\botp\\b", "one[ -]?time password", "verification code", "do not share",
            "will be debited", "will be auto[- ]?debited", "is due", "due on", "due date", "due by",
            "minimum amount due", "total amount due", "statement (is|has been) generated",
            "requested (money|rs|inr|₹)", "has requested", "is requesting", "collect request", "payment request",
            "declined", "\\bfailed\\b", "unsuccessful", "could not be", "was not successful", "insufficient",
            "pre-?approved", "loan offer", "apply now", "eligible for", "get upto", "get up to", "offer valid",
            "\\bkyc\\b", "mandate (has been )?(created|registered|set up)", "autopay (is )?(scheduled|upcoming|set)",
            "upcoming (payment|debit)", "reminder", "recharge now", "pay now",
        ).joinToString("|"),
        IC,
    )

    // Refunds/reversals are credits even when the text mentions a failed transaction.
    private val REFUND = Regex("\\b(refund(ed)?|reversed|reversal|cashback (of|credited))\\b", IC)
    private val FUTURE_REFUND = Regex("will be (refunded|reversed|credited back)|if debited", IC)

    private fun isRefund(text: String) = REFUND.containsMatchIn(text) && !FUTURE_REFUND.containsMatchIn(text)

    private val CC_BILL_PAID = Regex(
        "payment (of )?(rs\\.?|inr|₹)?\\s*[0-9,.]+\\s*(has been |was |is )?(received|credited)\\b.{0,40}(credit ?card|card (no|ending|xx))",
        IC,
    )

    private val AMOUNT = Regex("(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", IC)
    private val AMOUNT_BARE = Regex(
        "(?:debited by|credited by|debited for|credited with|credited for|debited with|withdrawn|txn of|transaction of|purchase of)\\s*(?:rs\\.?|inr|₹)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        IC,
    )

    private val DEBIT_WORDS = Regex(
        "\\b(debited|spent|paid|sent|withdrawn|withdrawal|purchase|deducted|txn of|transaction of|used (at|for)|dr\\.?)(?=\\W|$)",
        IC,
    )
    private val CREDIT_WORDS = Regex("\\b(credited|received|deposited|cr\\.?)(?=\\W|$)", IC)

    private val ACCOUNT = Regex(
        "(?:a/c|ac|acct|account|card)\\s*(?:no\\.?|number|ending(?: with| in)?)?\\s*[:\\-]?\\s*([xX*]*\\d{3,6})\\b",
        IC,
    )
    private val REF = Regex(
        "(?:upi\\s*ref(?:erence)?(?:\\s*no\\.?)?|upi\\s*txn\\s*id|ref(?:erence)?\\s*(?:no\\.?|number|#|id)?|refno|rrn|utr(?:\\s*no\\.?)?|txn\\s*(?:id|no\\.?)|transaction\\s*(?:id|no\\.?)|imps\\s*ref)\\s*[:.\\-]?\\s*([A-Za-z0-9]{6,})",
        IC,
    )
    private val VPA = Regex("\\b([a-zA-Z0-9._\\-]{2,}@[a-zA-Z]{2,})\\b")
    private const val NAME = "([A-Za-z0-9][A-Za-z0-9 .&'_*\\-/]{1,45}?)"
    private const val STOP =
        "(?=\\s+(?:on|via|using|ref|refno|upi|txn|avl|avbl|from|by|dated|at|info|for|is|was|has|with|avl|not)\\b|\\s+-|\\s*[.,;:(]|\\s*$)"
    private val AT_MERCHANT = Regex("\\b(?:at|@)\\s+$NAME$STOP", IC)
    private val TO_NAME = Regex("\\b(?:paid to|sent to|transferred to|trf to|transfer to|to vpa|towards|to)\\s+$NAME$STOP", IC)
    private val ON_MERCHANT = Regex("\\bon\\s+(?!\\d|date\\b|your\\b)$NAME$STOP", IC)
    private val FROM_NAME = Regex("\\b(?:received from|from vpa|by vpa|from|by)\\s+$NAME$STOP", IC)
    private val NAME_BEFORE_CREDITED = Regex(";\\s*([A-Za-z][A-Za-z .]{2,40}?)\\s+credited", IC)

    private val BANK_CODES = linkedMapOf(
        "HDFC" to "HDFC Bank", "SBI" to "SBI", "ICICI" to "ICICI Bank", "AXIS" to "Axis Bank",
        "KOTAK" to "Kotak Bank", "PNB" to "PNB", "BOB" to "Bank of Baroda", "BARODA" to "Bank of Baroda",
        "YESB" to "Yes Bank", "IDFC" to "IDFC First", "INDUS" to "IndusInd Bank", "AUBANK" to "AU Bank",
        "AUSFB" to "AU Bank", "FEDBNK" to "Federal Bank", "FEDERAL" to "Federal Bank", "CANBNK" to "Canara Bank",
        "CANARA" to "Canara Bank", "UNIONB" to "Union Bank", "UBOI" to "Union Bank", "ONECRD" to "OneCard",
        "ONECARD" to "OneCard", "AMEX" to "American Express", "PAYTM" to "Paytm", "AIRBNK" to "Airtel Payments Bank",
        "JUPITER" to "Jupiter", "FIMONY" to "Fi", "SLICE" to "Slice", "RBL" to "RBL Bank", "IDBI" to "IDBI Bank",
        "BOIIND" to "Bank of India", "CENTBK" to "Central Bank", "IOB" to "Indian Overseas Bank", "INDBNK" to "Indian Bank",
        "SCBANK" to "Standard Chartered", "HSBC" to "HSBC", "CITI" to "Citi", "DBS" to "DBS Bank", "EQUITAS" to "Equitas",
    )

    private val BANK_NAMES = linkedMapOf(
        "HDFC" to "HDFC Bank", "SBI" to "SBI", "STATE BANK" to "SBI", "ICICI" to "ICICI Bank", "AXIS" to "Axis Bank",
        "KOTAK" to "Kotak Bank", "PNB" to "PNB", "PUNJAB NATIONAL" to "PNB", "BANK OF BARODA" to "Bank of Baroda",
        "YES BANK" to "Yes Bank", "IDFC" to "IDFC First", "INDUSIND" to "IndusInd Bank", "AU BANK" to "AU Bank",
        "AU SMALL" to "AU Bank", "FEDERAL" to "Federal Bank", "CANARA" to "Canara Bank", "UNION BANK" to "Union Bank",
        "ONECARD" to "OneCard", "AMEX" to "American Express", "AMERICAN EXPRESS" to "American Express",
        "PAYTM" to "Paytm", "RBL" to "RBL Bank", "IDBI" to "IDBI Bank",
    )

    private val UPI_APP_PACKAGES = mapOf(
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.phonepe.app" to "PhonePe",
        "net.one97.paytm" to "Paytm",
        "in.org.npci.upiapp" to "BHIM",
        "com.dreamplug.androidapp" to "CRED",
        "in.amazon.mShop.android.shopping" to "Amazon Pay",
        "com.mobikwik_new" to "MobiKwik",
        "com.freecharge.android" to "Freecharge",
        "com.sliceit.app" to "Slice",
        "money.jupiter" to "Jupiter",
        "com.epifi.paisa" to "Fi",
        "com.navi.android" to "Navi",
    )
    private val CARD_WALLET_PACKAGES = mapOf(
        "com.google.android.apps.walletnfcrel" to "Google Wallet",
        "com.samsung.android.spay" to "Samsung Wallet",
        "com.samsung.android.samsungpay.gear" to "Samsung Wallet",
    )
    private val BANK_APP_PACKAGES = mapOf(
        "com.snapwork.hdfc" to "HDFC Bank", "com.hdfcbank.payzapp" to "HDFC Bank",
        "com.sbi.lotusintouch" to "SBI", "com.sbi.upi" to "SBI", "com.sbi.SBIFreedomPlus" to "SBI",
        "com.csam.icici.bank.imobile" to "ICICI Bank", "com.axis.mobile" to "Axis Bank",
        "com.msf.kbank.mobile" to "Kotak Bank", "com.idfcfirstbank.optimus" to "IDFC First",
        "com.Version1" to "PNB", "com.bankofbaroda.mconnect" to "Bank of Baroda", "com.yesbank" to "Yes Bank",
        "com.fss.indus" to "IndusInd Bank", "com.aubank.aubankmobile" to "AU Bank",
        "com.federalbank.fedmobile" to "Federal Bank", "com.canarabank.mobility" to "Canara Bank",
        "com.infrasoft.uboi" to "Union Bank", "com.onecard.android" to "OneCard",
        "com.americanexpress.android.acctsvcs.in" to "American Express",
    )

    /** Every package the notification listener should look at. */
    val PAYMENT_PACKAGES: Set<String> = UPI_APP_PACKAGES.keys + CARD_WALLET_PACKAGES.keys + BANK_APP_PACKAGES.keys

    fun appLabel(packageName: String): String? =
        UPI_APP_PACKAGES[packageName] ?: CARD_WALLET_PACKAGES[packageName] ?: BANK_APP_PACKAGES[packageName]

    /** Parses a bank/UPI/card SMS. Returns null for OTPs, promos, reminders, failed txns, balance alerts, etc. */
    fun parseSms(sender: String, body: String, timestamp: Long): ParsedTransaction? {
        val text = normalize(body)
        val isRefund = isRefund(text)
        val isCcBill = CC_BILL_PAID.containsMatchIn(text)
        if (!isRefund && !isCcBill && REJECT.containsMatchIn(text)) return null
        if (!looksFinancial(sender, text)) return null

        val amount = findAmount(text) ?: return null
        val type = if (isRefund || isCcBill) TxnType.CREDIT else detectType(text) ?: return null
        val mode = detectMode(text)
        val counterparty = when {
            isCcBill -> "Credit card bill payment"
            mode == PaymentMode.CASH && type == TxnType.DEBIT -> "ATM"
            else -> findCounterparty(text, type)
        }
        return ParsedTransaction(
            amount = amount,
            type = type,
            counterparty = counterparty,
            account = findAccount(text),
            bank = bankFromSender(sender) ?: bankFromText(text),
            upiRef = findRef(text),
            timestamp = timestamp,
            paymentMode = mode,
        )
    }

    /** Parses a payment-app / bank-app / wallet notification. Null if not a completed payment or receipt. */
    fun parseNotification(packageName: String, title: String?, text: String, timestamp: Long): ParsedTransaction? {
        if (packageName !in PAYMENT_PACKAGES) return null
        val combined = normalize(listOfNotNull(title, text).joinToString(". "))
        val isRefund = isRefund(combined)
        if (!isRefund && REJECT.containsMatchIn(combined)) return null
        if (!isRefund && Regex("\\b(pending|processing|in progress|expir\\w*|scratch card|you won|earned|reward)\\b", IC).containsMatchIn(combined)) return null
        val amount = findAmount(combined) ?: return null
        val debitish = Regex("\\b(paid|sent|debited|spent|payment (was )?successful|payment of|transferred|purchase)\\b", IC)
        val type = when {
            isRefund -> TxnType.CREDIT
            Regex("\\b(received|credited|got|added to)\\b", IC).containsMatchIn(combined) && !debitish.containsMatchIn(combined) -> TxnType.CREDIT
            debitish.containsMatchIn(combined) -> TxnType.DEBIT
            else -> return null
        }
        val mode = when (packageName) {
            in CARD_WALLET_PACKAGES -> PaymentMode.CARD
            in UPI_APP_PACKAGES -> PaymentMode.UPI
            else -> detectMode(combined)
        }
        return ParsedTransaction(
            amount = amount,
            type = type,
            counterparty = findCounterparty(combined, type),
            account = findAccount(combined),
            bank = BANK_APP_PACKAGES[packageName] ?: bankFromText(combined),
            upiRef = findRef(combined),
            timestamp = timestamp,
            paymentMode = mode,
        )
    }

    // ---------------------------------------------------------------------------------------------

    private fun normalize(s: String) = s.replace('\n', ' ').replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun looksFinancial(sender: String, text: String): Boolean {
        val bankSender = Regex("^[A-Z]{2}-[A-Z0-9]{4,9}(-[A-Z])?$", IC).matches(sender.trim()) || bankFromSender(sender) != null
        val ctx = Regex("\\b(a/c|acct|account|card|upi|vpa|imps|neft|rtgs|atm|pos|bank|wallet|ref|txn)\\b", IC).containsMatchIn(text)
        return ctx && (bankSender || Regex("\\b(debited|credited|spent|withdrawn)\\b", IC).containsMatchIn(text))
    }

    private fun parseAmount(raw: String): Double? = raw.replace(",", "").toDoubleOrNull()?.takeIf { it > 0 }

    private fun findAmount(text: String): Double? {
        AMOUNT_BARE.find(text)?.let { m -> parseAmount(m.groupValues[1])?.let { return it } }
        for (m in AMOUNT.findAll(text)) {
            val before = text.substring(maxOf(0, m.range.first - 30), m.range.first).lowercase()
            if (Regex("(bal|balance|limit|avl|avbl|available|outstanding)[^.]*$").containsMatchIn(before)) continue
            parseAmount(m.groupValues[1])?.let { return it }
        }
        return null
    }

    private fun detectType(text: String): TxnType? {
        val d = DEBIT_WORDS.find(text)?.range?.first
        val c = CREDIT_WORDS.find(text)?.range?.first
        return when {
            d == null && c == null -> null
            c == null -> TxnType.DEBIT
            d == null -> TxnType.CREDIT
            // "debited from your a/c ... credited to X" -> debit; "credited to your a/c ... debited from Y" -> credit
            d < c -> TxnType.DEBIT
            else -> TxnType.CREDIT
        }
    }

    private fun detectMode(text: String): PaymentMode = when {
        Regex("\\b(atm|cash withdrawal|withdrawn)\\b", IC).containsMatchIn(text) -> PaymentMode.CASH
        Regex("\\b(upi|vpa)\\b", IC).containsMatchIn(text) || VPA.containsMatchIn(text) -> PaymentMode.UPI
        Regex("\\b(card|pos|contactless|tap to pay)\\b", IC).containsMatchIn(text) -> PaymentMode.CARD
        Regex("\\b(neft|imps|rtgs|net ?banking|netbanking)\\b", IC).containsMatchIn(text) -> PaymentMode.NETBANKING
        Regex("\\bwallet\\b", IC).containsMatchIn(text) -> PaymentMode.WALLET
        else -> PaymentMode.OTHER
    }

    private fun findAccount(text: String): String? {
        val m = ACCOUNT.find(text) ?: return null
        val digits = m.groupValues[1].filter(Char::isDigit)
        return if (digits.length >= 3) "XX" + digits.takeLast(4) else null
    }

    private fun findRef(text: String): String? =
        REF.findAll(text).map { it.groupValues[1] }.firstOrNull { it.count(Char::isDigit) >= 6 }

    private fun bankFromSender(sender: String): String? {
        val parts = sender.uppercase().split('-')
        val code = if (parts.size >= 2) parts[1] else parts[0]
        if (code.length < 3 || code.all { it.isDigit() || it == '+' }) return null
        return BANK_CODES.entries.firstOrNull { code.contains(it.key) }?.value
    }

    private fun bankFromText(text: String): String? {
        val up = text.uppercase()
        return BANK_NAMES.entries.firstOrNull { (k, _) -> Regex("\\b${Regex.escape(k)}\\b").containsMatchIn(up) }?.value
    }

    private fun findCounterparty(text: String, type: TxnType): String? {
        val vpaRegex = if (type == TxnType.DEBIT) {
            Regex("\\b(?:to|vpa|paid to|sent to)\\s+(?:vpa\\s+)?([a-zA-Z0-9._\\-]{2,}@[a-zA-Z]{2,})", IC)
        } else {
            Regex("\\b(?:from|by|vpa)\\s+(?:vpa\\s+)?([a-zA-Z0-9._\\-]{2,}@[a-zA-Z]{2,})", IC)
        }
        vpaRegex.find(text)?.let { return it.groupValues[1].lowercase() }

        val candidates = buildList {
            if (type == TxnType.DEBIT) {
                AT_MERCHANT.findAll(text).forEach { add(it.groupValues[1]) }
                TO_NAME.findAll(text).forEach { add(it.groupValues[1]) }
                ON_MERCHANT.findAll(text).forEach { add(it.groupValues[1]) }
            } else {
                FROM_NAME.findAll(text).forEach { add(it.groupValues[1]) }
                NAME_BEFORE_CREDITED.find(text)?.let { add(it.groupValues[1]) }
            }
        }
        candidates.mapNotNull(::cleanName).firstOrNull { !isNoise(it) }?.let { return it }
        return VPA.find(text)?.groupValues?.get(1)?.lowercase()
    }

    private fun cleanName(raw: String): String? {
        val s = raw.trim().trim('.', ',', '-', ':', ';', '*', '/').replace(Regex("\\s+"), " ")
        return s.takeIf { it.length >= 2 }
    }

    private val NOISE = Regex(
        "^(your|you|the|beneficiary|xx\\w*|rs\\.?|inr|bank|on|via|self|.*\\b(a/c|account|card|acct)\\b.*|\\d+.*)$",
        IC,
    )

    private fun isNoise(s: String) = NOISE.matches(s) || s.all { it.isDigit() || it in "Xx*" }
}
