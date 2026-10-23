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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlmClientsTest {

    private class Http(val replies: MutableList<Pair<Int, String>>) {
        val requests = mutableListOf<Pair<Request, JSONObject>>()
        val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val buf = okio.Buffer().also { req.body!!.writeTo(it) }
            requests += req to JSONObject(buf.readUtf8())
            val (code, body) = replies.removeAt(0)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
    }

    private val user = listOf<LlmMessage>(LlmMessage.User("hi"))
    private val tool = ToolSpec("t", "d", JSONObject().put("type", "object").put("properties", JSONObject().put("a", JSONObject().put("type", "string"))).put("required", JSONArray()))
    private val okChat = """{"choices":[{"finish_reason":"stop","message":{"content":"hello"}}]}"""

    private fun fails(kind: ErrorKind, block: () -> Unit) {
        try { block(); fail("expected $kind") } catch (e: AssistantException) { assertEquals(kind, e.kind) }
    }

    @Test fun everyOpenAiCompatibleProviderUsesItsBaseUrlAndBearerAuth() {
        val expected = mapOf(
            "sk-abc" to "https://api.openai.com/v1/chat/completions",
            "AIzaX" to "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
            "sk-or-x" to "https://openrouter.ai/api/v1/chat/completions",
            "gsk_x" to "https://api.groq.com/openai/v1/chat/completions",
        )
        for ((key, url) in expected) {
            val http = Http(mutableListOf(200 to okChat))
            val turn = OpenAiCompatClient(resolveAiConfig(key, null, "", "")!!, http.client).complete("sys", user, listOf(tool))
            assertEquals("hello", turn.text)
            assertEquals(url, http.requests[0].first.url.toString())
            assertEquals("Bearer $key", http.requests[0].first.header("Authorization"))
        }
    }

    @Test fun openRouterGetsTitleHeaderOthersDoNot() {
        val a = Http(mutableListOf(200 to okChat))
        OpenAiCompatClient(resolveAiConfig("sk-or-x", null, "", "")!!, a.client).complete("s", user)
        assertEquals("SpendSense", a.requests[0].first.header("X-Title"))
        val b = Http(mutableListOf(200 to okChat))
        OpenAiCompatClient(resolveAiConfig("gsk_x", null, "", "")!!, b.client).complete("s", user)
        assertNull(b.requests[0].first.header("X-Title"))
    }

    @Test fun nonOpenAiProvidersUseMaxTokens() {
        val http = Http(mutableListOf(200 to okChat))
        OpenAiCompatClient(resolveAiConfig("gsk_x", null, "", "")!!, http.client).complete("s", user)
        assertTrue(http.requests[0].second.has("max_tokens"))
        assertFalse(http.requests[0].second.has("max_completion_tokens"))
    }

    @Test fun retriesOnceWithOtherTokenParamOnBadRequest() {
        val http = Http(mutableListOf(400 to """{"error":{"message":"unsupported parameter"}}""", 200 to okChat))
        val t = OpenAiCompatClient(resolveAiConfig("sk-abc", null, "", "")!!, http.client).complete("s", user)
        assertEquals("hello", t.text)
        assertTrue(http.requests[0].second.has("max_completion_tokens"))
        assertTrue(http.requests[1].second.has("max_tokens"))
    }

    @Test fun customEndpointRejectsHttpAndAcceptsPastedFullPath() {
        val bad = Http(mutableListOf())
        fails(ErrorKind.OTHER) {
            OpenAiCompatClient(resolveAiConfig("tok", AiProvider.CUSTOM, "m", "http://insecure.example.com/v1")!!, bad.client).complete("s", user)
        }
        assertTrue(bad.requests.isEmpty())

        val ok = Http(mutableListOf(200 to okChat))
        OpenAiCompatClient(resolveAiConfig("tok", AiProvider.CUSTOM, "my-model", "https://llm.example.com/v1/chat/completions/")!!, ok.client).complete("s", user)
        assertEquals("https://llm.example.com/v1/chat/completions", ok.requests[0].first.url.toString())
        assertEquals("my-model", ok.requests[0].second.getString("model"))
    }

    @Test fun errorsMapToTheSameKindsForBothClients() {
        val cases = listOf(401 to ErrorKind.AUTH, 403 to ErrorKind.AUTH, 429 to ErrorKind.RATE_LIMIT, 500 to ErrorKind.OVERLOADED, 503 to ErrorKind.OVERLOADED, 529 to ErrorKind.OVERLOADED)
        for ((code, kind) in cases) {
            fails(kind) { OpenAiCompatClient(resolveAiConfig("sk-abc", null, "", "")!!, Http(mutableListOf(code to "{}")).client).complete("s", user) }
            fails(kind) { AnthropicClient(resolveAiConfig("sk-ant-x", null, "", "")!!, Http(mutableListOf(code to "{}")).client).complete("s", user) }
        }
        fails(ErrorKind.NO_KEY) { OpenAiCompatClient(AiConfig(AiProvider.OPENAI, " ", "m", AiProvider.OPENAI.defaultBaseUrl)).complete("s", user) }
    }

    @Test fun networkFailureMapsToNetwork() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { throw java.io.IOException("down") }).build()
        fails(ErrorKind.NETWORK) { OpenAiCompatClient(resolveAiConfig("sk-abc", null, "", "")!!, client).complete("s", user) }
    }

    @Test fun geminiStyleArrayErrorBodyIsParsed() {
        val http = Http(mutableListOf(400 to """[{"error":{"message":"model not found"}}]""", 400 to """[{"error":{"message":"model not found"}}]"""))
        try {
            OpenAiCompatClient(resolveAiConfig("AIzaX", null, "", "")!!, http.client).complete("s", user)
            fail()
        } catch (e: AssistantException) {
            assertEquals(ErrorKind.BAD_REQUEST, e.kind)
            assertEquals("model not found", e.message)
        }
    }

    @Test fun parsesGeminiStopWithToolCallsAndMissingIds() {
        val resp = """{"choices":[{"finish_reason":"stop","message":{"content":null,"tool_calls":[{"type":"function","function":{"name":"t","arguments":"{\"a\":\"x\"}"}}]}}]}"""
        val t = OpenAiCompatClient(resolveAiConfig("AIzaX", null, "", "")!!, Http(mutableListOf(200 to resp)).client).complete("s", user, listOf(tool))
        assertEquals(StopReason.TOOL_USE, t.stop)
        assertEquals("t", t.toolCalls.single().name)
        assertEquals("x", t.toolCalls.single().args.getString("a"))
        assertTrue(t.toolCalls.single().id.isNotBlank())
    }

    @Test fun forbiddingToolsSendsToolChoiceNone() {
        val http = Http(mutableListOf(200 to okChat, 200 to """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))
        OpenAiCompatClient(resolveAiConfig("sk-abc", null, "", "")!!, http.client).complete("s", user, listOf(tool), allowTools = false)
        assertEquals("none", http.requests[0].second.getString("tool_choice"))
        AnthropicClient(resolveAiConfig("sk-ant-x", null, "", "")!!, http.client).complete("s", user, listOf(tool), allowTools = false)
        assertEquals("none", http.requests[1].second.getJSONObject("tool_choice").getString("type"))
    }

    // ---- Anthropic ----

    @Test fun anthropicSendsKeyInHeaderAndParsesToolUse() {
        val resp = """{"stop_reason":"tool_use","content":[{"type":"text","text":"Checking"},{"type":"tool_use","id":"tu1","name":"t","input":{"a":"1"}}]}"""
        val http = Http(mutableListOf(200 to resp))
        val t = AnthropicClient(resolveAiConfig("sk-ant-secret", null, "", "")!!, http.client).complete("sys", user, listOf(tool))
        val (req, body) = http.requests[0]
        assertEquals("https://api.anthropic.com/v1/messages", req.url.toString())
        assertEquals("sk-ant-secret", req.header("x-api-key"))
        assertNull(req.header("Authorization"))
        assertEquals("claude-sonnet-5-5", body.getString("model"))
        assertEquals("sys", body.getString("system"))
        assertEquals("t", body.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertTrue(body.getJSONArray("tools").getJSONObject(0).has("input_schema"))
        assertTrue(body.has("thinking"))
        assertEquals(StopReason.TOOL_USE, t.stop)
        assertEquals("Checking", t.text)
        assertEquals("tu1", t.toolCalls.single().id)
    }

    @Test fun anthropicRetriesPlainOnBadRequestAndDropsRawBlocks() {
        val raw = JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "hm").put("signature", "s"))
            .put(JSONObject().put("type", "tool_use").put("id", "tu1").put("name", "t").put("input", JSONObject()))
        val history = listOf(
            LlmMessage.User("q"),
            LlmMessage.Assistant("", listOf(ToolCall("tu1", "t", JSONObject())), raw),
            LlmMessage.ToolResults(listOf(ToolResult("tu1", "{}"))),
        )
        val http = Http(mutableListOf(400 to """{"error":{"message":"bad"}}""", 200 to """{"content":[{"type":"text","text":"fine"}],"stop_reason":"end_turn"}"""))
        val t = AnthropicClient(resolveAiConfig("sk-ant-x", null, "", "")!!, http.client).complete("s", history, listOf(tool))
        assertEquals("fine", t.text)
        val first = http.requests[0].second
        val second = http.requests[1].second
        assertTrue(first.has("thinking"))
        assertEquals("thinking", first.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        assertFalse(second.has("thinking"))
        assertEquals("tool_use", second.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("tool_result", second.getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test fun plainCompletionHasNoToolsOrTuning() {
        val http = Http(mutableListOf(200 to """{"content":[{"type":"text","text":"[]"}],"stop_reason":"end_turn"}"""))
        val text = AnthropicClient(resolveAiConfig("sk-ant-x", null, "", "")!!, http.client).completeText("s", "u", 8000)
        assertEquals("[]", text)
        val body = http.requests[0].second
        assertFalse(body.has("tools"))
        assertFalse(body.has("thinking"))
        assertEquals(8000, body.getInt("max_tokens"))
    }

    @Test fun configuredClientRoutesByProviderAndFailsWithoutKey() {
        val http = Http(mutableListOf(200 to okChat))
        ConfiguredLlmClient({ resolveAiConfig("gsk_x", null, "", "") }, http.client).complete("s", user)
        assertTrue(http.requests[0].first.url.toString().endsWith("/chat/completions"))
        fails(ErrorKind.NO_KEY) { ConfiguredLlmClient({ null }, http.client).complete("s", user) }
    }
}
