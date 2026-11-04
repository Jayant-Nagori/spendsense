package com.jayant.spendsense.ai

import com.jayant.spendsense.BuildInfo
import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONException
import com.jayant.spendsense.json.JSONObject
import com.jayant.spendsense.randomUuid
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.Volatile

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
 * One chat round trip with whichever provider is configured. Suspends on network I/O.
 * Implementations throw [AssistantException] and never log keys or message bodies.
 */
interface LlmClient {
    suspend fun complete(
        system: String,
        messages: List<LlmMessage>,
        tools: List<ToolSpec> = emptyList(),
        allowTools: Boolean = true,
        maxTokens: Int = 4096,
    ): LlmTurn
}

/** Plain text completion, no tools. */
suspend fun LlmClient.completeText(system: String, user: String, maxTokens: Int): String =
    complete(system, listOf(LlmMessage.User(user)), emptyList(), false, maxTokens).text

/** Picks the Anthropic or OpenAI-compatible client from the current settings on every call. */
class ConfiguredLlmClient(
    private val config: () -> AiConfig?,
    private val http: HttpClient = LlmHttp.defaultClient,
    private val userAgent: String = LlmHttp.userAgent,
) : LlmClient {
    @Volatile private var sessionId: String = LlmHttp.newSessionId()

    /** Starts a new conversation (new `x-opencode-session`); call when the chat is cleared. */
    fun newSession() { sessionId = LlmHttp.newSessionId() }

    override suspend fun complete(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int): LlmTurn {
        val c = config() ?: throw AssistantException(ErrorKind.NO_KEY, "No API key")
        val client: LlmClient = if (c.provider == AiProvider.ANTHROPIC) AnthropicClient(c, http) else OpenAiCompatClient(c, http, sessionId, userAgent)
        return client.complete(system, messages, tools, allowTools, maxTokens)
    }
}

// ---- Shared HTTP plumbing -------------------------------------------------------------------------

internal object LlmHttp {
    /** Identifies this app to providers that ask for a client-specific agent (OpenCode), instead of the engine default. */
    val userAgent: String = "SpendSense/${BuildInfo.VERSION_NAME}"

    fun newSessionId(): String = randomUuid()

    val defaultClient: HttpClient by lazy {
        HttpClient(llmHttpEngine()) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 90_000
                requestTimeoutMillis = 120_000
            }
        }
    }

    /**
     * POSTs [body] as JSON with [headers]; returns the body of a 2xx response as JSON, otherwise throws a mapped
     * [AssistantException]. Any transport failure is NETWORK.
     */
    suspend fun postJson(client: HttpClient, url: String, headers: List<Pair<String, String>>, body: JSONObject): JSONObject {
        val code: Int
        val text: String
        try {
            val resp = client.post(url) {
                headers.forEach { (k, v) -> header(k, v) }
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            code = resp.status.value
            text = resp.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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

/** OkHttp on Android, NSURLSession (Darwin) on iOS. */
internal expect fun llmHttpEngine(): io.ktor.client.engine.HttpClientEngineFactory<*>
