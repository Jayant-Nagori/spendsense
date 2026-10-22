package com.jayant.spendsense.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEngineTest {

    private class ScriptedApi(private val replies: MutableList<() -> JSONObject>) : LlmApi {
        val requests = mutableListOf<JSONObject>()
        override fun createMessage(body: JSONObject): JSONObject {
            requests += JSONObject(body.toString())
            return replies.removeAt(0)()
        }
    }

    private fun toolUse(id: String, name: String, input: String) = {
        JSONObject().put("stop_reason", "tool_use").put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", "Let me check."))
                .put(JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", JSONObject(input))),
        )
    }

    private fun text(t: String) = {
        JSONObject().put("stop_reason", "end_turn")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", t)))
    }

    private fun engine(api: LlmApi) = AssistantEngine(api, AssistantTools(sampleData()), { NOW })

    @Test fun toolLoopReturnsAnswerAndCards() {
        val api = ScriptedApi(mutableListOf(
            toolUse("t1", "search_transactions", """{"amount":254,"from_date":"2026-10-12","to_date":"2026-10-12"}"""),
            text("You paid ₹254 to Swiggy on 12 Oct, 8:15 pm via UPI (XX1234)."),
        ))
        val r = engine(api).ask("where did I spend 254 on 12 oct?")
        assertTrue(r.text.contains("Swiggy"))
        assertEquals(listOf(1L), r.txns.map { it.id })
        assertEquals(2, api.requests.size)

        val first = api.requests[0]
        assertEquals("claude-sonnet-5-5", first.getString("model"))
        assertTrue(first.getString("system").contains("Thursday, 22 October 2026"))
        // Second request carries the assistant tool_use and our tool_result
        val msgs = api.requests[1].getJSONArray("messages")
        assertEquals(3, msgs.length())
        val result = msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("t1", result.getString("tool_use_id"))
        assertTrue(JSONObject(result.getString("content")).getInt("count") == 1)
    }

    @Test fun historyIsKeptForFollowUps() {
        val api = ScriptedApi(mutableListOf(text("First answer."), text("Second answer.")))
        val e = engine(api)
        e.ask("one")
        e.ask("two")
        val msgs = api.requests[1].getJSONArray("messages")
        assertEquals(3, msgs.length()) // user, assistant, user
        assertEquals("two", msgs.getJSONObject(2).getString("content"))
    }

    @Test fun stopsAfterMaxIterationsByForbiddingTools() {
        val replies = MutableList<() -> JSONObject>(AssistantEngine.MAX_ITERATIONS - 1) { toolUse("t$it", "list_pending_splits", "{}") }
        replies += text("Done.")
        val api = ScriptedApi(replies)
        val r = engine(api).ask("loop")
        assertEquals("Done.", r.text)
        assertEquals(AssistantEngine.MAX_ITERATIONS, api.requests.size)
        assertEquals("none", api.requests.last().getJSONObject("tool_choice").getString("type"))
    }

    @Test fun failedCallDoesNotPolluteHistory() {
        val api = ScriptedApi(mutableListOf({ throw AssistantException(ErrorKind.NETWORK, "x") }, text("ok")))
        val e = engine(api)
        try { e.ask("one"); throw AssertionError("expected failure") } catch (_: AssistantException) {}
        e.ask("two")
        assertEquals(1, api.requests[1].getJSONArray("messages").length())
    }

    @Test fun retriesPlainRequestOnBadRequest() {
        val api = ScriptedApi(mutableListOf({ throw AssistantException(ErrorKind.BAD_REQUEST, "bad") }, text("fine")))
        val r = engine(api).ask("hi")
        assertEquals("fine", r.text)
        assertTrue(api.requests[0].has("thinking"))
        assertTrue(!api.requests[1].has("thinking"))
    }

    // ---- Assistant facade ----
    @Test fun noKeyUsesOfflineAnswerer() {
        val a = Assistant(engine(ScriptedApi(mutableListOf())), LocalAnswerer(sampleData()) { NOW }, hasKey = { false })
        val r = a.ask("where did I spend 254 on 12 oct")
        assertEquals(ReplyMode.OFFLINE, r.mode)
        assertEquals(listOf(1L), r.txns.map { it.id })
    }

    @Test fun networkFailureFallsBackOffline() {
        val api = ScriptedApi(mutableListOf({ throw AssistantException(ErrorKind.NETWORK, "x") }))
        val r = Assistant(engine(api), LocalAnswerer(sampleData()) { NOW }, hasKey = { true }).ask("how much did I spend today")
        assertEquals(ReplyMode.OFFLINE, r.mode)
        assertTrue(r.text.contains("₹800"))
    }

    @Test fun rateLimitIsFriendlyError() {
        val api = ScriptedApi(mutableListOf({ throw AssistantException(ErrorKind.RATE_LIMIT, "x") }))
        val r = Assistant(engine(api), LocalAnswerer(sampleData()) { NOW }, hasKey = { true }).ask("hi")
        assertEquals(ReplyMode.ERROR, r.mode)
        assertTrue(r.text.contains("rate limited"))
    }
}
