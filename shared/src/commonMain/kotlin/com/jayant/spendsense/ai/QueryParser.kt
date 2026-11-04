package com.jayant.spendsense.ai

import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.time.ZonedDateTime
import com.jayant.spendsense.time.atStartOfDayMillis
import com.jayant.spendsense.time.fullName
import com.jayant.spendsense.time.isAfter
import com.jayant.spendsense.time.minusDays
import com.jayant.spendsense.time.minusMonths
import com.jayant.spendsense.time.minusWeeks
import com.jayant.spendsense.time.minusYears
import com.jayant.spendsense.time.monthValue
import com.jayant.spendsense.time.plusDays
import com.jayant.spendsense.time.plusMonths
import com.jayant.spendsense.time.safeLocalDate
import com.jayant.spendsense.time.shortName
import com.jayant.spendsense.time.withDayOfMonth
import com.jayant.spendsense.time.withDayOfWeek
import com.jayant.spendsense.time.withDayOfYear
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** Half-open date range [from, toExclusive) in local calendar days. */
data class DateRange(val from: LocalDate, val toExclusive: LocalDate, val label: String) {
    fun fromMillis(zone: TimeZone): Long = from.atStartOfDayMillis(zone)
    fun toMillis(zone: TimeZone): Long = toExclusive.atStartOfDayMillis(zone)
}

enum class QueryIntent { FIND, TOTAL, BIGGEST, SPLITS, PAID_FOR, INVESTMENTS, LATEST, COUNT, BREAKDOWN, COMPARE, AVERAGE, ACCOUNT, ACCOUNT_BREAKDOWN, UNKNOWN }

/** "my Axis card", "hdfc debit card", "card ending 1234": which card / account a question is about. */
data class AccountFilter(val issuer: String?, val instrument: com.jayant.spendsense.data.Instrument?, val anyCard: Boolean, val last4: String?)

data class ParsedQuery(
    val amount: Double?,
    val range: DateRange?,
    val categoryKey: String?,
    val textTerm: String?,
    val type: TxnType?,
    val intent: QueryIntent,
    val account: AccountFilter? = null,
)

/**
 * Rule-based understanding of a spending question, used by the offline fallback.
 * Pure Kotlin so it can be unit tested with a fixed "now".
 */
object QueryParser {

    /** category key -> words that indicate it (also matched against merchant names). */
    val CATEGORY_WORDS: Map<String, List<String>> = linkedMapOf(
        "food" to listOf("food", "dining", "restaurant", "restaurants", "swiggy", "zomato", "cafe", "coffee", "lunch", "dinner", "breakfast", "snacks", "eating"),
        "groceries" to listOf("groceries", "grocery", "blinkit", "zepto", "bigbasket", "instamart", "dmart", "vegetables"),
        "transport" to listOf("transport", "cab", "cabs", "uber", "ola", "rapido", "fuel", "petrol", "diesel", "metro", "commute"),
        "travel" to listOf("travel", "flight", "flights", "hotel", "irctc", "train", "trip", "makemytrip"),
        "shopping" to listOf("shopping", "amazon", "flipkart", "myntra", "clothes"),
        "bills" to listOf("bills", "bill", "electricity", "recharge", "rent", "wifi", "broadband", "utilities"),
        "entertainment" to listOf("entertainment", "movie", "movies", "netflix", "spotify", "bookmyshow", "games"),
        "health" to listOf("health", "medicine", "medicines", "pharmacy", "doctor", "hospital", "medical", "gym"),
        "cash" to listOf("cash", "atm", "withdrawal", "withdrawals", "withdrew"),
        "rent" to listOf("rent", "landlord", "maintenance"),
        "education" to listOf("education", "school", "college", "fees", "course", "tuition"),
        "investment" to listOf("investment", "investing", "zerodha", "groww", "upstox"),
        "transfers" to listOf("transfers", "transfer", "friends", "friend"),
    )

