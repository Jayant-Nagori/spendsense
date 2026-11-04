package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.ai.LlmClient
import com.jayant.spendsense.ai.LlmMessage
import com.jayant.spendsense.ai.LlmTurn
import com.jayant.spendsense.ai.StopReason
import com.jayant.spendsense.ai.ToolSpec
import com.jayant.spendsense.data.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class AiStatementParserTest {

    private class FakeApi(private val reply: String) : LlmClient {
        var calls = 0
        var sawTools = false
        override suspend fun complete(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int): LlmTurn {
            calls++
            if (tools.isNotEmpty() || allowTools) sawTools = true
            return LlmTurn(reply, emptyList(), StopReason.END)
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

    @Test fun chunks_split_on_line_boundaries_and_each_chunk_calls_api() = kotlinx.coroutines.test.runTest {
        val api = FakeApi("[]")
        val parser = AiStatementParser(api, chunkChars = 50)
        val lines = List(10) { "line number $it with some text" }
        val chunks = parser.chunk(lines)
        assertTrue(chunks.size > 1)
        assertEquals(lines.joinToString("\n", postfix = "\n"), chunks.joinToString(""))
        parser.parse(lines)
        assertEquals(chunks.size, api.calls)
        assertTrue(!api.sawTools, "statement parsing must be a plain completion")
    }
}
