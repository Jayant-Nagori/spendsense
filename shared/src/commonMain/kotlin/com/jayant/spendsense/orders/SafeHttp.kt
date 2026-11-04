package com.jayant.spendsense.orders

import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONException
import com.jayant.spendsense.json.JSONObject
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Url
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/** Something went wrong talking to an order service. [message] is safe to show (never contains response data). */
class OrderServiceException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NETWORK, AUTH, REGISTRATION, PROTOCOL, TOO_LARGE, NOT_SUPPORTED, CANCELLED }
}

/** Limits applied to everything an order service sends back: it is untrusted input. */
object Limits {
    const val MAX_BODY_BYTES = 1_000_000L
    const val MAX_JSON_DEPTH = 48
    const val MAX_ORDERS = 200
    const val MAX_ITEMS = 60
    const val MAX_TEXT = 80
    const val MAX_TOOLS = 200
}

/**
 * Reads at most [Limits.MAX_BODY_BYTES] of [resp]; anything bigger is rejected without buffering the rest.
 * Decodes as UTF-8 (malformed bytes become U+FFFD).
 */
internal suspend fun HttpResponse.limitedText(max: Long = Limits.MAX_BODY_BYTES): String {
    headers["Content-Length"]?.toLongOrNull()?.let { if (it > max) throw tooLarge() }
    val bytes = bodyAsChannel().readRemaining(max + 1).readByteArray()
    if (bytes.size > max) throw tooLarge()
    return bytes.decodeToString()
}

private fun tooLarge() = OrderServiceException(OrderServiceException.Kind.TOO_LARGE, "The service sent more data than expected")

/** Rejects JSON nested deeper than [Limits.MAX_JSON_DEPTH] before the recursive parser sees it. */
internal fun checkJsonDepth(s: String, max: Int = Limits.MAX_JSON_DEPTH) {
    var depth = 0
    var inString = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else when (c) {
            '"' -> inString = true
            '{', '[' -> if (++depth > max) throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "Response nested too deeply")
            '}', ']' -> depth--
        }
        i++
    }
}

/** Parses untrusted text as a JSON object, or null when it is not one. Depth-checked. */
internal fun safeJsonObject(text: String): JSONObject? {
    val t = text.trim()
    if (!t.startsWith("{")) return null
    checkJsonDepth(t)
    return try { JSONObject(t) } catch (e: JSONException) { null }
}

/** Parses untrusted text as any JSON value (object or array), or null. Depth-checked. */
internal fun safeJsonValue(text: String): Any? {
    val t = text.trim()
    if (t.isEmpty() || (t[0] != '{' && t[0] != '[')) return null
    checkJsonDepth(t)
    return try {
        if (t[0] == '{') JSONObject(t) else JSONArray(t)
    } catch (e: JSONException) { null }
}

/** Removes control, bidi-override and zero-width characters and caps the length. Safe to put in a Text(). */
fun cleanText(raw: String?, max: Int = Limits.MAX_TEXT): String? {
    if (raw == null) return null
    val sb = StringBuilder()
    for (ch in raw) {
        val c = ch.code
        when {
            ch == '\n' || ch == '\t' || ch == '\r' -> sb.append(' ')
            c < 0x20 || c == 0x7f || c in 0x80..0x9f -> {}
            c in 0x200b..0x200f || c in 0x202a..0x202e || c in 0x2066..0x2069 || c == 0xfeff -> {}
            ch == '<' || ch == '>' -> {}
            else -> sb.append(ch)
        }
    }
    val s = sb.toString().replace(Regex("\\s+"), " ").trim()
    if (s.isEmpty()) return null
    return if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"
}

/**
 * Endpoints discovered from metadata must be https. The only exception is the debug fake server, whose origin is
 * passed as [allowedInsecureOrigin] (null in release builds).
 */
internal fun requireSecureUrl(url: String, allowedInsecureOrigin: String?): String {
    val u = runCatching { Url(url) }.getOrNull()
        ?: throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "Service sent an invalid address")
    if (u.protocol.name == "https" && u.host.isNotEmpty()) return url
    if (allowedInsecureOrigin != null && u.protocol.name == "http" && originOf(url) == originOf(allowedInsecureOrigin)) return url
    throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "Service sent an insecure address")
}

internal fun originOf(url: String): String {
    val u = Url(url)
    return "${u.protocol.name}://${u.host}" + if (u.port != u.protocol.defaultPort) ":${u.port}" else ""
}