    /**
     * Typos, Hinglish and shorthand mapped to the words the rules understand, so "kal kitna kharcha hua" and
     * "where did i spnt 210 aaj" work like their English versions.
     */
    private val NORMALIZE: List<Pair<Regex, String>> = listOf(
        Regex("""\b(spnt|spemt|spenr|spedn|spended|spents?|spending)\b""") to "spent",
        Regex("""\b(kharcha|kharch|kharche|kharchi|kharcho|udaya|udaye|lagaya|lagaye|diya|diye|bheja|bheje)\b""") to "spent",
        Regex("""\b(kaha|kahan|kidhar|kaha pe|kahan pe)\b""") to "where",
        Regex("""\b(kitna|kitne|kitni)\b""") to "how much",
        Regex("""\b(kitni baar|kitne baar|kitni bar|kitne bar)\b""") to "how many times",
        Regex("""\baaj\b""") to "today",
        Regex("""\bparso\b""") to "day before yesterday",
        Regex("""\bkal\b""") to "yesterday",
        Regex("""\b(is|iss) (mahine|mahina|month)\b""") to "this month",
        Regex("""\b(pichle|pichhle|last) (mahine|mahina)\b""") to "last month",
        Regex("""\b(is|iss) (hafte|hafta|week)\b""") to "this week",
        Regex("""\b(pichle|pichhle) (hafte|hafta)\b""") to "last week",
        Regex("""\b(rupaye|rupay|rupees|rupee|rupya|rupiya|rs|inr)\b\.?""") to "rs",
        Regex("""\b(tday|tdy|2day)\b""") to "today",
        Regex("""\b(yday|ystrday|yesterdy|yestarday)\b""") to "yesterday",
        Regex("""\b(mnth)\b""") to "month",
        Regex("""\b(wk)\b""") to "week",
    )

    fun normalize(question: String): String {
        var s = question.lowercase().replace(Regex("""\s+"""), " ").trim()
        for ((re, to) in NORMALIZE) s = re.replace(s, to)
        return s
    }

    /** Words left after dates/amounts that could name a payee or note ("chai", "rahul"), for a text search fallback. */
    fun freeWords(question: String, now: ZonedDateTime): List<String> {
        val sb = StringBuilder(normalize(question))
        extractDate(sb, now.toLocalDate())
        return Regex("""[a-z][a-z0-9@._&-]{2,}""").findAll(sb.toString()).map { it.value.trim('.', '-', '_') }
            .filter { it.length >= 3 && it !in STOP && it !in QUESTION_WORDS }.distinct().toList()
    }

