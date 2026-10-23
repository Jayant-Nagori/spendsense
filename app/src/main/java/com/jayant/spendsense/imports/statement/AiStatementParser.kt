package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.ai.LlmClient
import com.jayant.spendsense.ai.completeText
import com.jayant.spendsense.data.TxnType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional fallback: asks the configured AI to read statement text the heuristics could not. Only used when the user
 * explicitly opts in. The statement text goes to the user's chosen AI provider; nothing is logged or written to disk here.
 */
class AiStatementParser(
    private val llm: LlmClient,
    private val chunkChars: Int = 9000,
) {
    fun parse(lines: List<String>, bank: String? = null, account: String? = null, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): StatementParse {
        val chunks = chunk(lines)
        val out = mutableListOf<StatementEntry>()
        chunks.forEachIndexed { i, text ->
            onProgress(i, chunks.size)
            out += parseReply(replyFor(text), bank ?: StatementImporter.detectBank(text), account)
        }
        onProgress(chunks.size, chunks.size)
        return StatementParse(out, bank, account)
    }

    internal fun chunk(lines: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.isNotEmpty() && sb.length + l.length + 1 > chunkChars) { chunks += sb.toString(); sb.setLength(0) }
            sb.append(l).append('\n')
        }
        if (sb.isNotEmpty()) chunks += sb.toString()
        return chunks
    }

    private fun replyFor(text: String): String = llm.completeText(SYSTEM, "Statement text:\n\n$text", 8000)

    companion object {
        private const val SYSTEM =
            "You extract transactions from the text of an Indian bank or credit-card statement. " +
                "Reply with ONLY a JSON array, no prose and no code fences. Each element: " +
                "{\"date\":\"YYYY-MM-DD\",\"description\":\"narration as printed\",\"amount\":123.45,\"type\":\"debit\" or \"credit\",\"ref\":\"reference/UPI RRN or empty\"}. " +
                "amount is a positive number without commas. Skip opening/closing balances, totals, headers and footers. " +
                "If there are no transactions, reply []."

        /** Parses the model reply (tolerates code fences / surrounding prose) into entries. Bad rows are dropped. */
        fun parseReply(reply: String, bank: String? = null, account: String? = null): List<StatementEntry> {
            val start = reply.indexOf('[')
            val end = reply.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val arr = try { JSONArray(reply.substring(start, end + 1)) } catch (_: Exception) { return emptyList() }
            val out = mutableListOf<StatementEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val date = StatementDates.parse(o.optString("date")) ?: continue
                val amount = o.optDouble("amount", Double.NaN).takeIf { !it.isNaN() && it > 0 } ?: continue
                val desc = o.optString("description").trim()
                if (desc.isEmpty()) continue
                val type = if (o.optString("type").lowercase().startsWith("c")) TxnType.CREDIT else TxnType.DEBIT
                out += StatementImporter.entryFrom(date, desc, amount, type, o.optString("ref").ifBlank { null }, desc, bank, account, false)
            }
            return out
        }
    }
}
