package com.jayant.spendsense.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Anthropic Messages API client. Key goes only in the x-api-key header; nothing is logged. */
class AnthropicClient(
    private val config: AiConfig,
    private val http: OkHttpClient = LlmHttp.defaultClient,
) : LlmClient {

    override fun complete(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int): LlmTurn {
        val url = endpoint()
        val base = body(system, messages, tools, allowTools, maxTokens, useRaw = true)
        val tuned = if (tools.isEmpty()) base else JSONObject(base.toString())
            .put("thinking", JSONObject().put("type", "between_tools"))
            .put("output_config", JSONObject().put("effort", "medium"))
        val resp = try {
            post(url, tuned)
        } catch (e: AssistantException) {
            // If the tuning fields (or replayed thinking blocks) are rejected, retry once with the plain request.
            if (e.kind == ErrorKind.BAD_REQUEST) post(url, body(system, messages, tools, allowTools, maxTokens, useRaw = false)) else throw e
        }
        return parse(resp)
    }

    private fun endpoint(): String {
        val base = config.baseUrl.trim().trimEnd('/')
        if (!isHttpsUrl(base)) throw AssistantException(ErrorKind.OTHER, "Base URL must use https")
        return "$base/messages"
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val key = config.apiKey.trim()
        if (key.isEmpty()) throw AssistantException(ErrorKind.NO_KEY, "No API key")
        val request = Request.Builder()
            .url(url)
            .header("x-api-key", key)
            .header("anthropic-version", VERSION)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return LlmHttp.execute(http, request)
    }

    internal fun body(
        system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int, useRaw: Boolean,
    ): JSONObject {
        val o = JSONObject()
            .put("model", config.model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray(messages.map { wire(it, useRaw) }))
        if (tools.isNotEmpty()) {
            o.put("tools", JSONArray(tools.map {
                JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.parameters)
            }))
            if (!allowTools) o.put("tool_choice", JSONObject().put("type", "none"))
        }
        return o
    }

    private fun wire(m: LlmMessage, useRaw: Boolean): JSONObject = when (m) {
        is LlmMessage.User -> JSONObject().put("role", "user").put("content", m.text)
        is LlmMessage.Assistant -> {
            val raw = m.raw as? JSONArray
            val content = if (useRaw && raw != null) raw else JSONArray().apply {
                if (m.text.isNotBlank()) put(JSONObject().put("type", "text").put("text", m.text))
                m.toolCalls.forEach {
                    put(JSONObject().put("type", "tool_use").put("id", it.id).put("name", it.name).put("input", it.args))
                }
            }
            JSONObject().put("role", "assistant").put("content", content)
        }
        is LlmMessage.ToolResults -> JSONObject().put("role", "user").put(
            "content",
            JSONArray(m.results.map {
                JSONObject().put("type", "tool_result").put("tool_use_id", it.callId).put("content", it.content)
                    .apply { if (it.isError) put("is_error", true) }
            }),
        )
    }

    internal fun parse(resp: JSONObject): LlmTurn {
        val content = resp.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            when (b.optString("type")) {
                "text" -> { if (text.isNotEmpty()) text.append("\n"); text.append(b.optString("text")) }
                "tool_use" -> calls += ToolCall(b.optString("id"), b.optString("name"), b.optJSONObject("input") ?: JSONObject())
            }
        }
        val stop = when (resp.optString("stop_reason")) {
            "tool_use" -> StopReason.TOOL_USE
            "refusal" -> StopReason.REFUSAL
            "max_tokens" -> StopReason.MAX_TOKENS
            else -> StopReason.END
        }
        return LlmTurn(text.toString().trim(), calls, stop, content)
    }

    companion object {
        const val VERSION = "2023-06-01"
    }
}
