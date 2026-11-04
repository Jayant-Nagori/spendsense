package com.jayant.spendsense.ingest

/**
 * Recognises one-time-password / verification-code messages (SMS, app notifications, email alerts) so they are
 * ignored entirely: never parsed, stored, shown, or sent to the AI assistant.
 *
 * Real debit/credit alerts very often end with a safety line such as "Never share your OTP/PIN with anyone" or
 * "OTP kisi ke saath share na karein". Those warning clauses are removed before looking for OTP signals, so such
 * alerts are still captured. Pure Kotlin, no I/O; never logs its input.
 */
object OtpClassifier {

    private val IC = setOf(RegexOption.IGNORE_CASE)

    /** Words that name a one-time code. */
    private const val KEYWORD =
        "(?:otp|0tp|o\\.t\\.p\\.?|o t p|one[\\s-]?time[\\s-]?pass(?:word|code)|verification[\\s-]?code|security[\\s-]?code|" +
            "auth(?:entication|ori[sz]ation)?[\\s-]?code|log[\\s-]?in[\\s-]?code|confirmation[\\s-]?code|" +
            "access[\\s-]?code|secret[\\s-]?code|2fa[\\s-]?code|passcode|verification[\\s-]?pin)"
    private val KEYWORD_RE = Regex("\\b$KEYWORD\\b", IC)

    // A 4–8 digit code that is not part of a longer number, an amount ("1,299.00") or a masked account ("XX1234").
    private const val CODE = "(?<![\\dxX*,.])(\\d{4,8})(?![\\d]|[,.]\\d)"

    /** "OTP: 123456", "OTP is 123456", "Your OTP for login is 482913", "aapka OTP 482913 hai". */
    private val KEYWORD_THEN_CODE = Regex("\\b$KEYWORD(?:\\s*\\(\\s*otp\\s*\\))?[a-z\\s]{0,40}?(?:\\bis\\b|\\bhai\\b|:|-|=|\\s)\\s*[:\\-]?\\s*$CODE", IC)

    /** "123456 is your OTP", "G-123456 is your Google verification code", "482913 aapka SBI OTP hai". */
    private val CODE_THEN_KEYWORD = Regex("$CODE\\s*(?:is|as|=|:|-)?\\s*((?:[a-z]{1,15}\\s+){0,3}?)$KEYWORD\\b", IC)

    /** "123456 is your Amazon Pay code to pay Rs 500": a bare "code" right after "<digits> is your/the …". */
    private val CODE_IS_YOUR_CODE = Regex("$CODE\\s+(?:is|as)\\s+(?:your|the|ur|aapka)\\s+(?:[a-z]{1,15}\\s+){0,3}?(?:code|pin)\\b", IC)

    /** "OTP for transaction of INR 1,299.00 at MYNTRA on card XX1234 is 884512." */
    private val KEYWORD_LONG_IS_CODE = Regex("\\b$KEYWORD\\b[^.!?\\n]{0,120}?\\b(?:is|hai)\\s*:?\\s*$CODE", IC)

    /** Filler words that mean the keyword belongs to a safety warning, not to the code before it. */
    private val WARNING_FILLER = Regex("\\b(never|share|sharing|not|don'?t|do|with|anyone|mat|na|nahi|bataye\\w*|ask\\w*)\\b", IC)

    /** Context that makes a number after "is" a balance / limit / amount rather than a code. */
    private val AMOUNT_CONTEXT = Regex("(rs\\.?|inr|₹|bal|balance|limit|amount|amt|total|due)\\W*\\w{0,8}\\W*(is|hai)?\\s*:?\\s*$", IC)

    /** One clause of a message: split on sentence punctuation (not decimal points) and on " - " / ", ". */
    private val CLAUSE_SPLIT = Regex("(?<!\\d)[.!?;|](?!\\d)|,\\s+|\\s-\\s")

