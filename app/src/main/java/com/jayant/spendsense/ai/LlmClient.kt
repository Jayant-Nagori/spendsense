package com.jayant.spendsense.ai

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class ErrorKind { NO_KEY, AUTH, RATE_LIMIT, OVERLOADED, NETWORK, BAD_REQUEST, OTHER }

class AssistantException(val kind: ErrorKind, message: String) : RuntimeException(message)

// ---- Provider-neutral conversation model ----------------------------------------------------------

/** A model-callable tool, in neutral form. [parameters] is a JSON Schema object (may have no properties). */
data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

data class ToolCall(val id: String, val name: String, val args: JSONObject)

data class ToolResult(val callId: String, val content: String, val isError: Boolean = false)

sealed interface LlmMessage {
    data class User(val text: String) : LlmMessage

    /**
     * [raw] is an opaque provider payload the SAME client may replay verbatim (Anthropic thinking blocks need to
     * round-trip). Other clients ignore it.
     */
    data class Assistant(val text: String, val toolCalls: List<ToolCall> = emptyList(), val raw: Any? = null) : LlmMessage

    data class ToolResults(val results: List<ToolResult>) : LlmMessage
}

enum class StopReason { END, TOOL_USE, MAX_TOKENS, REFUSAL }

data class LlmTurn(val text: String, val toolCalls: List<ToolCall>, val stop: StopReason, val raw: Any? = null)

/**
 * One chat round trip with whichever provider is configured. Blocking: call from Dispatchers.IO.
 * Implementations throw [AssistantException] and never log keys or message bodies.
 */
interface LlmClient {
    fun complete(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec> = emptyList(),
        allowTools: Boolean = true,
        maxTokens: Int = 4096,
    ): LlmTurn
}

/** Plain text completion, no tools. */
fun LlmClient.completeText(system: String, user: String, maxTokens: Int): String =
    complete(system, listOf(LlmMessage.User(user)), emptyList(), false, maxTokens).text

/** Picks the Anthropic or OpenAI-compatible client from the current settings on every call. */
class ConfiguredLlmClient(
    private val config: () -> AiConfig?,
    private val http: OkHttpClient = LlmHttp.defaultClient,
) : LlmClient {
    override fun complete(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int): LlmTurn {
        val c = config() ?: throw AssistantException(ErrorKind.NO_KEY, "No API key")
        val client: LlmClient = if (c.provider == AiProvider.ANTHROPIC) AnthropicClient(c, http) else OpenAiCompatClient(c, http)
        return client.complete(system, messages, tools, allowTools, maxTokens)
    }
}

// ---- Shared HTTP plumbing -------------------------------------------------------------------------

internal object LlmHttp {
    val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** Executes [request]; returns the body of a 2xx response as JSON, otherwise throws a mapped [AssistantException]. */
    fun execute(client: OkHttpClient, request: Request): JSONObject {
        val code: Int
        val text: String
        try {
            client.newCall(request).execute().use { resp ->
                code = resp.code
                text = resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw AssistantException(ErrorKind.NETWORK, "Network error")
        }
        if (code !in 200..299) throw mapError(code, text)
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw AssistantException(ErrorKind.OTHER, "Unreadable response")
        }
    }

    fun mapError(code: Int, text: String): AssistantException {
        val detail = errorDetail(text).take(200)
        return when {
            code == 401 || code == 403 -> AssistantException(ErrorKind.AUTH, "Invalid API key")
            code == 429 -> AssistantException(ErrorKind.RATE_LIMIT, "Rate limited")
            code == 529 || code >= 500 -> AssistantException(ErrorKind.OVERLOADED, "Service busy")
            code == 400 || code == 404 || code == 422 -> AssistantException(ErrorKind.BAD_REQUEST, detail.ifBlank { "Bad request" })
            else -> AssistantException(ErrorKind.OTHER, "HTTP $code")
        }
    }

    /** Pulls "error.message" out of Anthropic / OpenAI / Gemini style error bodies; tolerates arrays and plain strings. */
    private fun errorDetail(text: String): String = runCatching {
        val obj = if (text.trimStart().startsWith("[")) JSONArray(text).optJSONObject(0) else JSONObject(text)
        when (val e = obj?.opt("error")) {
            is JSONObject -> e.optString("message", "")
            is String -> e
            else -> ""
        }
    }.getOrDefault("")
}
