package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.ai.LlmApi
import com.jayant.spendsense.data.TxnType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiStatementParserTest {

    private class FakeApi(private val reply: String) : LlmApi {
        var calls = 0
        override fun createMessage(body: JSONObject): JSONObject {
            calls++
            return JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", reply)))
        }
    }

    private val reply = """
        ```json
        [{"date":"2024-04-01","description":"UPI/529512345678/ZEPTO/Pay","amount":250.5,"type":"debit","ref":"529512345678"},
         {"date":"2024-04-02","description":"SALARY ACME","amount":75000,"type":"credit","ref":""},
         {"date":"garbage","description":"x","amount":1,"type":"debit"}]
        ```
    """.trimIndent()

    @Test fun parses_fenced_json_and_drops_bad_rows() {
        val rows = AiStatementParser.parseReply(reply, "HDFC Bank", null)
        assertEquals(2, rows.size)
        assertEquals(TxnType.DEBIT, rows[0].txn.type)
        assertEquals("Zepto", rows[0].txn.counterparty)
        assertEquals("529512345678", rows[0].txn.upiRef)
        assertEquals(TxnType.CREDIT, rows[1].txn.type)
        assertEquals("HDFC Bank", rows[0].txn.bank)
    }

    @Test fun non_json_reply_yields_nothing() {
        assertTrue(AiStatementParser.parseReply("Sorry, I cannot help").isEmpty())
    }

    @Test fun chunks_split_on_line_boundaries_and_each_chunk_calls_api() {
        val api = FakeApi("[]")
        val parser = AiStatementParser(api, "m", chunkChars = 50)
        val lines = List(10) { "line number $it with some text" }
        val chunks = parser.chunk(lines)
        assertTrue(chunks.size > 1)
        assertEquals(lines.joinToString("\n", postfix = "\n"), chunks.joinToString(""))
        parser.parse(lines)
        assertEquals(chunks.size, api.calls)
    }
}
