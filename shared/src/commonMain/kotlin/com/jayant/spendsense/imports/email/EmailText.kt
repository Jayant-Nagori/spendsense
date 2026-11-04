package com.jayant.spendsense.imports.email

import com.jayant.spendsense.data.ParsedTransaction
import com.jayant.spendsense.ingest.TransactionParser

/** Pure Kotlin (no android.*, no javax.mail) so everything here is unit-testable on the JVM. */

/** Known alert senders. [match] is a lowercase substring of the From address; [code] is the SMS-style sender id. */
data class AlertSender(val match: String, val code: String)

object EmailSenders {
    val KNOWN: List<AlertSender> = listOf(
        AlertSender("hdfcbank", "HDFCBK"),
        AlertSender("axisbank", "AXISBK"),
        AlertSender("icicibank", "ICICIB"),
        AlertSender("sbicard", "SBICRD"),
        AlertSender("sbi.co.in", "SBIINB"),
        AlertSender("alerts.sbi", "SBIINB"),
        AlertSender("onlinesbi", "SBIINB"),
        AlertSender("kotak", "KOTAKB"),
        AlertSender("idfcfirstbank", "IDFCFB"),
        AlertSender("yesbank", "YESBNK"),
        AlertSender("indusind", "INDUSB"),
        AlertSender("americanexpress", "AMEXIN"),
        AlertSender("amex", "AMEXIN"),
        AlertSender("onecard", "ONECRD"),
        AlertSender("federalbank", "FEDBNK"),
        AlertSender("pnb", "PNBSMS"),
        AlertSender("bankofbaroda", "BOBSMS"),
        AlertSender("bobfinancial", "BOBSMS"),
        AlertSender("canarabank", "CANBNK"),
        AlertSender("rblbank", "RBLBNK"),
        AlertSender("aubank", "AUBANK"),
        AlertSender("hsbc", "HSBCIN"),
        AlertSender("paytm", "PAYTMB"),
        AlertSender("phonepe", "EM-PHONEPE"),
        AlertSender("noreply-payments@google.com", "EM-GPAY"),
        AlertSender("googlepay-noreply@google.com", "EM-GPAY"),
        AlertSender("cred.club", "EM-CRED"),
        AlertSender("sliceit", "EM-SLICE"),
        AlertSender("jupiter.money", "EM-JUPITER"),
    )

    /** Subject words that mark a message as a probable money alert even from an unlisted sender. */
    val SUBJECT_WORDS: List<String> = listOf(
        "debited", "credited", "spent", "transaction alert", "payment receipt", "payment successful",
        "upi transaction", "txn alert", "card transaction", "payment confirmation",
    )

    private val ADDRESS = Regex("<([^>]+)>")

    /** "HDFC Bank InstaAlerts <alerts@hdfcbank.net>" -> "alerts@hdfcbank.net" (lowercase). */
    fun address(rawFrom: String): String =
        (ADDRESS.find(rawFrom)?.groupValues?.get(1) ?: rawFrom).trim().lowercase()

    /** "alerts@hdfcbank.net" -> "hdfcbank.net". Empty if there is no domain. */
    fun domain(rawFrom: String): String = address(rawFrom).substringAfter('@', "").trim('>', ' ')

    /**
     * Maps a From header to an SMS-style sender id understood by [TransactionParser.parseSms].
     * Unlisted senders get "EM-<LABEL>" which the parser treats as a bank-style sender.
     */
    fun senderCode(rawFrom: String): String {
        val addr = address(rawFrom)
        KNOWN.firstOrNull { addr.contains(it.match) }?.let { return it.code }
        val label = domain(rawFrom).split('.').dropLast(1).lastOrNull().orEmpty()
            .uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(9)
        return "EM-" + label.padEnd(4, 'X')
    }

    fun isKnown(rawFrom: String): Boolean = address(rawFrom).let { a -> KNOWN.any { a.contains(it.match) } }
}

object HtmlText {
    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "#39" to "'",
        "rupee" to "₹", "ndash" to "-", "mdash" to "-", "hellip" to "...", "bull" to "*", "middot" to "-",
        "zwnj" to "", "zwj" to "", "shy" to "", "lsquo" to "'", "rsquo" to "'", "ldquo" to "\"", "rdquo" to "\"",
        "copy" to "(c)", "reg" to "(R)", "trade" to "TM",
    )
    private val OPT = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val DROP_BLOCKS = Regex("<(script|style|head|title)\\b.*?</\\1\\s*>", OPT)
    private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val BREAKS = Regex("<\\s*(br|/p|/div|/tr|/li|/h[1-6]|/table)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val CELLS = Regex("<\\s*/?(td|th)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TAGS = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val SPACES = Regex("[ \\t\\u00A0\\u200B-\\u200D\\u2060\\uFEFF]+")
    private val BLANKS = Regex("\\s*\\n\\s*")

    fun toText(html: String): String {
        var s = com.jayant.spendsense.ingest.TextSafety.bound(html, 1_000_000)
        s = COMMENTS.replace(s, " ")
        s = DROP_BLOCKS.replace(s, " ")
        s = BREAKS.replace(s, "\n")
        s = CELLS.replace(s, " ")
        s = TAGS.replace(s, " ")
        s = decodeEntities(s)
        s = SPACES.replace(s, " ")
        s = BLANKS.replace(s, "\n")
        return s.trim()
    }

    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val g = m.groupValues[1]
        when {
            g.startsWith("#x") || g.startsWith("#X") -> g.substring(2).toIntOrNull(16)?.let(::cp)
            g.startsWith("#") -> g.substring(1).toIntOrNull()?.let(::cp)
            else -> NAMED[g] ?: NAMED[g.lowercase()]
        } ?: m.value
    }

    private fun cp(code: Int): String? =
        if (code in 1..0x10FFFF && code !in 0xD800..0xDFFF) codePointToString(code) else null

    private fun codePointToString(code: Int): String =
        if (code < 0x10000) code.toChar().toString()
        else {
            val v = code - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
}

