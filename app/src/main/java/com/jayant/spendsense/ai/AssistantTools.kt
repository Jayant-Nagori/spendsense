package com.jayant.spendsense.ai

import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale

/** Result of running one tool: compact JSON for the model plus the transactions to show as cards. */
data class ToolOutput(val json: String, val txns: List<Transaction> = emptyList(), val isError: Boolean = false)

/**
 * The tools the model can call. Everything returns compact JSON, rows capped at [MAX_ROWS].
 * Privacy: rows carry only merchant / amount / time / category / mode / account last digits
 * (raw SMS text only when the model asks with include_raw for a specific transaction).
 */
class AssistantTools(
    private val data: AssistantData,
    private val zone: ZoneId = Ist.ZONE,
) {
    companion object {
        const val MAX_ROWS = 50
        private const val FETCH_CAP = 5000
    }

    fun definitions(): JSONArray = JSONArray()
        .put(
            tool(
                "search_transactions",
                "Search the user's transactions (UPI, bank, card). All filters optional and combined with AND. " +
                    "'amount' matches a transaction's full amount OR the user's split share within ±0.5. " +
                    "Returns total count and sums over ALL matches plus up to 50 rows.",
                JSONObject()
                    .put("from_date", prop("string", "Start date inclusive, ISO YYYY-MM-DD (IST)."))
                    .put("to_date", prop("string", "End date inclusive, ISO YYYY-MM-DD (IST)."))
                    .put("amount", prop("number", "Exact amount in INR."))
                    .put("min_amount", prop("number", "Minimum full amount in INR."))
                    .put("max_amount", prop("number", "Maximum full amount in INR."))
                    .put("text", prop("string", "Substring to match in merchant/payee, note, category or bank."))
                    .put("type", JSONObject().put("type", "string").put("enum", JSONArray(listOf("debit", "credit"))))
                    .put("category", prop("string", "Exact category name, e.g. Food."))
                    .put("sort", JSONObject().put("type", "string").put("enum", JSONArray(listOf("newest", "oldest", "largest")))
                        .put("description", "Row order; default newest."))
                    .put("limit", prop("integer", "Max rows to return (default 25, max 50)."))
                    .put("include_raw", prop("boolean", "Include a trimmed copy of the original SMS/notification text. Only when the user asks about one specific transaction.")),
                emptyList(),
            ),
        )
        .put(
            tool(
                "spending_summary",
                "Totals for a date range: debits (what the user paid vs their own share after splits), credits, " +
                    "and breakdowns by category, merchant and day (or month for long ranges).",
                JSONObject()
                    .put("from_date", prop("string", "Start date inclusive, ISO YYYY-MM-DD (IST)."))
                    .put("to_date", prop("string", "End date inclusive, ISO YYYY-MM-DD (IST).")),
                listOf("from_date", "to_date"),
            ),
        )
        .put(
            tool(
                "list_pending_splits",
                "Money other people owe the user from split transactions (total minus the user's own share), " +
                    "plus how many debits are still waiting for the user to answer the split question. " +
                    "Note: settlement is not tracked, so these are shares owed by others, not confirmed unpaid.",
                JSONObject(), emptyList(),
            ),
        )
        .put(
            tool(
                "portfolio_summary",
                "The user's investments: holdings grouped by asset type with invested vs current value.",
                JSONObject().put(
                    "asset_type",
                    prop("string", "Optional filter: MUTUAL_FUND, STOCK, FIXED_DEPOSIT, RECURRING_DEPOSIT, BOND, GOLD, PPF, EPF, NPS, CRYPTO, REAL_ESTATE, SAVINGS, INSURANCE, LOAN_GIVEN, OTHER."),
                ),
                emptyList(),
            ),
        )

    /** Provider-neutral tool list; each client converts it to its own wire format. */
    fun specs(): List<ToolSpec> = definitions().let { defs ->
        (0 until defs.length()).map {
            val d = defs.getJSONObject(it)
            ToolSpec(d.getString("name"), d.getString("description"), d.getJSONObject("input_schema"))
        }
    }

    fun execute(name: String, input: JSONObject): ToolOutput = try {
        when (name) {
            "search_transactions" -> search(input)
            "spending_summary" -> summary(input)
            "list_pending_splits" -> pendingSplits()
            "portfolio_summary" -> portfolio(input)
            else -> error("Unknown tool $name")
        }
    } catch (e: BadInput) {
        error(e.message ?: "bad input")
    } catch (e: Exception) {
        error("Tool failed")
    }

    // ---- search ---------------------------------------------------------------------------------

    private fun search(input: JSONObject): ToolOutput {
        val (from, to) = dateWindow(input)
        val type = when (input.optString("type").lowercase(Locale.ROOT)) {
            "debit" -> TxnType.DEBIT
            "credit" -> TxnType.CREDIT
            else -> null
        }
        val limit = input.optInt("limit", 25).coerceIn(1, MAX_ROWS)
        val all = data.search(
            fromMillis = from, toMillis = to,
            amount = input.optDoubleOrNull("amount"),
            minAmount = input.optDoubleOrNull("min_amount"),
            maxAmount = input.optDoubleOrNull("max_amount"),
            text = input.optString("text").takeIf { it.isNotBlank() },
            type = type,
            category = input.optString("category").takeIf { it.isNotBlank() },
            limit = FETCH_CAP,
        )
        val sorted = when (input.optString("sort")) {
            "oldest" -> all.sortedBy { it.timestamp }
            "largest" -> all.sortedByDescending { it.amount }
            else -> all.sortedByDescending { it.timestamp }
        }
        val shown = sorted.take(limit)
        val includeRaw = input.optBoolean("include_raw", false)
        val out = JSONObject()
            .put("count", all.size)
            .put("total_paid", round2(all.sumOf { it.amount }))
            .put("total_my_share", round2(all.sumOf { it.effectiveAmount }))
            .put("shown", shown.size)
            .put("rows", JSONArray(shown.map { row(it, includeRaw) }))
        if (all.size > shown.size) out.put("note", "Only first ${shown.size} of ${all.size} rows shown; totals cover all.")
        return ToolOutput(out.toString(), shown)
    }

    // ---- summary --------------------------------------------------------------------------------

    private fun summary(input: JSONObject): ToolOutput {
        val (from, to) = dateWindow(input)
        val all = data.search(fromMillis = from, toMillis = to, limit = FETCH_CAP)
        val debits = all.filter { it.type == TxnType.DEBIT }
        val credits = all.filter { it.type == TxnType.CREDIT && !it.isRefundCredit }

        fun group(list: List<Transaction>, key: (Transaction) -> String, top: Int): JSONArray =
            JSONArray(
                list.groupBy(key).map { (k, v) ->
                    Triple(k, v.sumOf { it.effectiveAmount }, v)
                }.sortedByDescending { it.second }.take(top).map { (k, mine, v) ->
                    JSONObject().put("name", k).put("count", v.size)
                        .put("my_share", round2(mine)).put("paid", round2(v.sumOf { it.amount }))
                },
            )

        val spanDays = if (from != null && to != null) (to - from) / 86_400_000L else 0L
        val byTime = if (spanDays > 62) {
            group(debits, { Ist.row(it.timestamp, zone).take(7) }, 24).let { sortByName(it) }
        } else {
            group(debits, { Ist.row(it.timestamp, zone).take(10) }, 62).let { sortByName(it) }
        }
        val out = JSONObject()
            .put("transactions", all.size)
            .put(
                "debits",
                JSONObject().put("count", debits.size)
                    .put("paid", round2(debits.sumOf { it.amount }))
                    .put("my_share", round2(debits.sumOf { it.effectiveAmount })),
            )
            .put("credits", JSONObject().put("count", credits.size).put("total", round2(credits.sumOf { it.amount })))
            .put("by_category", group(debits, { it.category }, 12))
            .put("by_merchant", group(debits, { it.counterparty ?: "Unknown" }, 10))
            .put(if (spanDays > 62) "by_month" else "by_day", byTime)
        return ToolOutput(out.toString())
    }

    private fun sortByName(arr: JSONArray): JSONArray {
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.sortedBy { it.getString("name") }
        return JSONArray(list)
    }

    // ---- splits ---------------------------------------------------------------------------------

    private fun pendingSplits(): ToolOutput {
        val splits = data.search(type = TxnType.DEBIT, limit = FETCH_CAP)
            .filter { it.isSplit && it.myShare != null }
            .sortedByDescending { it.timestamp }
        val owed = splits.sumOf { it.amount - (it.myShare ?: it.amount) }
        val waiting = data.pendingReview()
        val rows = splits.take(MAX_ROWS).map {
            val others = it.amount - (it.myShare ?: it.amount)
            JSONObject()
                .put("id", it.id)
                .put("t", Ist.row(it.timestamp, zone))
                .put("to", it.counterparty ?: "Unknown")
                .put("total", round2(it.amount))
                .put("my_share", round2(it.myShare ?: it.amount))
                .put("others_owe", round2(others))
        }
        val out = JSONObject()
            .put("split_count", splits.size)
            .put("total_others_owe", round2(owed))
            .put("rows", JSONArray(rows))
            .put("awaiting_split_answer", JSONObject().put("count", waiting.size).put("total", round2(waiting.sumOf { it.amount })))
            .put("note", "Settlement is not tracked; others_owe is each split's total minus the user's share.")
        return ToolOutput(out.toString(), splits.take(8))
    }

    // ---- portfolio ------------------------------------------------------------------------------

    private fun portfolio(input: JSONObject): ToolOutput {
        val filter = input.optString("asset_type").trim()
        var list = data.investments()
        if (filter.isNotEmpty()) {
            val f = filter.uppercase(Locale.ROOT).replace(' ', '_')
            val match = AssetType.values().firstOrNull { it.name == f || it.label.equals(filter, true) }
                ?: throw BadInput("Unknown asset_type '$filter'")
            list = list.filter { it.assetType == match }
        }
        val byType = list.groupBy { it.assetType }.map { (t, v) ->
            JSONObject().put("type", t.name).put("label", t.label).put("count", v.size)
                .put("invested", round2(v.sumOf { it.investedAmount }))
                .put("current", round2(v.sumOf { it.valueNow }))
        }.sortedByDescending { it.getDouble("invested") }
        val holdings = list.sortedByDescending { it.investedAmount }.take(MAX_ROWS).map {
            val o = JSONObject().put("name", it.name).put("type", it.assetType.name)
                .put("invested", round2(it.investedAmount)).put("current", round2(it.valueNow))
            it.platform?.let { p -> o.put("platform", p) }
            it.units?.let { u -> o.put("units", u) }
            if (it.isSip) o.put("sip", it.sipAmount ?: 0.0)
            if (it.currentValue == null) o.put("current_is_estimate", true)
            o
        }
        val out = JSONObject()
            .put("holdings_count", list.size)
            .put("total_invested", round2(list.sumOf { it.investedAmount }))
            .put("total_current", round2(list.sumOf { it.valueNow }))
            .put("gain", round2(list.sumOf { it.gain }))
            .put("by_type", JSONArray(byType))
            .put("holdings", JSONArray(holdings))
        return ToolOutput(out.toString())
    }

    // ---- helpers --------------------------------------------------------------------------------

    private class BadInput(msg: String) : Exception(msg)

    private fun error(msg: String) = ToolOutput(JSONObject().put("error", msg).toString(), isError = true)

    private fun dateWindow(input: JSONObject): Pair<Long?, Long?> {
        fun parse(key: String): LocalDate? {
            val s = input.optString(key).trim()
            if (s.isEmpty()) return null
            return try { LocalDate.parse(s.take(10)) } catch (e: DateTimeParseException) { throw BadInput("$key must be ISO YYYY-MM-DD") }
        }
        val from = parse("from_date")
        val to = parse("to_date")
        if (from != null && to != null && to.isBefore(from)) throw BadInput("to_date is before from_date")
        return Pair(
            from?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
            to?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(),
        )
    }

    private fun row(t: Transaction, includeRaw: Boolean): JSONObject {
        val o = JSONObject()
            .put("id", t.id)
            .put("t", Ist.row(t.timestamp, zone))
            .put("type", t.type.name)
            .put("amt", round2(t.amount))
            .put("to", t.counterparty ?: "Unknown")
            .put("cat", t.category)
        if (t.isSplit && t.myShare != null) o.put("split", true).put("my_share", round2(t.myShare))
        t.paymentMode?.let { o.put("mode", it.name) }
        if (t.refundStatus != com.jayant.spendsense.data.RefundStatus.NONE) {
            o.put("refund", t.refundStatus.name).put("refund_amt", round2(t.refundAmount ?: 0.0)).put("my_cost", round2(t.effectiveAmount))
        }
        t.refundOfId?.let { o.put("refund_of_id", it) }
        t.account?.takeIf { it.isNotBlank() }?.let { o.put("acct", it.takeLast(6)) }
        t.bank?.let { o.put("bank", it) }
        t.note?.takeIf { it.isNotBlank() }?.let { o.put("note", it.take(80)) }
        if (includeRaw) o.put("raw", t.rawText.take(240))
        return o
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun prop(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)

    private fun tool(name: String, desc: String, props: JSONObject, required: List<String>) =
        JSONObject().put("name", name).put("description", desc).put(
            "input_schema",
            JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required)),
        )
}
