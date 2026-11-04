package com.jayant.spendsense.ingest

/**
 * Separates real money movements from offers, cashback promos and reminders, for both the notification listener
 * and the SMS parser. A message with promo wording ("get ₹15 back when you pay", "you won ₹5 cashback", "flat ₹100
 * off") is rejected unless it also proves a completed transaction (debited, paid to, UPI Ref, A/c XX1234 …).
 * Cashback that was actually credited is reported separately so it can be stored as income without a pop-up.
 * Pure Kotlin; never logs its input.
 */
object PromoClassifier {
    private val IC = setOf(RegexOption.IGNORE_CASE)

    private val PROMO = Regex(
        listOf(
            "cash ?back", "get (?:(?:rs\\.?|inr|₹)\\s*[0-9,]+(?:\\.[0-9]+)?|[0-9,]+%?)\\s*(?:back|off|cashback)", "\\bearn(?:ed|s)?\\b",
            "\\bwin\\b", "\\bwon\\b", "\\brewards?\\b", "scratch ?card", "\\boffers?\\b", "\\bup ?to\\b", "\\bupto\\b",
            "when you (?:pay|shop|spend|scan|send|recharge|transact)", "on your (?:next|first)", "\\bpay now\\b", "\\bhurry\\b",
            "valid (?:till|until|upto|up to)", "\\bcoupons?\\b", "\\bvouchers?\\b", "[0-9]+\\s*% off", "\\bflat (?:rs\\.?|inr|₹)",
            "\\bassured\\b", "\\bclaim (?:now|your)\\b", "\\bunlock(?:ed)? (?:a |your )?(?:reward|cashback|offer)", "\\blimited time\\b",
            "\\bexclusive\\b", "\\bdeal of\\b", "\\bpromo ?code\\b", "\\bgift ?card\\b", "\\bjackpot\\b", "\\blucky\\b",
            "\\bpre-?approved\\b", "\\bshop now\\b", "\\bbook now\\b", "\\bt&c\\b",
        ).joinToString("|"),
        IC,
    )

    /** Wording that only a completed debit/credit alert uses. */
    private val EVIDENCE = Regex(
        listOf(
            "\\bdebited\\b", "\\bpaid to\\b", "\\bsent to\\b", "\\bspent\\b", "\\b(?:txn|transaction) (?:of|for)\\b",
            "\\bupi ?ref", "\\brrn\\b", "\\butr\\b", "\\bref(?:erence)? ?(?:no\\.?|number|id)?\\s*[:.]?\\s*\\d{6,}",
            "\\bhas been credited\\b", "\\bcredited to (?:your )?(?:a/?c|acct|account|bank)",
            "\\b(?:a/c|acct|account|card)\\s*(?:no\\.?\\s*)?(?:ending\\s*(?:with|in)?\\s*)?[x*]+\\s*\\d{3,6}\\b",
            "\\bwithdrawn\\b", "\\bdeducted\\b", "\\bpayment (?:of [^.]{0,30})?(?:was |is )?successful\\b",
            "\\b(?:you )?paid (?:rs\\.?|inr|₹)\\s*[0-9]", "(?:rs\\.?|inr|₹)\\s*[0-9][0-9,.]*\\s+(?:paid|sent) (?:to|successfully)\\b",
        ).joinToString("|"),
        IC,
    )

    /** "₹5 cashback credited to your account", "Cashback of Rs 25 has been added to your wallet", "received ₹5 cashback". */
    private val CASHBACK_CREDITED = Regex(
        "cash ?back (?:of )?(?:rs\\.?|inr|₹)?\\s*[0-9][0-9,.]*\\s*(?:has been |is |was )?(?:credited|added|received|deposited)" +
            "|(?:rs\\.?|inr|₹)\\s*[0-9][0-9,.]*\\s*cash ?back\\s*(?:has been |is |was )?(?:credited|added|deposited)" +
            "|(?:received|got) (?:a )?(?:rs\\.?|inr|₹)\\s*[0-9][0-9,.]*\\s*cash ?back" +
            "|cash ?back (?:has been |is |was )?(?:credited|added|deposited) (?:to|in|into) your",
        IC,
    )

    /** Future or conditional wording: "will be credited", "get … within 48 hours", "if eligible". */
    private val NOT_YET = Regex("\\bwill (?:be |get )|\\bwithin \\d+|\\bif (?:you|eligible)|\\bexpected\\b|\\bshall be\\b|\\bto be credited\\b", IC)

    fun hasPromoCue(text: String): Boolean = PROMO.containsMatchIn(text)

    fun hasCompletionEvidence(text: String): Boolean = EVIDENCE.containsMatchIn(text)

    /** Cashback that has been credited (not promised): store as income, never ask about it. */
    fun isCashbackCredit(text: String): Boolean =
        CASHBACK_CREDITED.containsMatchIn(text) && !NOT_YET.containsMatchIn(text) && !DEBIT_EVIDENCE.containsMatchIn(text)

    /** A debit alert that also mentions credited cashback is still the debit (R3-04). */
    private val DEBIT_EVIDENCE = Regex(
        // Only words that mean money left the account; "paid to"/"sent to" also appear in cashback notes (R4-04).
        "\\bdebited\\b|\\bwithdrawn\\b|\\bdeducted\\b|\\bspent (?:on|at|using|via)\\b",
        IC,
    )

    /** True when the text is an offer / promo / reward message rather than a completed transaction. */
    fun isPromo(text: String): Boolean {
        if (isCashbackCredit(text)) return false
        return hasPromoCue(text) && !hasCompletionEvidence(text)
    }
}
