package com.jayant.spendsense.ai

import io.ktor.client.HttpClient
import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject

/**
 * OpenAI Chat Completions client (function calling). Used for OpenAI, Gemini's OpenAI-compatible endpoint,
 * OpenRouter, Groq and custom servers. Key goes only in the Authorization header; nothing is logged.
 */
class OpenAiCompatClient(
    private val config: AiConfig,
    private val http: HttpClient = LlmHttp.defaultClient,
    /** Stable per conversation; sent as `x-opencode-session` to the OpenCode providers. */
    private val sessionId: String = LlmHttp.newSessionId(),
    private val userAgent: String = LlmHttp.userAgent,
) : LlmClient {

    override suspend fun complete(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int): LlmTurn {
        val url = endpoint()
        // OpenAI's newer models only accept max_completion_tokens; everyone else still wants max_tokens.
        val primary = if (config.provider == AiProvider.OPENAI) "max_completion_tokens" else "max_tokens"
        val secondary = if (primary == "max_tokens") "max_completion_tokens" else "max_tokens"
        val resp = try {
            post(url, body(system, messages, tools, allowTools, maxTokens, primary))
        } catch (e: AssistantException) {
            if (e.kind == ErrorKind.BAD_REQUEST) post(url, body(system, messages, tools, allowTools, maxTokens, secondary)) else throw e
        }
        return parse(resp)
    }

    private fun endpoint(): String {
        var base = config.baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions")
        if (!isHttpsUrl(base)) throw AssistantException(ErrorKind.OTHER, "Base URL must use https")
        return "$base/chat/completions"
    }

    private suspend fun post(url: String, body: JSONObject): JSONObject {
        val key = config.apiKey.trim()
        if (key.isEmpty()) throw AssistantException(ErrorKind.NO_KEY, "No API key")
        // content-type: application/json comes from the JSON body.
        val headers = mutableListOf("Authorization" to "Bearer $key", "User-Agent" to userAgent)
        if (config.provider == AiProvider.OPENROUTER) {
            headers += "X-Title" to "SpendSense"
        }
        if (config.provider.needsSessionHeader) headers += "x-opencode-session" to sessionId
        return LlmHttp.postJson(http, url, headers, body)
    }

    internal fun body(
        system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int, tokenParam: String,
    ): JSONObject {
        val wire = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        messages.forEach { appendWire(wire, it) }
        val o = JSONObject().put("model", config.model).put(tokenParam, maxTokens).put("messages", wire)
        if (tools.isNotEmpty()) {
            o.put("tools", JSONArray(tools.map { t ->
                val fn = JSONObject().put("name", t.name).put("description", t.description)
                // No-argument tools omit "parameters": valid for OpenAI and avoids Gemini rejecting empty schemas.
                val props = t.parameters.optJSONObject("properties")
                if (props != null && props.length() > 0) {
                    val schema = JSONObject(t.parameters.toString())
                    if ((schema.optJSONArray("required")?.length() ?: 0) == 0) schema.remove("required")
                    fn.put("parameters", schema)
                }
                JSONObject().put("type", "function").put("function", fn)
            }))
            if (!allowTools) o.put("tool_choice", "none")
        }
        return o
    }

    private fun appendWire(out: JSONArray, m: LlmMessage) {
        when (m) {
            is LlmMessage.User -> out.put(JSONObject().put("role", "user").put("content", m.text))
            is LlmMessage.Assistant -> {
                val o = JSONObject().put("role", "assistant")
                    .put("content", if (m.text.isBlank() && m.toolCalls.isNotEmpty()) JSONObject.NULL else m.text)
                if (m.toolCalls.isNotEmpty()) {
                    o.put("tool_calls", JSONArray(m.toolCalls.map {
                        JSONObject().put("id", it.id).put("type", "function").put(
                            "function", JSONObject().put("name", it.name).put("arguments", it.args.toString()),
                        )
                    }))
                }
                out.put(o)
            }
            is LlmMessage.ToolResults -> m.results.forEach {
                out.put(JSONObject().put("role", "tool").put("tool_call_id", it.callId).put("content", it.content))
            }
        }
    }

    internal fun parse(resp: JSONObject): LlmTurn {
        val choice = resp.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AssistantException(ErrorKind.OTHER, "Unreadable response")
        val msg = choice.optJSONObject("message") ?: JSONObject()
        val text = when (val c = msg.opt("content")) {
            is String -> c
            is JSONArray -> buildString {
                for (i in 0 until c.length()) {
                    val part = c.optJSONObject(i) ?: continue
                    if (part.optString("type", "text") == "text") append(part.optString("text", ""))
                }
            }
            else -> ""
        }.trim()
        val calls = mutableListOf<ToolCall>()
        val raw = msg.optJSONArray("tool_calls")
        if (raw != null) for (i in 0 until raw.length()) {
            val c = raw.optJSONObject(i) ?: continue
            val fn = c.optJSONObject("function") ?: continue
            val name = fn.optString("name", "")
            if (name.isEmpty()) continue
            val args = runCatching { JSONObject(fn.optString("arguments", "{}").ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val id = c.optString("id", "").ifBlank { "call_$i" }
            calls += ToolCall(id, name, args)
        }
        val stop = when {
            calls.isNotEmpty() -> StopReason.TOOL_USE // some providers (Gemini) report "stop" alongside tool calls
            choice.optString("finish_reason") == "length" -> StopReason.MAX_TOKENS
            choice.optString("finish_reason") == "content_filter" -> StopReason.REFUSAL
            else -> StopReason.END
        }
        return LlmTurn(text, calls, stop)
    }
}
