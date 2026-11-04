package com.jayant.spendsense.ai

import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

class LlmClientsTest {

    private fun Http(replies: MutableList<Pair<Int, String>>) = FakeHttp(replies)

    private val user = listOf<LlmMessage>(LlmMessage.User("hi"))
    private val tool = ToolSpec("t", "d", JSONObject().put("type", "object").put("properties", JSONObject().put("a", JSONObject().put("type", "string"))).put("required", JSONArray()))
    private val okChat = """{"choices":[{"finish_reason":"stop","message":{"content":"hello"}}]}"""

    private suspend fun fails(kind: ErrorKind, block: suspend () -> Unit) {
        try { block(); fail("expected $kind") } catch (e: AssistantException) { assertEquals(kind, e.kind) }
    }

    @Test fun openCodeSendsSessionHeaderAndAppUserAgent() = runTest {
        for ((provider, url) in listOf(
            AiProvider.OPENCODE_GO to "https://opencode.ai/zen/go/v1/chat/completions",
            AiProvider.OPENCODE_ZEN to "https://opencode.ai/zen/v1/chat/completions",
        )) {
            val http = Http(mutableListOf(200 to okChat, 200 to okChat))
            val client = OpenAiCompatClient(resolveAiConfig("oc-secret", provider, "", "")!!, http.client, sessionId = "sess-1", userAgent = "SpendSense/9.9")
            client.complete("s", user); client.complete("s", user)
            http.requests.forEach { (req, body) ->
                assertEquals(url, req.url.toString())
                assertEquals("Bearer oc-secret", req.header("Authorization"))
                assertEquals("sess-1", req.header("x-opencode-session"))
                assertEquals("SpendSense/9.9", req.header("User-Agent"))
                assertEquals("deepseek-v4.1-flash", body.getString("model"))
            }
        }
    }

    @Test fun otherProvidersSkipSessionHeaderButStillIdentifyTheApp() = runTest {
        val http = Http(mutableListOf(200 to okChat))
        OpenAiCompatClient(resolveAiConfig("gsk_x", null, "", "")!!, http.client, sessionId = "sess-1", userAgent = "SpendSense/9.9").complete("s", user)
        val req = http.requests.single().first
        assertNull(req.header("x-opencode-session"))
        assertEquals("SpendSense/9.9", req.header("User-Agent"))
    }

