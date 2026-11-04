package com.jayant.spendsense.ai

import com.jayant.spendsense.json.JSONObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class AssistantToolsTest {
    private val tools = AssistantTools(sampleData())

    private fun run(name: String, json: String = "{}") = tools.execute(name, JSONObject(json))

    @Test fun definesFourTools() {
        val names = (0 until tools.definitions().length()).map { tools.definitions().getJSONObject(it).getString("name") }
        assertEquals(listOf("search_transactions", "spending_summary", "list_pending_splits", "portfolio_summary"), names)
    }

    @Test fun searchByAmountAndDateIsInclusiveOfEndDate() {
        val out = run("search_transactions", """{"amount":254,"from_date":"2026-10-12","to_date":"2026-10-12"}""")
        val j = JSONObject(out.json)
        assertEquals(1, j.getInt("count"))
        val row = j.getJSONArray("rows").getJSONObject(0)
        assertEquals("Swiggy", row.getString("to"))
        assertEquals("2026-10-12 20:15 Mon", row.getString("t"))
        assertEquals("UPI", row.getString("mode"))
        assertEquals("XX1234", row.getString("acct"))
        assertEquals(listOf(1L), out.txns.map { it.id })
    }

    @Test fun rawTextOnlyWhenRequested() {
        assertFalse(JSONObject(run("search_transactions", """{"amount":254}""").json).getJSONArray("rows").getJSONObject(0).has("raw"))
        assertTrue(JSONObject(run("search_transactions", """{"amount":254,"include_raw":true}""").json).getJSONArray("rows").getJSONObject(0).has("raw"))
    }

    @Test fun splitRowShowsShare() {
        val row = JSONObject(run("search_transactions", """{"text":"Zomato"}""").json).getJSONArray("rows").getJSONObject(0)
        assertTrue(row.getBoolean("split"))
        assertEquals(300.0, row.getDouble("my_share"), 0.001)
        assertEquals(1200.0, row.getDouble("amt"), 0.001)
    }

    @Test fun largestSortAndLimit() {
        val j = JSONObject(run("search_transactions", """{"type":"debit","sort":"largest","limit":2}""").json)
        assertEquals(2, j.getInt("shown"))
        assertEquals(4L, j.getJSONArray("rows").getJSONObject(0).getLong("id"))
        assertEquals(6, j.getInt("count"))
    }

    @Test fun badDateIsAnError() {
        val out = run("search_transactions", """{"from_date":"yesterday"}""")
        assertTrue(out.isError)
    }

    @Test fun unknownToolIsAnError() = assertTrue(run("nope").isError)

    @Test fun summaryTotals() {
        val j = JSONObject(run("spending_summary", """{"from_date":"2026-10-01","to_date":"2026-10-22"}""").json)
        assertEquals(6804.0, j.getJSONObject("debits").getDouble("my_share"), 0.001)
        assertEquals(7704.0, j.getJSONObject("debits").getDouble("paid"), 0.001)
        assertEquals(60000.0, j.getJSONObject("credits").getDouble("total"), 0.001)
        assertEquals("Shopping", j.getJSONArray("by_category").getJSONObject(0).getString("name"))
        assertTrue(j.has("by_day"))
    }

    @Test fun pendingSplits() {
        val j = JSONObject(run("list_pending_splits").json)
        assertEquals(900.0, j.getDouble("total_others_owe"), 0.001)
        assertEquals(1, j.getJSONObject("awaiting_split_answer").getInt("count"))
    }

    @Test fun portfolioFilter() {
        val j = JSONObject(run("portfolio_summary", """{"asset_type":"mutual fund"}""").json)
        assertEquals(150000.0, j.getDouble("total_invested"), 0.001)
        assertEquals(175000.0, j.getDouble("total_current"), 0.001)
    }

    @Test fun portfolioAll() {
        val j = JSONObject(run("portfolio_summary").json)
        assertEquals(350000.0, j.getDouble("total_invested"), 0.001)
        assertEquals(2, j.getJSONArray("by_type").length())
    }

    @Test fun pendingSplitsBreaksOutPaidForOthersByPerson() {
        val t = AssistantTools(FakeData(txns = listOf(
            txn(1, 250.0, "chai", millis(2026, 10, 20), paidForOthers = true, paidFor = "Rahul"),
            txn(2, 100.0, "auto", millis(2026, 10, 21), paidForOthers = true, paidFor = "Rahul"),
            txn(3, 900.0, "dinner", millis(2026, 10, 21), split = true, share = 300.0),
        )))
        val j = JSONObject(t.execute("list_pending_splits", JSONObject()).json)
        assertEquals(950.0, j.getDouble("total_others_owe"), 0.001)
        val p = j.getJSONObject("paid_for_others")
        assertEquals(350.0, p.getDouble("total_to_collect"), 0.001)
        assertEquals("Rahul", p.getJSONArray("by_person").getJSONObject(0).getString("name"))
        val row = JSONObject(t.execute("search_transactions", JSONObject("{\"text\":\"Rahul\"}")).json).getJSONArray("rows").getJSONObject(0)
        assertTrue(row.getBoolean("paid_for_others")); assertEquals(0.0, row.getDouble("my_share"), 0.001)
    }
}