    private val QUESTION_WORDS = setOf(
        "where", "when", "who", "whom", "why", "much", "many", "times", "pay", "paid", "payment", "payments", "money",
        "transaction", "transactions", "txn", "txns", "expense", "expenses", "show", "list", "find", "tell", "give",
        "rs", "spent", "have", "has", "had", "for", "with", "from", "to", "at", "about", "please", "can", "could",
        "last", "latest", "recent", "most", "top", "biggest", "how", "often", "average", "daily", "per", "compare",
        "compared", "versus", "than", "more", "less", "break", "breakdown", "go", "went", "hua", "hue", "hai", "tha",
        "maine", "mera", "mere", "ko", "ka", "ki", "ke", "se", "pe", "par", "mein", "me", "did", "does",
    )

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private const val MONTH_RE =
        "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private const val WEEKDAY_RE = "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)"

    private val isoDate = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val numericDate = Regex("""\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""")
    private val dayMonth = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s*(?:of\s+)?$MONTH_RE\b(?:,?\s*(\d{4}))?""")
    private val monthDay = Regex("""\b$MONTH_RE\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s*(\d{4}))?""")
    private val dayBeforeYesterday = Regex("""\bday before yesterday\b""")
    private val yesterday = Regex("""\byesterday\b""")
    private val today = Regex("""\b(today|tonight)\b""")
    private val lastN = Regex("""\b(?:last|past|previous)\s+(\d{1,3})\s+(day|week|month)s?\b""")
    private val weekday = Regex("""\b(?:(last|this|on)\s+)?$WEEKDAY_RE\b""")
    private val periodRe = Regex("""\b(last|this|current|previous)\s+(week|month|year)\b""")
    private val yearOnly = Regex("""\b(?:in|during|of|for)\s+(20\d\d)\b""")
    private val monthOnly = Regex("""\b(?:(in|during|of|for|last|this)\s+)?$MONTH_RE\b(?:\s+(20\d\d))?""")

    private const val NUM = """(\d[\d,]*(?:\.\d+)?)"""
    private const val SUFFIX = """(?:\s?(k|thousand|lakhs?|lacs?|crores?|cr))?"""
    private val currencyBefore = Regex("""(?:₹|\brs\b\.?|\binr\b|\brupees?\b)\s*$NUM$SUFFIX""")
    private val currencyAfter = Regex("""(?<![\w.,])$NUM$SUFFIX\s*(?:₹|/-|\brs\b|\binr\b|\brupees?\b)""")
    private val bareNumber = Regex("""(?<![\w.,])$NUM$SUFFIX(?![\w])""")
    private val NOT_AMOUNT_PREFIX = setOf("top", "last", "first", "past", "next", "previous", "biggest", "largest")

    private val topicRe = Regex("""\b(?:on|at|to|from|for)\s+([a-z0-9@._&-]+)(?:\s+([a-z0-9@._&-]+))?""")
    private val STOP = setOf(
        "the", "a", "an", "my", "me", "it", "this", "that", "which", "what", "how", "all", "date", "day", "time",
        "each", "every", "any", "some", "i", "we", "you", "and", "in", "on", "of", "so", "far", "total", "month",
        "week", "year", "today", "yesterday", "was", "were", "is", "are", "did", "do", "spent", "spend",
    )

    /**
     * "paid for others", "who owes me", "kisko paise diye" (normalised: "kisko paise spent"), "kiske liye".
     * Checked before every other intent so "owes" doesn't fall into plain splits.
     */
    private val PAID_FOR_RE = Regex(
        """\b(for (someone|somebody|someone else|others?|other people|friends?|family|him|her|them|people)|on behalf of|behalf|(who|whom|kon|kaun) (all )?(owes?|owed) (me|us)|who owes|kisko|kisk[ei] (liye|lie|ke liye)|paid for others)\b""",
    )

    fun parse(question: String, now: ZonedDateTime): ParsedQuery {
        val lower = normalize(question)
        val sb = StringBuilder(lower)
        val range = extractDate(sb, now.toLocalDate())
        val rest = sb.toString()

        val amount = extractAmount(rest)
        val words = Regex("""[a-z0-9@._&-]+""").findAll(rest).map { it.value.trim('.', '-', '_') }.toList()
        val category = CATEGORY_WORDS.entries.firstOrNull { (key, syns) -> words.any { it == key || it in syns } }?.key
        val text = if (category == null) extractTopic(rest) else null

        val type = when {
            // "credit card" names a card, not money in.
            Regex("""\b(received|credited|credit|income|salary|refund|refunds|earned|got paid)\b""").containsMatchIn(CREDIT_CARD_RE.replace(lower, " ")) -> TxnType.CREDIT
            else -> null
        }

        val account = extractAccount(lower)
        val intent = when {
            ACCOUNT_WISE_RE.containsMatchIn(lower) -> QueryIntent.ACCOUNT_BREAKDOWN
            account != null && amount == null && type == null &&
                Regex("""\b(how much|total|sum|spent|spend|spending|expenses?|cost|used|charged|bill)\b""").containsMatchIn(lower) -> QueryIntent.ACCOUNT
            PAID_FOR_RE.containsMatchIn(lower) -> QueryIntent.PAID_FOR
            Regex("""\b(compare|compared|comparison|vs|versus)\b|\b(more|less) than last (week|month)\b""").containsMatchIn(lower) -> QueryIntent.COMPARE
            Regex("""\b(how many times|how often|number of times|count of)\b""").containsMatchIn(lower) -> QueryIntent.COUNT
            Regex("""\b(average|avg|per day|daily spend|a day)\b""").containsMatchIn(lower) -> QueryIntent.AVERAGE
            Regex("""\b(breakdown|break down|categories|category wise|categorywise|where (did|does|do) (all )?my money go|where my money (goes|went)|top (merchants?|places?|shops?|payees?|categories)|(who|whom) (did i|do i)? ?(pay|paid) (the )?most|where (do|did) i spent (the )?most|spent most)\b""")
                .containsMatchIn(lower) -> QueryIntent.BREAKDOWN
            Regex("""\b(last|latest|recent|most recent|previous)\s+(payment|transaction|txn|spend|expense|purchase|debit|credit)s?\b""")
                .containsMatchIn(lower) -> QueryIntent.LATEST
            Regex("""\b(owe|owes|owed|split|splits|splitwise)\b""").containsMatchIn(lower) -> QueryIntent.SPLITS
            Regex("""\b(invest|invested|investment|investments|portfolio|mutual funds?|sips?|stocks?|holdings?|fds?|fixed deposits?|gold|nps|ppf|epf)\b""")
                .containsMatchIn(lower) -> QueryIntent.INVESTMENTS
            Regex("""\b(biggest|largest|highest|costliest|most expensive|maximum|max|top)\b""").containsMatchIn(lower) -> QueryIntent.BIGGEST
            amount == null && Regex("""\b(how much|total|sum|spent|spend|spending|expenses?|cost)\b""").containsMatchIn(lower) -> QueryIntent.TOTAL
            amount != null || range != null || category != null || text != null -> QueryIntent.FIND
            else -> QueryIntent.UNKNOWN
        }
        return ParsedQuery(amount, range, category, text, type, intent, account)
    }

    private val CREDIT_CARD_RE = Regex("""\bcredit ?cards?\b""")
    private val ACCOUNT_WISE_RE = Regex("""\b(card|bank|account)[ -]?wise\b|\b(by|per|each|every|which) (card|bank|account)s?\b|\b(cards?|banks?|accounts?) (breakdown|split)\b""")
    private val ISSUER_WORDS: List<Pair<Regex, String>> = listOf(
        "axis" to "Axis Bank", "hdfc" to "HDFC Bank", "icici" to "ICICI Bank", "sbi" to "SBI", "state bank" to "SBI",
        "kotak" to "Kotak", "idfc" to "IDFC FIRST", "au bank" to "AU Bank", "au small" to "AU Bank", "yes bank" to "Yes Bank",
        "indusind" to "IndusInd", "onecard" to "OneCard", "one card" to "OneCard", "federal" to "Federal Bank",
        "amex" to "Amex", "american express" to "Amex", "bob" to "Bank of Baroda", "bank of baroda" to "Bank of Baroda",
        "baroda" to "Bank of Baroda", "pnb" to "PNB", "punjab national" to "PNB", "canara" to "Canara Bank",
        "union bank" to "Union Bank", "rbl" to "RBL Bank", "hsbc" to "HSBC", "citi" to "Citi", "slice" to "Slice",
    ).map { (w, n) -> Regex("\\b${Regex.escape(w)}\\b") to n }

    /** The card / account a question names, or null. */
    fun extractAccount(lower: String): AccountFilter? {
        val issuer = ISSUER_WORDS.firstOrNull { (re, _) -> re.containsMatchIn(lower) }?.second
        val instrument = when {
            Regex("""\bcredit ?cards?\b|\bcc\b""").containsMatchIn(lower) -> com.jayant.spendsense.data.Instrument.CREDIT_CARD
            Regex("""\bdebit ?cards?\b""").containsMatchIn(lower) -> com.jayant.spendsense.data.Instrument.DEBIT_CARD
            Regex("""\bupi\b""").containsMatchIn(lower) -> com.jayant.spendsense.data.Instrument.UPI
            else -> null
        }
        val anyCard = instrument == null && Regex("""\bcards?\b""").containsMatchIn(lower)
        val last4 = Regex("""\b(?:ending|ending in|ending with|xx|\*\*)\s*(\d{4})\b""").find(lower)?.groupValues?.get(1)
        if (issuer == null && instrument == null && !anyCard && last4 == null) return null
        // A bare "upi" isn't a card/account question ("how much on upi" still is, below, with spend words).
        return AccountFilter(issuer, instrument, anyCard, last4)
    }

    // ---- dates ---------------------------------------------------------------------------------

    private fun StringBuilder.blank(r: IntRange) {
        for (i in r) this[i] = ' '
    }

    private fun day(date: LocalDate, label: String? = null) =
        DateRange(date, date.plusDays(1), label ?: pretty(date))

    private fun pretty(d: LocalDate) =
        "${d.day} ${d.month.shortName()} ${d.year}"

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        safeLocalDate(y, m, d)

    /** Day+month without year: this year, or last year if that would be in the future. */
    private fun inferYear(m: Int, d: Int, today: LocalDate): LocalDate? {
        val thisYear = safeDate(today.year, m, d) ?: return null
        return if (thisYear.isAfter(today)) safeDate(today.year - 1, m, d) else thisYear
    }

    private fun fullYear(y: Int) = if (y < 100) 2000 + y else y

    private fun monthOf(token: String) = MONTHS.getValue(token.take(3))

    private fun extractDate(sb: StringBuilder, today: LocalDate): DateRange? {
        isoDate.find(sb.toString())?.let { m ->
            val d = safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            if (d != null) { sb.blank(m.range); return day(d) }
        }
        numericDate.find(sb.toString())?.let { m ->
            val dd = m.groupValues[1].toInt()
            val mm = m.groupValues[2].toInt()
            val y = m.groupValues[3]
            val d = if (y.isNotEmpty()) safeDate(fullYear(y.toInt()), mm, dd) else inferYear(mm, dd, today)
            if (d != null) { sb.blank(m.range); return day(d) }
        }
        dayMonth.find(sb.toString())?.let { m ->
            val dd = m.groupValues[1].toInt()
            val mm = monthOf(m.groupValues[2])
            val y = m.groupValues[3]
            val d = if (y.isNotEmpty()) safeDate(y.toInt(), mm, dd) else inferYear(mm, dd, today)
            if (d != null) { sb.blank(m.range); return day(d) }
        }
        monthDay.find(sb.toString())?.let { m ->
            val mm = monthOf(m.groupValues[1])
            val dd = m.groupValues[2].toInt()
            val y = m.groupValues[3]
            val d = if (y.isNotEmpty()) safeDate(y.toInt(), mm, dd) else inferYear(mm, dd, today)
            if (d != null) { sb.blank(m.range); return day(d) }
        }
        dayBeforeYesterday.find(sb.toString())?.let { m -> sb.blank(m.range); return day(today.minusDays(2), "day before yesterday") }
        yesterday.find(sb.toString())?.let { m -> sb.blank(m.range); return day(today.minusDays(1), "yesterday") }
        this.today.find(sb.toString())?.let { m -> sb.blank(m.range); return day(today, "today") }
        lastN.find(sb.toString())?.let { m ->
            val n = m.groupValues[1].toLong()
            val from = when (m.groupValues[2]) {
                "day" -> today.minusDays(n - 1)
                "week" -> today.minusWeeks(n).plusDays(1)
                else -> today.minusMonths(n).plusDays(1)
            }
            sb.blank(m.range)
            return DateRange(from, today.plusDays(1), "the last $n ${m.groupValues[2]}${if (n == 1L) "" else "s"}")
        }
        weekday.find(sb.toString())?.let { m ->
            val target = DayOfWeek.valueOf(m.groupValues[2].uppercase())
            var d = today
            if (m.groupValues[1] == "last" || d.dayOfWeek != target) {
                d = d.minusDays(1)
                while (d.dayOfWeek != target) d = d.minusDays(1)
            }
            sb.blank(m.range)
            val name = target.fullName()
            return day(d, "$name, ${pretty(d)}")
        }
        periodRe.find(sb.toString())?.let { m ->
            val last = m.groupValues[1] == "last" || m.groupValues[1] == "previous"
            sb.blank(m.range)
            return when (m.groupValues[2]) {
                "week" -> {
                    val monday = today.withDayOfWeek(DayOfWeek.MONDAY)
                    if (last) DateRange(monday.minusWeeks(1), monday, "last week")
                    else DateRange(monday, today.plusDays(1), "this week")
                }
                "month" -> {
                    val first = today.withDayOfMonth(1)
                    if (last) DateRange(first.minusMonths(1), first, "last month")
                    else DateRange(first, today.plusDays(1), "this month")
                }
                else -> {
                    val first = today.withDayOfYear(1)
                    if (last) DateRange(first.minusYears(1), first, "last year")
                    else DateRange(first, today.plusDays(1), "this year")
                }
            }
        }
        yearOnly.find(sb.toString())?.let { m ->
            val y = m.groupValues[1].toInt()
            sb.blank(m.range)
            return DateRange(LocalDate(y, 1, 1), LocalDate(y + 1, 1, 1), "$y")
        }
        monthOnly.find(sb.toString())?.let { m ->
            val qualifier = m.groupValues[1]
            val token = m.groupValues[2]
            // "may" is also a verb: only treat it as a month when clearly used as one.
            if (token != "may" || qualifier.isNotEmpty()) {
                val mm = monthOf(token)
                val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
                    ?: if (mm > today.monthValue) today.year - 1 else today.year
                val first = LocalDate(y, mm, 1)
                sb.blank(m.range)
                return DateRange(
                    first, first.plusMonths(1),
                    "${first.month.fullName()} $y",
                )
            }
        }
        return null
    }

    // ---- amount --------------------------------------------------------------------------------

    private fun multiplier(suffix: String) = when (suffix) {
        "k", "thousand" -> 1_000.0
        "lakh", "lakhs", "lac", "lacs" -> 100_000.0
        "cr", "crore", "crores" -> 10_000_000.0
        else -> 1.0
    }

    private fun toAmount(num: String, suffix: String): Double? =
        num.replace(",", "").trimEnd('.').toDoubleOrNull()?.times(multiplier(suffix))

    private fun extractAmount(text: String): Double? {
        currencyBefore.find(text)?.let { m -> toAmount(m.groupValues[1], m.groupValues[2])?.let { return it } }
        currencyAfter.find(text)?.let { m -> toAmount(m.groupValues[1], m.groupValues[2])?.let { return it } }
        for (m in bareNumber.findAll(text)) {
            val prev = text.substring(0, m.range.first).trimEnd().substringAfterLast(' ')
            if (prev in NOT_AMOUNT_PREFIX) continue
            toAmount(m.groupValues[1], m.groupValues[2])?.let { if (it > 0) return it }
        }
        return null
    }

    // ---- merchant / topic ------------------------------------------------------------------------

    private fun extractTopic(text: String): String? {
        val m = topicRe.find(text) ?: return null
        val first = m.groupValues[1].trim('.', '-', '_')
        if (first.isEmpty() || first in STOP || first.all { it.isDigit() }) return null
        val second = m.groupValues[2].trim('.', '-', '_')
        return if (second.isNotEmpty() && second !in STOP && !second.all { it.isDigit() }) "$first $second" else first
    }
}
