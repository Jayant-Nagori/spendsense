package com.jayant.spendsense.ai

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEngineTest {

    /** Scripted neutral client: records every request, replays queued turns (or throws). */
    private class ScriptedLlm(private val replies: MutableList<() -> LlmTurn>) : LlmClient {
        class Req(val system: String, val messages: List<LlmMessage>, val tools: List<ToolSpec>, val allowTools: Boolean)
        val requests = mutableListOf<Req>()
        override fun complete(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int): LlmTurn {
            requests += Req(system, messages.toList(), tools, allowTools)
            return replies.removeAt(0)()
        }
    }

    private fun toolUse(id: String, name: String, input: String) = {
        LlmTurn("Let me check.", listOf(ToolCall(id, name, JSONObject(input))), StopReason.TOOL_USE)
    }

    private fun text(t: String) = { LlmTurn(t, emptyList(), StopReason.END) }

    private fun engine(llm: LlmClient) = AssistantEngine(llm, AssistantTools(sampleData()), { NOW })

    @Test fun toolLoopReturnsAnswerAndCards() {
        val api = ScriptedLlm(mutableListOf(
            toolUse("t1", "search_transactions", """{"amount":254,"from_date":"2026-10-12","to_date":"2026-10-12"}"""),
            text("You paid ₹254 to Swiggy on 12 Oct, 8:15 pm via UPI (XX1234)."),
        ))
        val r = engine(api).ask("where did I spend 254 on 12 oct?")
        assertTrue(r.text.contains("Swiggy"))
        assertEquals(listOf(1L), r.txns.map { it.id })
        assertEquals(2, api.requests.size)

        val first = api.requests[0]
        assertTrue(first.system.contains("Thursday, 22 October 2026"))
        assertEquals(
            setOf("search_transactions", "spending_summary", "list_pending_splits", "portfolio_summary"),
            first.tools.map { it.name }.toSet(),
        )
        // Second request carries the assistant tool call and our tool result
        val msgs = api.requests[1].messages
        assertEquals(3, msgs.size)
        val result = (msgs[2] as LlmMessage.ToolResults).results.single()
        assertEquals("t1", result.callId)
        assertTrue(JSONObject(result.content).getInt("count") == 1)
    }

    @Test fun historyIsKeptForFollowUps() {
        val api = ScriptedLlm(mutableListOf(text("First answer."), text("Second answer.")))
        val e = engine(api)
        e.ask("one")
        e.ask("two")
        val msgs = api.requests[1].messages
        assertEquals(3, msgs.size) // user, assistant, user
        assertEquals("two", (msgs[2] as LlmMessage.User).text)
    }

    @Test fun stopsAfterMaxIterationsByForbiddingTools() {
        val replies = MutableList<() -> LlmTurn>(AssistantEngine.MAX_ITERATIONS - 1) { toolUse("t$it", "list_pending_splits", "{}") }
        replies += text("Done.")
        val api = ScriptedLlm(replies)
        val r = engine(api).ask("loop")
        assertEquals("Done.", r.text)
        assertEquals(AssistantEngine.MAX_ITERATIONS, api.requests.size)
        assertFalse(api.requests.last().allowTools)
        assertTrue(api.requests.dropLast(1).all { it.allowTools })
    }

    @Test fun failedCallDoesNotPolluteHistory() {
        val api = ScriptedLlm(mutableListOf({ throw AssistantException(ErrorKind.NETWORK, "x") }, text("ok")))
        val e = engine(api)
        try { e.ask("one"); throw AssertionError("expected failure") } catch (_: AssistantException) {}
        e.ask("two")
        assertEquals(1, api.requests[1].messages.size)
    }

    // ---- full OpenAI-format path: engine + OpenAiCompatClient over a scripted HTTP layer ----

    private class FakeHttp(val replies: MutableList<String>) {
        val requests = mutableListOf<Pair<Request, JSONObject>>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val buf = okio.Buffer().also { req.body!!.writeTo(it) }
            requests += req to JSONObject(buf.readUtf8())
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(replies.removeAt(0).toResponseBody("application/json".toMediaType())).build()
        }).build()
    }

    private fun openAiToolCall(id: String, name: String, args: String) = JSONObject().put(
        "choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject()
            .put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().put(JSONObject()
                .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args)))))),
    ).toString()

    private fun openAiText(t: String) = JSONObject().put(
        "choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message", JSONObject().put("role", "assistant").put("content", t))),
    ).toString()

    @Test fun openAiFormatToolCallThenToolResultThenAnswer() {
        val http = FakeHttp(mutableListOf(
            openAiToolCall("call_1", "search_transactions", """{"amount":254,"from_date":"2026-10-12","to_date":"2026-10-12"}"""),
            openAiText("You paid ₹254 to Swiggy."),
        ))
        val cfg = resolveAiConfig("sk-test", null, "", "")!!
        val r = engine(OpenAiCompatClient(cfg, http.client)).ask("where did I spend 254 on 12 oct?")
        assertEquals("You paid ₹254 to Swiggy.", r.text)
        assertEquals(listOf(1L), r.txns.map { it.id })

        val (req1, body1) = http.requests[0]
        assertEquals("https://api.openai.com/v1/chat/completions", req1.url.toString())
        assertEquals("Bearer sk-test", req1.header("Authorization"))
        assertEquals("gpt-4.1-mini", body1.getString("model"))
        assertTrue(body1.has("max_completion_tokens"))
        val msgs1 = body1.getJSONArray("messages")
        assertEquals("system", msgs1.getJSONObject(0).getString("role"))
        assertTrue(msgs1.getJSONObject(0).getString("content").contains("Thursday, 22 October 2026"))
        val fn = body1.getJSONArray("tools").getJSONObject(0)
        assertEquals("function", fn.getString("type"))
        assertEquals("search_transactions", fn.getJSONObject("function").getString("name"))
        // no-arg tool omits parameters
        val noArg = (0 until body1.getJSONArray("tools").length()).map { body1.getJSONArray("tools").getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "list_pending_splits" }
        assertFalse(noArg.has("parameters"))

        val msgs2 = http.requests[1].second.getJSONArray("messages")
        assertEquals(4, msgs2.length()) // system, user, assistant(tool_calls), tool
        val asst = msgs2.getJSONObject(2)
        val call = asst.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals(254, JSONObject(call.getJSONObject("function").getString("arguments")).getInt("amount"))
        val tool = msgs2.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
        assertEquals(1, JSONObject(tool.getString("content")).getInt("count"))
    }

    // ---- Assistant facade ----
    @Test fun noKeyUsesOfflineAnswerer() {
        val a = Assistant(engine(ScriptedLlm(mutableListOf())), LocalAnswerer(sampleData()) { NOW }, hasKey = { false })
        val r = a.ask("where did I spend 254 on 12 oct")
        assertEquals(ReplyMode.OFFLINE, r.mode)
        assertEquals(listOf(1L), r.txns.map { it.id })
    }

    @Test fun networkFailureFallsBackOffline() {
        val api = ScriptedLlm(mutableListOf({ throw AssistantException(ErrorKind.NETWORK, "x") }))
        val r = Assistant(engine(api), LocalAnswerer(sampleData()) { NOW }, hasKey = { true }).ask("how much did I spend today")
        assertEquals(ReplyMode.OFFLINE, r.mode)
        assertTrue(r.text.contains("₹800"))
    }

    @Test fun rejectedKeyFallsBackOfflineWithProviderNeutralWording() {
        val api = ScriptedLlm(mutableListOf({ throw AssistantException(ErrorKind.AUTH, "x") }))
        val r = Assistant(engine(api), LocalAnswerer(sampleData()) { NOW }, hasKey = { true }).ask("how much did I spend today")
        assertEquals(ReplyMode.OFFLINE, r.mode)
        assertTrue(r.text.contains("AI API key was rejected"))
        assertFalse(r.text.contains("Anthropic"))
    }

    @Test fun rateLimitIsFriendlyError() {
        val api = ScriptedLlm(mutableListOf({ throw AssistantException(ErrorKind.RATE_LIMIT, "x") }))
        val r = Assistant(engine(api), LocalAnswerer(sampleData()) { NOW }, hasKey = { true }).ask("hi")
        assertEquals(ReplyMode.ERROR, r.mode)
        assertTrue(r.text.contains("rate limited"))
    }
}