    @Test fun configuredClientKeepsOneSessionPerConversationAndRotatesOnNewSession() = runTest {
        val http = Http(mutableListOf(200 to okChat, 200 to okChat, 200 to okChat))
        val c = ConfiguredLlmClient({ resolveAiConfig("oc-x", AiProvider.OPENCODE_GO, "", "") }, http.client)
        c.complete("s", user); c.complete("s", user)
        c.newSession(); c.complete("s", user)
        val ids = http.requests.map { it.first.header("x-opencode-session")!! }
        assertEquals(ids[0], ids[1])
        assertTrue(ids[2] != ids[0])
        assertTrue(http.requests.all { it.first.header("User-Agent")!!.startsWith("SpendSense/") })
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(ids[0]))
    }

    @Test fun everyOpenAiCompatibleProviderUsesItsBaseUrlAndBearerAuth() = runTest {
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

    @Test fun openRouterGetsTitleHeaderOthersDoNot() = runTest {
        val a = Http(mutableListOf(200 to okChat))
        OpenAiCompatClient(resolveAiConfig("sk-or-x", null, "", "")!!, a.client).complete("s", user)
        assertEquals("SpendSense", a.requests[0].first.header("X-Title"))
        val b = Http(mutableListOf(200 to okChat))
        OpenAiCompatClient(resolveAiConfig("gsk_x", null, "", "")!!, b.client).complete("s", user)
        assertNull(b.requests[0].first.header("X-Title"))
    }

    @Test fun nonOpenAiProvidersUseMaxTokens() = runTest {
        val http = Http(mutableListOf(200 to okChat))
        OpenAiCompatClient(resolveAiConfig("gsk_x", null, "", "")!!, http.client).complete("s", user)
        assertTrue(http.requests[0].second.has("max_tokens"))
        assertFalse(http.requests[0].second.has("max_completion_tokens"))
    }

    @Test fun retriesOnceWithOtherTokenParamOnBadRequest() = runTest {
        val http = Http(mutableListOf(400 to """{"error":{"message":"unsupported parameter"}}""", 200 to okChat))
        val t = OpenAiCompatClient(resolveAiConfig("sk-abc", null, "", "")!!, http.client).complete("s", user)
        assertEquals("hello", t.text)
        assertTrue(http.requests[0].second.has("max_completion_tokens"))
        assertTrue(http.requests[1].second.has("max_tokens"))
    }

    @Test fun customEndpointRejectsHttpAndAcceptsPastedFullPath() = runTest {
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

    @Test fun errorsMapToTheSameKindsForBothClients() = runTest {
        val cases = listOf(401 to ErrorKind.AUTH, 403 to ErrorKind.AUTH, 429 to ErrorKind.RATE_LIMIT, 500 to ErrorKind.OVERLOADED, 503 to ErrorKind.OVERLOADED, 529 to ErrorKind.OVERLOADED)
        for ((code, kind) in cases) {
            fails(kind) { OpenAiCompatClient(resolveAiConfig("sk-abc", null, "", "")!!, Http(mutableListOf(code to "{}")).client).complete("s", user) }
            fails(kind) { AnthropicClient(resolveAiConfig("sk-ant-x", null, "", "")!!, Http(mutableListOf(code to "{}")).client).complete("s", user) }
        }
        fails(ErrorKind.NO_KEY) { OpenAiCompatClient(AiConfig(AiProvider.OPENAI, " ", "m", AiProvider.OPENAI.defaultBaseUrl)).complete("s", user) }
    }

    @Test fun networkFailureMapsToNetwork() = runTest {
        val client = failingHttpClient()
        fails(ErrorKind.NETWORK) { OpenAiCompatClient(resolveAiConfig("sk-abc", null, "", "")!!, client).complete("s", user) }
    }

    @Test fun geminiStyleArrayErrorBodyIsParsed() = runTest {
        val http = Http(mutableListOf(400 to """[{"error":{"message":"model not found"}}]""", 400 to """[{"error":{"message":"model not found"}}]"""))
        try {
            OpenAiCompatClient(resolveAiConfig("AIzaX", null, "", "")!!, http.client).complete("s", user)
            fail()
        } catch (e: AssistantException) {
            assertEquals(ErrorKind.BAD_REQUEST, e.kind)
            assertEquals("model not found", e.message)
        }
    }

    @Test fun parsesGeminiStopWithToolCallsAndMissingIds() = runTest {
        val resp = """{"choices":[{"finish_reason":"stop","message":{"content":null,"tool_calls":[{"type":"function","function":{"name":"t","arguments":"{\"a\":\"x\"}"}}]}}]}"""
        val t = OpenAiCompatClient(resolveAiConfig("AIzaX", null, "", "")!!, Http(mutableListOf(200 to resp)).client).complete("s", user, listOf(tool))
        assertEquals(StopReason.TOOL_USE, t.stop)
        assertEquals("t", t.toolCalls.single().name)
        assertEquals("x", t.toolCalls.single().args.getString("a"))
        assertTrue(t.toolCalls.single().id.isNotBlank())
    }

    @Test fun forbiddingToolsSendsToolChoiceNone() = runTest {
        val http = Http(mutableListOf(200 to okChat, 200 to """{"content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn"}"""))
        OpenAiCompatClient(resolveAiConfig("sk-abc", null, "", "")!!, http.client).complete("s", user, listOf(tool), allowTools = false)
        assertEquals("none", http.requests[0].second.getString("tool_choice"))
        AnthropicClient(resolveAiConfig("sk-ant-x", null, "", "")!!, http.client).complete("s", user, listOf(tool), allowTools = false)
        assertEquals("none", http.requests[1].second.getJSONObject("tool_choice").getString("type"))
    }

    // ---- Anthropic ----

    @Test fun anthropicSendsKeyInHeaderAndParsesToolUse() = runTest {
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

    @Test fun anthropicRetriesPlainOnBadRequestAndDropsRawBlocks() = runTest {
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

    @Test fun plainCompletionHasNoToolsOrTuning() = runTest {
        val http = Http(mutableListOf(200 to """{"content":[{"type":"text","text":"[]"}],"stop_reason":"end_turn"}"""))
        val text = AnthropicClient(resolveAiConfig("sk-ant-x", null, "", "")!!, http.client).completeText("s", "u", 8000)
        assertEquals("[]", text)
        val body = http.requests[0].second
        assertFalse(body.has("tools"))
        assertFalse(body.has("thinking"))
        assertEquals(8000, body.getInt("max_tokens"))
    }

    @Test fun configuredClientRoutesByProviderAndFailsWithoutKey() = runTest {
        val http = Http(mutableListOf(200 to okChat))
        ConfiguredLlmClient({ resolveAiConfig("gsk_x", null, "", "") }, http.client).complete("s", user)
        assertTrue(http.requests[0].first.url.toString().endsWith("/chat/completions"))
        fails(ErrorKind.NO_KEY) { ConfiguredLlmClient({ null }, http.client).complete("s", user) }
    }
}