    /** A safety-warning clause: tells the reader not to share / that nobody will ask for their codes. */
    private val WARNING_CLAUSE = Regex(
        listOf(
            "\\b(never|do not|don'?t|dont|not to|pls do not|please do not|must not)\\b.{0,25}\\b(share|disclose|tell|give|reveal|forward)\\b",
            "\\b(share|disclose|reveal)\\b.{0,40}\\b(with|to)\\s+(anyone|anybody|any ?one|others|any person|bank staff)",
            "\\bnever\\b.{0,30}\\b(asks?|call|calls|request|requests)\\b",
            "\\b(will|does|do)\\s+not\\s+(ask|call)\\b",
            "\\b(kisi|kisi ke|kisi ko|kisiko)\\b.{0,30}\\b(share|na|mat|nahi|nahin)\\b",
            "\\b(share|batayein|bataye|batana|batao|bataen)\\b.{0,15}\\b(na|mat|nahi|nahin)\\b",
            "\\b(na|mat|nahi|nahin)\\b.{0,15}\\b(share|batayein|bataye|batana|batao|bataen|karein|karen)\\b",
            "\\bnot you\\b", "\\bfraud\\b", "\\bscam\\b",
        ).joinToString("|"),
        IC,
    )

    /** What an OTP message asks you to do with the code. */
    private val OTP_PURPOSE = Regex(
        listOf(
            "\\b(use|enter|your|the|is|aapka|apka|apna)\\s+$KEYWORD\\b",
            "\\b$KEYWORD\\s+(is|for|to|hai|ka|valid|sent|code)\\b",
            "\\bvalid (for|till|until|upto|up to)\\b", "\\bexpires? (in|at|after)\\b",
            "\\bto (authori[sz]e|authenticate|verify|validate|complete|log ?in|sign ?in|proceed|confirm)\\b",
        ).joinToString("|"),
        IC,
    )

    /** Words of a completed money movement ("debited", "paid Rs 500"). */
    private val COMPLETED = Regex(
        "\\b(debited|credited|spent|withdrawn|deducted|debit hue|credit hue)\\b|\\b(paid|sent|received)\\s+(rs\\.?|inr|₹)",
        IC,
    )

    /** OTP SMS are short (< 300 chars); bank alerts < 1 000. Bounds regex work (Kotlin/Native's engine is slow). */
    private const val MAX_CHARS = 1_200

    /** At most this many candidate matches per pattern are examined in one clause. */
    private const val MAX_MATCHES = 12

    /** True when [text] is a one-time code / verification message that must be dropped. */
    fun isOtp(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        // Bounded work for pathological input: OTP messages are short; the head carries the code.
        // Line breaks / no-break spaces are just layout (bank SMS often put the code on its own line).
        val t = TextSafety.bound(text, MAX_CHARS).replace('\u00A0', ' ').replace('\r', ' ').replace('\n', ' ')
        if (CODE_IS_YOUR_CODE.findAll(t).take(MAX_MATCHES).any { !amountBefore(t, it.range.first) && !WARNING_FILLER.containsMatchIn(it.value) }) return true
        if (!KEYWORD_RE.containsMatchIn(t)) return false

        val clauses = t.split(CLAUSE_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        for (clause in clauses) {
            if (hasCode(clause)) return true
        }
        // No visible code (masked, or sent as an image/link): judge what is left once safety warnings are removed.
        val rest = clauses.filterNot { WARNING_CLAUSE.containsMatchIn(it) }.joinToString(". ")
        if (!KEYWORD_RE.containsMatchIn(rest)) return false
        return OTP_PURPOSE.containsMatchIn(rest) && !COMPLETED.containsMatchIn(rest)
    }

    private fun hasCode(clause: String): Boolean {
        for (re in listOf(KEYWORD_THEN_CODE, KEYWORD_LONG_IS_CODE)) {
            re.findAll(clause).take(MAX_MATCHES).forEach { m ->
                val codeAt = m.groups[1]!!.range.first
                val between = clause.substring(m.range.first, codeAt)
                if (!WARNING_FILLER.containsMatchIn(between) && !amountBefore(clause, codeAt)) return true
            }
        }
        CODE_THEN_KEYWORD.findAll(clause).take(MAX_MATCHES).forEach { m ->
            val filler = m.groups[2]?.value.orEmpty()
            if (!WARNING_FILLER.containsMatchIn(filler) && !amountBefore(clause, m.range.first)) return true
        }
        return false
    }

    /** True when the number starting at [index] is introduced as money ("Rs 1234", "bal is 5000"). */
    private fun amountBefore(s: String, index: Int): Boolean {
        val head = s.substring((index - 24).coerceAtLeast(0), index)
        return AMOUNT_CONTEXT.containsMatchIn(head)
    }
}
