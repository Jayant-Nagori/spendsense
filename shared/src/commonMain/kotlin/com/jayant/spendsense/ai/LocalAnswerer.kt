package com.jayant.spendsense.ai

import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.time.ZonedDateTime
import com.jayant.spendsense.time.dayOfMonth
import com.jayant.spendsense.time.minusDays
import com.jayant.spendsense.time.minusMonths
import com.jayant.spendsense.time.minusWeeks
import com.jayant.spendsense.time.plusDays
import com.jayant.spendsense.time.withDayOfMonth
import com.jayant.spendsense.time.withDayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import com.jayant.spendsense.time.toEpochDay
import kotlin.math.roundToLong
import kotlin.math.abs
import kotlin.math.max

data class LocalAnswer(val text: String, val txns: List<Transaction> = emptyList(), val understood: Boolean = true)

/** Offline fallback: answers the core question types from local data with rule-based parsing. No android.*. */
class LocalAnswerer(
    private val data: AssistantData,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(Ist.ZONE) },
) {
    private val zone: TimeZone get() = clock().zone

    fun answer(question: String): LocalAnswer {
        val now = clock()
        val q = withFreeText(QueryParser.parse(question, now), question, now)
        val today = now.toLocalDate()
        if (isPaidForPerson(q, question)) return paidFor(q, today)
        return when (q.intent) {
            QueryIntent.PAID_FOR -> paidFor(q, today)
            QueryIntent.SPLITS -> splits()
            QueryIntent.INVESTMENTS -> investments(question)
            QueryIntent.TOTAL -> total(q, today)
            QueryIntent.BIGGEST -> biggest(q, today)
            QueryIntent.FIND -> find(q, today)
            QueryIntent.LATEST -> latest(q)
            QueryIntent.COUNT -> count(q, today)
            QueryIntent.BREAKDOWN -> breakdown(q, today)
            QueryIntent.COMPARE -> compare(q, today)
            QueryIntent.AVERAGE -> average(q, today)
            QueryIntent.ACCOUNT -> accountTotal(q, today)
            QueryIntent.ACCOUNT_BREAKDOWN -> accountBreakdown(q, today)
            QueryIntent.UNKNOWN -> LocalAnswer(HELP, understood = false)
        }
    }

    /** Card / account identity of a row, derived from its own fields when it predates 1.3. */
    private fun ident(t: Transaction): Transaction = if (t.issuer != null || t.instrument != null || t.last4 != null) t else com.jayant.spendsense.data.Accounts.derive(t)

    private fun matchesAccount(t: Transaction, f: AccountFilter): Boolean {
        val d = ident(t)
        if (f.issuer != null && d.issuer != f.issuer) return false
        if (f.instrument != null && d.instrument != f.instrument) return false
        if (f.anyCard && d.instrument != com.jayant.spendsense.data.Instrument.CREDIT_CARD && d.instrument != com.jayant.spendsense.data.Instrument.DEBIT_CARD) return false
        if (f.last4 != null && d.last4 != f.last4) return false
        return true
    }

    /** "how much on my Axis card this month", "hdfc card se kitna kharcha". */
    private fun accountTotal(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val f = q.account ?: return LocalAnswer(HELP, understood = false)
        val range = q.range ?: DateRange(today.withDayOfMonth(1), today.plusDays(1), "this month")
        val list = fetch(q.copy(textTerm = null), range, TxnType.DEBIT, useAmount = false).filter { !it.isTransfer && matchesAccount(it, f) }
        val name = listOfNotNull(f.issuer, f.instrument?.label ?: if (f.anyCard) "card" else null).joinToString(" ").ifEmpty { "that account" } +
            (f.last4?.let { " ••$it" } ?: "")
        val period = periodText(range)
        if (list.isEmpty()) return LocalAnswer("No payments from your $name $period.")
        val paid = list.sumOf { it.amount }
        val mine = list.sumOf { it.effectiveAmount }
        val share = if (abs(mine - paid) > 0.5) " Your share of that is ${Money.inr(mine)}." else ""
        val n = list.size
        return LocalAnswer("You paid ${Money.inr(paid)} from your $name $period across $n payment${if (n == 1) "" else "s"}.$share", list.sortedByDescending { it.amount }.take(5))
    }

    /** "card wise spend", "how much from each card". */
    private fun accountBreakdown(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val range = q.range ?: DateRange(today.withDayOfMonth(1), today.plusDays(1), "this month")
        val list = fetch(q.copy(textTerm = null, categoryKey = null), range, TxnType.DEBIT, useAmount = false).map(::ident)
        val rows = com.jayant.spendsense.data.AccountRepository.split(list, emptyMap())
        if (rows.isEmpty()) return LocalAnswer("No card or account payments ${periodText(range)}.")
        val lines = rows.take(6).joinToString("\n") { "• ${it.label}: ${Money.inr(it.amount)} (${(it.share * 100).roundToLong()}%, ${it.count})" }
        return LocalAnswer("Paid from each card and account ${periodText(range)}:\n$lines")
    }

    /**
     * No category or "on/at/to X" phrase: try leftover words against payees, notes and messages, so
     * "chai this week" or "rahul" work. An otherwise unknown question with a matching word becomes a search.
     */
    private fun withFreeText(q: ParsedQuery, question: String, now: ZonedDateTime): ParsedQuery {
        if (q.categoryKey != null || q.textTerm != null) return q
        if (q.intent in setOf(QueryIntent.SPLITS, QueryIntent.INVESTMENTS, QueryIntent.PAID_FOR)) return q
        val hit = QueryParser.freeWords(question, now).firstOrNull { w -> data.search(text = w, limit = 1).isNotEmpty() } ?: return q
        return q.copy(textTerm = hit, intent = if (q.intent == QueryIntent.UNKNOWN) QueryIntent.FIND else q.intent)
    }

    private fun fetch(q: ParsedQuery, range: DateRange?, type: TxnType?, useAmount: Boolean): List<Transaction> {
        val list = data.search(
            fromMillis = range?.fromMillis(zone), toMillis = range?.toMillis(zone),
            amount = if (useAmount) q.amount else null,
            text = if (q.categoryKey == null) q.textTerm else null,
            type = type, limit = 5000,
        )
        val key = q.categoryKey ?: return list
        val words = QueryParser.CATEGORY_WORDS[key].orEmpty()
        return list.filter { t ->
            t.category.contains(key, true) || words.any { t.category.equals(it, true) } ||
                (t.counterparty != null && words.any { t.counterparty.contains(it, true) })
        }
    }

    private fun find(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val list = fetch(q, q.range, q.type, useAmount = true)
        if (list.isEmpty() && q.amount != null) return amountMiss(q, q.amount, today)
        val what = buildString {
            append(if (q.amount != null) "of ${Money.inr(q.amount)}" else "")
            q.categoryKey?.let { append(" in $it") }
            q.textTerm?.let { append(" matching \"$it\"") }
        }.trim()
        val whenText = q.range?.let { " " + periodText(it) } ?: ""
        if (list.isEmpty()) {
            val hint = if (q.range != null && q.amount != null) " Try asking without the date." else ""
            return LocalAnswer("I couldn't find any transactions ${what}${whenText}.$hint".replace("  ", " ").replace(" .", "."))
        }
        val shown = list.take(8)
        val head = if (list.size == 1) "Found 1 transaction" else "Found ${list.size} transactions"
        val tail = if (list.size > shown.size) " Showing the latest ${shown.size}." else ""
        return LocalAnswer("$head ${what}${whenText}:$tail".replace("  ", " ").replace(" :", ":"), shown)
    }

    private fun total(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val range = q.range ?: DateRange(today.withDayOfMonth(1), today.plusDays(1), "this month")
        val type = q.type ?: TxnType.DEBIT
        val list = fetch(q, range, type, useAmount = false)
        val subject = q.categoryKey?.let { " on $it" } ?: q.textTerm?.let { " on $it" } ?: ""
        val verb = if (type == TxnType.CREDIT) "received" else "spent"
        val period = periodText(range)
        if (list.isEmpty()) return LocalAnswer("No ${if (type == TxnType.CREDIT) "credits" else "spending"}$subject found $period.")
        val mine = list.sumOf { it.effectiveAmount }
        val paid = list.sumOf { it.amount }
        val splitNote = if (type == TxnType.DEBIT && kotlin.math.abs(mine - paid) > 0.5) " (your share; you paid ${Money.inr(paid)} in total)" else ""
        val amountText = if (type == TxnType.CREDIT) Money.inr(paid) else Money.inr(mine)
        val n = list.size
        return LocalAnswer(
            "You $verb $amountText$subject $period$splitNote across $n transaction${if (n == 1) "" else "s"}.",
            list.sortedByDescending { it.effectiveAmount }.take(5),
        )
    }

    private fun biggest(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val range = q.range ?: DateRange(today.withDayOfMonth(1), today.plusDays(1), "this month")
        val list = fetch(q, range, q.type ?: TxnType.DEBIT, useAmount = false)
        if (list.isEmpty()) return LocalAnswer("No transactions found ${periodText(range)}.")
        val top = list.sortedByDescending { it.amount }.take(3)
        val t = top.first()
        val share = if (t.paidForOthers) ", paid for ${t.paidForName ?: "someone else"}" else if (t.isSplit && t.myShare != null) ", your share ${Money.inr(t.myShare)}" else ""
        return LocalAnswer(
            "Your biggest ${if (q.type == TxnType.CREDIT) "credit" else "expense"} ${periodText(range)} was ${Money.inr(t.amount)}" +
                " to ${t.counterparty ?: "Unknown"} on ${Ist.human(t.timestamp, zone)}$share.",
            top,
        )
    }

    /** "how much did I pay for rahul": a "for <name>" phrase whose name matches someone a payment was paid for. */
    private fun isPaidForPerson(q: ParsedQuery, question: String): Boolean {
        val term = q.textTerm?.substringBefore(' ')?.lowercase() ?: return false
        if (q.intent !in setOf(QueryIntent.TOTAL, QueryIntent.FIND, QueryIntent.UNKNOWN)) return false
        if (!Regex("\\bfor\\s+" + Regex.escape(term)).containsMatchIn(QueryParser.normalize(question))) return false
        return data.search(text = term, type = TxnType.DEBIT, limit = 5000).any { it.paidForName?.contains(term, true) == true }
    }

    /**
     * Payments the user made entirely for someone else: total to collect, per person. A name in the question
     * ("how much for rahul") narrows it to that person; a date range narrows it in time (default: all time,
     * since money owed doesn't expire at month end).
     */
    private fun paidFor(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val generic = setOf("others", "other", "someone", "somebody", "friends", "friend", "family", "him", "her", "them", "people", "else")
        val name = q.textTerm?.takeIf { t -> t.split(' ').none { it in generic } }?.lowercase()
        val range = q.range
        val all = data.search(
            fromMillis = range?.fromMillis(zone), toMillis = range?.toMillis(zone), type = TxnType.DEBIT, limit = 5000,
        ).filter { it.paidForOthers }
        val list = if (name != null) all.filter { t -> t.paidForName?.lowercase()?.contains(name) == true } else all
        val when_ = range?.let { " " + periodText(it) } ?: ""
        if (list.isEmpty() && name == null && range == null) splits().takeIf { it.txns.isNotEmpty() }?.let { return it }
        if (list.isEmpty()) {
            val who = name?.let { " for ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: " for someone else"
            return LocalAnswer("You haven't marked any payments as paid$who$when_ yet. Choose \"For someone else\" after a payment to track money owed to you.")
        }
        val total = list.sumOf { it.amount }
        val byPerson = list.groupBy { it.paidForName ?: "Someone (no name)" }
            .map { (k, v) -> k to v.sumOf { it.amount } }.sortedByDescending { it.second }
        val n = list.size
        val head = "You paid ${Money.inr(total)} for others$when_ across $n payment${if (n == 1) "" else "s"}"
        val people = if (byPerson.size > 1 || byPerson.first().first.startsWith("Someone")) {
            "; " + byPerson.take(5).joinToString(", ") { "${it.first} owes ${Money.inr(it.second)}" }
        } else "; ${byPerson.first().first} owes you ${Money.inr(byPerson.first().second)}"
        return LocalAnswer("$head$people (settlements aren't tracked).", list.sortedByDescending { it.timestamp }.take(6))
    }

    private fun splits(): LocalAnswer {
        val splits = data.search(type = TxnType.DEBIT, limit = 5000).filter { it.isSplit && it.myShare != null }
        val waiting = data.pendingReview().size
        if (splits.isEmpty()) {
            val extra = if (waiting > 0) " $waiting payment${if (waiting == 1) " is" else "s are"} still waiting for your split answer." else ""
            return LocalAnswer("You have no split transactions yet.$extra")
        }
        val owed = splits.sumOf { it.amount - (it.myShare ?: it.amount) }
        val extra = if (waiting > 0) " $waiting more payment${if (waiting == 1) " is" else "s are"} awaiting your split answer." else ""
        return LocalAnswer(
            "Others owe you ${Money.inr(owed)} across ${splits.size} split transaction${if (splits.size == 1) "" else "s"} " +
                "(settlements aren't tracked).$extra",
            splits.sortedByDescending { it.timestamp }.take(6),
        )
    }

    private fun investments(question: String): LocalAnswer {
        val lower = question.lowercase()
        val filter: AssetType? = when {
            Regex("""mutual funds?|\bmf\b|\bsips?\b""").containsMatchIn(lower) -> AssetType.MUTUAL_FUND
            Regex("""\bstocks?\b|\bshares?\b""").containsMatchIn(lower) -> AssetType.STOCK
            Regex("""\bfds?\b|fixed deposits?""").containsMatchIn(lower) -> AssetType.FIXED_DEPOSIT
            Regex("""\bgold\b""").containsMatchIn(lower) -> AssetType.GOLD
            Regex("""\bbonds?\b""").containsMatchIn(lower) -> AssetType.BOND
            Regex("""\bppf\b""").containsMatchIn(lower) -> AssetType.PPF
            Regex("""\bepf\b""").containsMatchIn(lower) -> AssetType.EPF
            Regex("""\bnps\b""").containsMatchIn(lower) -> AssetType.NPS
            else -> null
        }
        val all = data.investments()
        val list = if (filter != null) all.filter { it.assetType == filter } else all
        val label = filter?.label ?: "all investments"
        if (list.isEmpty()) return LocalAnswer("You haven't added any ${if (filter != null) label.lowercase() + " " else ""}holdings yet.")
        val invested = list.sumOf { it.investedAmount }
        val current = list.sumOf { it.valueNow }
        val gain = current - invested
        val sign = if (gain >= 0) "+" else "-"
        return LocalAnswer(
            "You've invested ${Money.inr(invested)} in $label across ${list.size} holding${if (list.size == 1) "" else "s"}; " +
                "current value ${Money.inr(current)} ($sign${Money.inr(kotlin.math.abs(gain))}).",
        )
    }

    // ---- "where did I spend ₹210" when there is no exact match -----------------------------------------------

    private fun amountMiss(q: ParsedQuery, amount: Double, today: LocalDate): LocalAnswer {
        val label = Money.inr(amount)
        val range = q.range
        val whenText = range?.let { " " + periodText(it) } ?: ""

        // Same amount a few days either side (dates get mixed up), then the most recent one ever.
        if (range != null) {
            val wide = DateRange(range.from.minusDays(3), range.toExclusive.plusDays(3), "")
            val near = fetch(q, wide, q.type, useAmount = true)
            if (near.isNotEmpty()) {
                val t = near.first()
                return LocalAnswer("No $label payment$whenText, but there's one close by: ${line(t)}.", near.take(5))
            }
        }

        // Look at what was paid in the period (or the last 7 days) for near amounts and combinations.
        val poolRange = range ?: DateRange(today.minusDays(6), today.plusDays(1), "the last 7 days")
        val pool = fetch(q.copy(amount = null), poolRange, q.type ?: TxnType.DEBIT, useAmount = false)
        val poolText = periodText(poolRange)

        val rounding = pool.filter { abs(it.amount - amount) <= 2.0 }.sortedBy { abs(it.amount - amount) }
        if (rounding.isNotEmpty()) {
            return LocalAnswer("Nothing for exactly $label$whenText. Did you mean ${line(rounding.first())}?", rounding.take(5))
        }
        combo(pool, amount)?.let { parts ->
            val sum = parts.joinToString(" + ") { "${Money.inr(it.amount)} at ${it.counterparty ?: "Unknown"}" }
            return LocalAnswer("No single $label payment $poolText, but these add up to $label: $sum.", parts)
        }
        val tol = max(10.0, amount * 0.05)
        val close = pool.filter { abs(it.amount - amount) <= tol }.sortedBy { abs(it.amount - amount) }
        if (close.isNotEmpty()) {
            return LocalAnswer("Nothing for exactly $label $poolText. Closest: ${close.take(3).joinToString("; ") { line(it) }}.", close.take(5))
        }
        if (range != null) {
            val ever = fetch(q, null, q.type, useAmount = true)
            if (ever.isNotEmpty()) {
                return LocalAnswer("No $label payment$whenText. The last $label payment I have is ${line(ever.first())}.", ever.take(5))
            }
        }

        val fix = captureFix()
        if (pool.isEmpty()) {
            return LocalAnswer("I have no payments recorded $poolText at all, so that $label payment wasn't captured.$fix")
        }
        return LocalAnswer(
            "No $label payment $poolText. Here's what I did record ${poolText}, in case it's one of these.$fix",
            pool.take(6),
        )
    }

    private fun captureFix(): String {
        val off = data.captureIssues()
        val why = if (off.isEmpty()) "" else " ${off.joinToString(" and ").replaceFirstChar { it.uppercase() }} ${if (off.size == 1) "is" else "are"} off in Settings → Capture."
        return "$why You can add a missed payment with + ADD on Home."
    }

    /** Two or three payments that add up to [amount] (within ₹1), smallest set first. */
    private fun combo(pool: List<Transaction>, amount: Double): List<Transaction>? {
        val items = pool.filter { it.amount < amount }.take(40)
        for (i in items.indices) for (j in i + 1 until items.size) {
            if (abs(items[i].amount + items[j].amount - amount) <= 1.0) return listOf(items[i], items[j])
        }
        for (i in items.indices) for (j in i + 1 until items.size) for (k in j + 1 until items.size) {
            if (abs(items[i].amount + items[j].amount + items[k].amount - amount) <= 1.0) return listOf(items[i], items[j], items[k])
        }
        return null
    }

    private fun line(t: Transaction): String =
        "${Money.inr(t.amount)} to ${t.counterparty ?: "Unknown"} on ${Ist.human(t.timestamp, zone)}" +
            (t.note?.takeIf { it.isNotBlank() }?.let { " (\"$it\")" } ?: "")

    // ---- more question types ------------------------------------------------------------------------------

    private fun latest(q: ParsedQuery): LocalAnswer {
        val type = q.type ?: TxnType.DEBIT
        val list = fetch(q, q.range, type, useAmount = false)
        if (list.isEmpty()) return LocalAnswer("I don't have any ${if (type == TxnType.CREDIT) "money received" else "payments"} recorded yet.${captureFix()}")
        return LocalAnswer("Your last ${if (type == TxnType.CREDIT) "credit" else "payment"} was ${line(list.first())}.", list.take(5))
    }

    private fun count(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val range = q.range ?: DateRange(today.withDayOfMonth(1), today.plusDays(1), "this month")
        val list = fetch(q, range, q.type ?: TxnType.DEBIT, useAmount = q.amount != null)
        val subject = q.categoryKey ?: q.textTerm?.let { "\"$it\"" } ?: "payments"
        if (list.isEmpty()) return LocalAnswer("No $subject ${periodText(range)}.")
        val n = list.size
        return LocalAnswer(
            "$n time${if (n == 1) "" else "s"} ${periodText(range)} for $subject, ${Money.inr(list.sumOf { it.effectiveAmount })} in total.",
            list.take(8),
        )
    }

    private fun breakdown(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val range = q.range ?: DateRange(today.withDayOfMonth(1), today.plusDays(1), "this month")
        val list = fetch(q, range, TxnType.DEBIT, useAmount = false).filter { !it.isRefundCredit }
        if (list.isEmpty()) return LocalAnswer("No spending recorded ${periodText(range)}.")
        val total = list.sumOf { it.effectiveAmount }
        val byPlace = Regex("""\b(merchant|place|shop|payee|who|whom)""").containsMatchIn(q.textTerm.orEmpty()) || false
        val groups = list.groupBy { if (byPlace) (it.counterparty ?: "Unknown") else it.category }
            .mapValues { (_, v) -> v.sumOf { it.effectiveAmount } }
            .entries.sortedByDescending { it.value }.take(5)
        val parts = groups.joinToString("\n") { (k, v) -> "- $k: ${Money.inr(v)} (${(v * 100 / total).roundToLong()}%)" }
        val topPayees = list.groupBy { it.counterparty ?: "Unknown" }.mapValues { (_, v) -> v.sumOf { it.effectiveAmount } }
            .entries.maxByOrNull { it.value }
        val payeeLine = topPayees?.let { "\nMost money went to ${it.key} (${Money.inr(it.value)})." } ?: ""
        return LocalAnswer(
            "You spent ${Money.inr(total)} ${periodText(range)}:\n$parts$payeeLine",
            list.sortedByDescending { it.effectiveAmount }.take(5),
        )
    }

    private fun compare(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val weekly = Regex("week").containsMatchIn(q.range?.label.orEmpty()) || q.range?.let { it.toExclusive.toEpochDay() - it.from.toEpochDay() <= 7 } == true
        val (cur, prev) = if (weekly) {
            val monday = today.withDayOfWeek(kotlinx.datetime.DayOfWeek.MONDAY)
            val days = today.toEpochDay() - monday.toEpochDay()
            DateRange(monday, today.plusDays(1), "this week") to DateRange(monday.minusWeeks(1), monday.minusWeeks(1).plusDays(days + 1), "the same days last week")
        } else {
            val first = today.withDayOfMonth(1)
            val prevFirst = first.minusMonths(1)
            val prevEnd = minOf(prevFirst.plusDays((today.dayOfMonth).toLong()), first)
            DateRange(first, today.plusDays(1), "this month") to DateRange(prevFirst, prevEnd, "the same days last month")
        }
        val a = fetch(q, cur, TxnType.DEBIT, useAmount = false).sumOf { it.effectiveAmount }
        val b = fetch(q, prev, TxnType.DEBIT, useAmount = false).sumOf { it.effectiveAmount }
        val subject = q.categoryKey?.let { " on $it" } ?: q.textTerm?.let { " on $it" } ?: ""
        val diff = a - b
        val verdict = when {
            b < 1 && a < 1 -> "No spending$subject in either period."
            b < 1 -> "Nothing$subject in ${prev.label} to compare with."
            abs(diff) < 1 -> "Exactly the same as ${prev.label}."
            diff > 0 -> "${Money.inr(diff)} more (${(diff * 100 / b).roundToLong()}% up) than ${prev.label} (${Money.inr(b)})."
            else -> "${Money.inr(-diff)} less (${(-diff * 100 / b).roundToLong()}% down) than ${prev.label} (${Money.inr(b)})."
        }
        return LocalAnswer("You've spent ${Money.inr(a)}$subject ${cur.label}. $verdict")
    }

    private fun average(q: ParsedQuery, today: LocalDate): LocalAnswer {
        val range = q.range ?: DateRange(today.withDayOfMonth(1), today.plusDays(1), "this month")
        val list = fetch(q, range, TxnType.DEBIT, useAmount = false)
        val end = minOf(range.toExclusive, today.plusDays(1))
        val days = max(1L, end.toEpochDay() - range.from.toEpochDay())
        val total = list.sumOf { it.effectiveAmount }
        return LocalAnswer("You spend about ${Money.inr(total / days)} a day ${periodText(range)} (${Money.inr(total)} over $days day${if (days == 1L) "" else "s"}).")
    }

    private fun periodText(r: DateRange): String = when {
        r.label.startsWith("this ") || r.label.startsWith("last ") || r.label == "today" || r.label == "yesterday" -> r.label
        r.label.startsWith("the ") -> "in ${r.label}"
        else -> "on ${r.label}".takeIf { r.toExclusive == r.from.plusDays(1) } ?: "in ${r.label}"
    }

    companion object {
        const val HELP = "Ask me things like \"Where did I spend ₹210 today?\", \"How much on food this month?\", " +
            "\"Where did my money go last month?\", \"How many times did I order Swiggy?\", \"Last payment\", " +
            "\"How much did I pay for others?\", \"This month vs last month\" or \"Kal kitna kharcha hua?\". It all works offline."
    }
}
