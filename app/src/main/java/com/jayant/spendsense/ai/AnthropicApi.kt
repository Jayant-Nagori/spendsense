package com.jayant.spendsense.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class ErrorKind { NO_KEY, AUTH, RATE_LIMIT, OVERLOADED, NETWORK, BAD_REQUEST, OTHER }

class AssistantException(val kind: ErrorKind, message: String) : RuntimeException(message)

/** One Messages API round trip. Blocking: call from Dispatchers.IO. */
interface LlmApi {
    fun createMessage(body: JSONObject): JSONObject
}

/** Direct OkHttp call to https://api.anthropic.com/v1/messages. Never logs the key or any message body. */
class AnthropicApi(
    private val keyProvider: () -> String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : LlmApi {

    override fun createMessage(body: JSONObject): JSONObject {
        val key = keyProvider().trim()
        if (key.isEmpty()) throw AssistantException(ErrorKind.NO_KEY, "No API key")
        val request = Request.Builder()
            .url(URL)
            .header("x-api-key", key)
            .header("anthropic-version", VERSION)
            .header("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
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

    private fun mapError(code: Int, text: String): AssistantException {
        val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
        return when {
            code == 401 || code == 403 -> AssistantException(ErrorKind.AUTH, "Invalid API key")
            code == 429 -> AssistantException(ErrorKind.RATE_LIMIT, "Rate limited")
            code == 529 || code >= 500 -> AssistantException(ErrorKind.OVERLOADED, "Service busy")
            code == 400 -> AssistantException(ErrorKind.BAD_REQUEST, detail.ifBlank { "Bad request" })
            else -> AssistantException(ErrorKind.OTHER, "HTTP $code")
        }
    }

    companion object {
        const val URL = "https://api.anthropic.com/v1/messages"
        const val VERSION = "2023-06-01"
    }
}