/** Turns one alert email into a [ParsedTransaction] by reusing the SMS parser on cleaned text. */
object EmailAlertParser {
    private val IC = RegexOption.IGNORE_CASE

    // Everything after these markers is footer/boilerplate whose words ("do not share", "offer valid", "reminder")
    // would make the SMS parser reject a genuine alert.
    private val FOOTER = Regex(
        "(warm regards|with regards|regards,|sincerely|disclaimer|this is a system[- ]generated|please do not reply|do not reply to this|" +
            "never share|do not share|safe banking|stay alert|beware of|to unsubscribe|terms and conditions|t&c|if you did not|if this transaction was not)",
        IC,
    )
    private val REF_IS = Regex("\\b(ref(?:erence)?\\.?)\\s*(?:no\\.?|number|id|#)?\\s*(?:is|:|-)\\s+", IC)
    private val INFO = Regex("\\b(?:info|merchant|merchant name)\\s*[:\\-]\\s*", IC)
    // "Thank you for using your card ... for INR 899" carries no debit verb the SMS parser knows.
    private val USED = Regex("\\b(?:thank you for using|has been used for|was used for|you have used)\\b", IC)
    private val DATE_PHRASES = listOf(
        // "on Oct 22, 2026 at 10:15:00 AM" / "on 22 Oct 2026 10:15"
        Regex("\\b(?:on|dated?)\\s+(?:[A-Za-z]{3,9}\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?,?\\s+\\d{2,4}|\\d{1,2}(?:st|nd|rd|th)?[ -][A-Za-z]{3,9}[ -,]*\\d{2,4})(?:\\s*(?:at|,)?\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:am|pm|hrs|IST)?)?", IC),
        Regex("\\bat\\s+\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:am|pm|hrs|IST)?", IC),
    )
    private const val MAX_CHARS = 1800

    /** Cleaned, single-line text the parser sees (also what is stored as rawText). */
    fun clean(subject: String?, plain: String?, html: String?): String {
        val body = when {
            !plain.isNullOrBlank() -> com.jayant.spendsense.ingest.TextSafety.bound(plain, 200_000)
            !html.isNullOrBlank() -> HtmlText.toText(html)
            else -> ""
        }
        var s = listOfNotNull(subject?.takeIf { it.isNotBlank() }?.let { com.jayant.spendsense.ingest.TextSafety.bound(it, 1_000) }, body).joinToString(". ")
        s = s.replace(Regex("\\s+"), " ").trim()
        FOOTER.find(s)?.takeIf { it.range.first > 40 }?.let { s = s.substring(0, it.range.first).trim() }
        if (s.length > MAX_CHARS) s = s.substring(0, MAX_CHARS)
        return s
    }

    /** Pre-parse tweaks so the SMS regexes read email phrasing the way they read SMS phrasing. */
    internal fun forParser(text: String): String {
        var s = REF_IS.replace(text, "Ref ")
        s = INFO.replace(s, "at ")
        s = USED.replace(s) { it.value + " (spent)" }
        for (r in DATE_PHRASES) s = r.replace(s, "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    fun parse(fromHeader: String, subject: String?, plain: String?, html: String?, timestamp: Long): ParsedTransaction? {
        val text = clean(subject, plain, html)
        if (text.isBlank()) return null
        return TransactionParser.parseSms(EmailSenders.senderCode(fromHeader), forParser(text), timestamp)
    }

    /** Chooses the window start: re-reads the full range if the user widened it, else resumes shortly before last sync. */
    fun computeSince(nowMillis: Long, months: Int, lastSyncMillis: Long, coveredFromMillis: Long): Long {
        val rangeStart = nowMillis - months * 30L * DAY
        if (lastSyncMillis <= 0 || coveredFromMillis <= 0 || rangeStart < coveredFromMillis) return rangeStart
        return maxOf(rangeStart, lastSyncMillis - 2 * DAY)
    }

    private const val DAY = 24L * 60 * 60 * 1000
}

/** Friendly classification of connection failures (no message content ever leaves this function). */
enum class EmailFailure(val message: String) {
    WRONG_PASSWORD("Gmail rejected the address or App Password. Create a fresh App Password and paste it without spaces."),
    IMAP_DISABLED("IMAP looks disabled. In Gmail: Settings, Forwarding and POP/IMAP, enable IMAP."),
    NO_NETWORK("No network. Check your connection and try again."),
    OTHER("Could not read Gmail right now. Try again in a minute."),
    ;

    companion object {
        fun classify(t: Throwable): EmailFailure {
            var cur: Throwable? = t
            var depth = 0
            var sawAuth = false
            while (cur != null && depth++ < 6) {
                val name = cur::class.simpleName
                val msg = cur.message.orEmpty().lowercase()
                when {
                    "imap" in msg && ("disabled" in msg || "not enabled" in msg || "not allowed" in msg) -> return IMAP_DISABLED
                    "web login required" in msg || "application-specific password" in msg ||
                        "invalid credentials" in msg || "authenticationfailed" in msg -> return WRONG_PASSWORD
                    name == "AuthenticationFailedException" -> sawAuth = true
                    name == "UnknownHostException" || name == "ConnectException" || name == "NoRouteToHostException" ||
                        name == "SocketTimeoutException" || name == "SSLException" && "timed out" in msg -> return NO_NETWORK
                }
                cur = cur.cause
            }
            return if (sawAuth) WRONG_PASSWORD else OTHER
        }
    }
}
