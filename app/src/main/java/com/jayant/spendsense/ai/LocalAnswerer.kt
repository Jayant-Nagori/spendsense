package com.jayant.spendsense.ai

import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class LocalAnswer(val text: String, val txns: List<Transaction> = emptyList(), val understood: Boolean = true)

/** Offline fallback: answers the core question types from local data with rule-based parsing. No android.*. */
class LocalAnswerer(
    private val data: AssistantData,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now(Ist.ZONE) },
) {
    private val zone: ZoneId get() = clock().zone

    fun answer(question: String): LocalAnswer {
        val now = clock()
        val q = QueryParser.parse(question, now)
        return when (q.intent) {
            QueryIntent.SPLITS -> splits()
            QueryIntent.INVESTMENTS -> investments(question)
            QueryIntent.TOTAL -> total(q, now.toLocalDate())
            QueryIntent.BIGGEST -> biggest(q, now.toLocalDate())
            QueryIntent.FIND -> find(q)
            QueryIntent.UNKNOWN -> LocalAnswer(HELP, understood = false)
        }
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

    private fun find(q: ParsedQuery): LocalAnswer {
        val list = fetch(q, q.range, q.type, useAmount = true)
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
        val splitNote = if (type == TxnType.DEBIT && Math.abs(mine - paid) > 0.5) " (your share; you paid ${Money.inr(paid)} in total)" else ""
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
        val share = if (t.isSplit && t.myShare != null) ", your share ${Money.inr(t.myShare)}" else ""
        return LocalAnswer(
            "Your biggest ${if (q.type == TxnType.CREDIT) "credit" else "expense"} ${periodText(range)} was ${Money.inr(t.amount)}" +
                " to ${t.counterparty ?: "Unknown"} on ${Ist.human(t.timestamp, zone)}$share.",
            top,
        )
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
                "current value ${Money.inr(current)} ($sign${Money.inr(Math.abs(gain))}).",
        )
    }

    private fun periodText(r: DateRange): String = when {
        r.label.startsWith("this ") || r.label.startsWith("last ") || r.label == "today" || r.label == "yesterday" -> r.label
        r.label.startsWith("the ") -> "in ${r.label}"
        else -> "on ${r.label}".takeIf { r.toExclusive == r.from.plusDays(1) } ?: "in ${r.label}"
    }

    companion object {
        const val HELP = "I can answer a few things offline, like \"Where did I spend ₹254 on 12 Oct?\", " +
            "\"How much did I spend this week?\", \"How much on food last month?\" or \"What's my biggest expense this month?\"."
    }
}
