package com.jayant.spendsense.orders

import com.jayant.spendsense.BuildInfo
import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlin.coroutines.cancellation.CancellationException

/** A tool as advertised by tools/list. Only names that pass [ReadOnlyGuard] can ever be called. */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject?,
    val readOnlyHint: Boolean?,
    val destructiveHint: Boolean?,
) {
    /** Property names of the input schema. */
    val params: Set<String> get() = inputSchema?.optJSONObject("properties")?.keySet().orEmpty()
    val required: Set<String> get() = inputSchema?.optJSONArray("required")?.strings()?.toSet().orEmpty()
}

/**
 * Minimal MCP client over Streamable HTTP (2025-06-18): JSON-RPC POSTs answered with either application/json or a
 * text/event-stream. Bearer auth with one refresh-and-retry on 401. Read-only by construction: [callTool] refuses
 * any tool [ReadOnlyGuard] has not approved from this server's own tools/list.
 */
class McpClient(
    private val http: HttpClient,
    private val url: String,
    /** Returns a bearer token; with forceRefresh = true it must refresh first (or return null if it can't). */
    private val token: suspend (forceRefresh: Boolean) -> String?,
) {
    private var sessionId: String? = null
    private var protocolVersion: String? = null
    private var nextId = 1L
    private var approved: Map<String, McpTool> = emptyMap()

    suspend fun initialize() {
        val result = request("initialize", initializeParams())
        protocolVersion = result.optString("protocolVersion", PROTOCOL_VERSION).ifBlank { PROTOCOL_VERSION }
        notify("notifications/initialized")
    }

    /** All tools (paged, capped), remembering which ones the read-only guard allows. */
    suspend fun listTools(): List<McpTool> {
        val all = mutableListOf<McpTool>()
        var cursor: String? = null
        repeat(5) {
            val params = JSONObject().also { p -> cursor?.let { p.put("cursor", it) } }
            val result = request("tools/list", params)
            val arr = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                if (all.size >= Limits.MAX_TOOLS) break
                val t = arr.optJSONObject(i) ?: continue
                val name = t.optString("name", "").take(128).ifBlank { null } ?: continue
                val ann = t.optJSONObject("annotations")
                all += McpTool(
                    name = name,
                    description = t.optString("description", "").take(1000),
                    inputSchema = t.optJSONObject("inputSchema"),
                    readOnlyHint = ann?.takeIf { it.has("readOnlyHint") }?.optBoolean("readOnlyHint"),
                    destructiveHint = ann?.takeIf { it.has("destructiveHint") }?.optBoolean("destructiveHint"),
                )
            }
            cursor = result.optString("nextCursor", "").ifBlank { null }
            if (cursor == null || all.size >= Limits.MAX_TOOLS) return all.also { remember(it) }
        }
        return all.also { remember(it) }
    }

    private fun remember(tools: List<McpTool>) {
        approved = tools.filter { ReadOnlyGuard.isAllowed(it) }.associateBy { it.name }
    }

    /** Calls a tool previously approved by [ReadOnlyGuard]; anything else throws before a byte is sent. */
    suspend fun callTool(name: String, arguments: JSONObject): JSONObject {
        val tool = approved[name]
        if (tool == null || !ReadOnlyGuard.isAllowed(tool)) throw ReadOnlyViolation(name)
        return request("tools/call", JSONObject().put("name", name).put("arguments", arguments))
    }

    private suspend fun notify(method: String) {
        runCatching { post(JSONObject().put("jsonrpc", "2.0").put("method", method), retryAuth = true) }
            .onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun request(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val msg = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        val (resp, text) = post(msg, retryAuth = true)
        val status = resp.status.value
        if (status == 404 && sessionId != null) throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "session expired")
        if (status !in 200..299) throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "server error ($status)")
        val contentType = resp.headers["Content-Type"].orEmpty().lowercase()
        val reply = if (contentType.startsWith("text/event-stream")) fromSse(text, id) else fromJson(text, id)
        reply ?: throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "no reply to $method")
        reply.optJSONObject("error")?.let {
            throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "$method failed (${it.optInt("code", 0)})")
        }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    private suspend fun post(msg: JSONObject, retryAuth: Boolean): Pair<HttpResponse, String> {
        var bearer = token(false) ?: throw OrderServiceException(OrderServiceException.Kind.AUTH, "not signed in")
        repeat(2) { attempt ->
            val resp = try {
                http.post(url) {
                    header("Accept", "application/json, text/event-stream")
                    header("Authorization", "Bearer $bearer")
                    header("User-Agent", "SpendSense/${BuildInfo.VERSION_NAME}")
                    sessionId?.let { header("Mcp-Session-Id", it) }
                    protocolVersion?.let { header("MCP-Protocol-Version", it) }
                    setBody(TextContent(msg.toString(), ContentType.Application.Json))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw OrderServiceException(OrderServiceException.Kind.NETWORK, "Couldn't reach the service")
            }
            if (resp.status.value == 401 && retryAuth && attempt == 0) {
                bearer = token(true) ?: throw OrderServiceException(OrderServiceException.Kind.AUTH, "sign-in expired")
                return@repeat
            }
            if (resp.status.value == 401) throw OrderServiceException(OrderServiceException.Kind.AUTH, "sign-in expired")
            resp.headers["Mcp-Session-Id"]?.takeIf { it.length in 1..256 && it.all { c -> c.code in 0x21..0x7e } }?.let { sessionId = it }
            return resp to resp.limitedText()
        }
        throw OrderServiceException(OrderServiceException.Kind.AUTH, "sign-in expired")
    }

    private fun fromJson(text: String, id: Long): JSONObject? = when (val v = safeJsonValue(text)) {
        is JSONObject -> v.takeIf { matches(it, id) }
        is JSONArray -> (0 until v.length()).mapNotNull { v.optJSONObject(it) }.firstOrNull { matches(it, id) }
        else -> null
    }

    /** Server-sent events: each event's data lines joined; the first JSON-RPC response with our id wins. */
    private fun fromSse(text: String, id: Long): JSONObject? {
        for (event in parseSse(text)) {
            val obj = fromJson(event, id)
            if (obj != null) return obj
        }
        return null
    }

    private fun matches(o: JSONObject, id: Long): Boolean =
        (o.has("result") || o.has("error")) && o.opt("id")?.toString()?.toDoubleOrNull()?.toLong() == id

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"

        private fun initializeParams(): JSONObject = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "SpendSense").put("version", BuildInfo.VERSION_NAME))

        /** The initialize request (also used unauthenticated by discovery to provoke the 401). */
        fun initializeRequest(id: Long): JSONObject =
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "initialize").put("params", initializeParams())

        /** Splits an SSE stream into event data payloads (multi-line data joined with '\n'). */
        fun parseSse(text: String): List<String> {
            val events = mutableListOf<String>()
            val data = StringBuilder()
            var hasData = false
            for (rawLine in text.split('\n')) {
                val line = rawLine.removeSuffix("\r")
                if (line.isEmpty()) {
                    if (hasData) events += data.toString()
                    data.clear(); hasData = false
                    continue
                }
                if (line.startsWith(":")) continue
                val field = line.substringBefore(':')
                var value = if (':' in line) line.substringAfter(':') else ""
                if (value.startsWith(" ")) value = value.substring(1)
                if (field == "data") {
                    if (hasData) data.append('\n')
                    data.append(value); hasData = true
                }
            }
            if (hasData) events += data.toString()
            return events
        }
    }
}

class ReadOnlyViolation(tool: String) : IllegalStateException("Blocked non-read-only tool: ${tool.take(64)}")

/**
 * Decides which MCP tools SpendSense may call. Order history and order details only; anything that could change
 * state (cart, checkout, ordering, payment, cancellation, address or account edits) is refused, whatever the
 * server says about it. Fail-closed: unknown tools are not allowed.
 */
object ReadOnlyGuard {
    /** Hard denylist, matched against every word of the tool name (so "addToCart", "place_order", "pay_now" all fail). */
    private val DENY_WORDS = listOf(
        "cart", "checkout", "place", "pay", "create", "update", "cancel", "add", "remove", "book", "delete", "edit",
        "set", "submit", "confirm", "reorder", "apply", "coupon", "write", "modify", "post", "put", "save", "rate",
        "review", "tip", "refund", "return", "subscribe", "login", "logout", "send", "track_live", "manage", "change",
        "buy", "make", "repeat", "duplicate", "clone", "transfer", "withdraw", "redeem", "claim", "accept", "approve",
        "authori", "execute", "run", "invoke", "trigger", "schedule", "insert", "upsert", "patch", "mutate", "upload",
        "share", "invite", "charge", "debit", "credit", "topup", "recharge", "start", "begin", "do",
    )
    /** Exact (not prefix) denials: "purchase" the verb, while "purchases" (a list of past ones) stays readable. */
    private val DENY_EXACT = setOf("purchase", "order_now", "now", "new", "instant", "quick")

    /** Phrases in a description that betray a state-changing tool even behind an innocent name. */
    private val DENY_PHRASES = Regex(
        "\\b(places? (an |a |the )?order|add(s|ing)? (items? )?to (the )?cart|check ?out|make (a )?payment|pays? for|" +
            "cancels?\\b|modif(y|ies)|updates? (the|your)|creates? (an? )?(order|cart)|book(s|ing)? |" +
            "buy(s|ing)?\\b|purchases? (an?|the|this|items?|products?)\\b|charges?\\b|debits?\\b|transfers? (money|funds)|" +
            "re[- ]?orders?\\b|repeats?\\b.{0,20}\\border|orders? (food|items|groceries|it) for)",
        RegexOption.IGNORE_CASE,
    )
    private val ALLOW_EXACT = setOf(
        "get_order_history", "order_history", "get_orders", "list_orders", "get_past_orders", "past_orders",
        "get_order_details", "order_details", "get_order", "fetch_orders", "get_recent_orders", "my_orders",
        "get_previous_orders", "orders_history", "get_food_orders", "get_instamart_orders", "get_order_status",
    )
    private val ALLOW_VERBS = setOf("get", "list", "fetch", "history", "past", "previous", "recent", "details", "detail", "view", "read", "show")

    /** A non-exact name must *start* with one of these, so "order" can only be the noun ("order_my_food" fails). */
    private val LEADING_READ_VERBS = setOf("get", "list", "fetch", "view", "read", "show")

    /**
     * Fail-closed vocabulary: every word of an allowed tool name must be one of these. A server cannot sneak in a
     * new verb ("get_orders_and_settle"), split a denied word ("pl_ace_order") or use a synonym we never listed.
     */
    private val NAME_VOCAB = ALLOW_VERBS + setOf(
        "my", "all", "order", "orders", "purchases", "status", "food", "instamart", "grocery", "groceries", "by", "id", "for",
        "user", "the", "of", "swiggy", "zomato", "zepto", "dineout", "info", "summary", "latest", "last", "items",
        "item", "v1", "v2", "data",
    )

    /** Plain ASCII identifiers only: homoglyphs ("рlace_order" with a Cyrillic р) or invisible characters fail. */
    private val NAME_CHARS = Regex("^[A-Za-z0-9_.\\-]{1,80}$")

    /** Splits snake_case, kebab-case, dotted and camelCase names into lower-case words. */
    fun words(name: String): List<String> =
        name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * Folds the usual look-alike tricks out of free text before phrase matching: zero-width / bidi controls are
     * dropped, full-width ASCII and Cyrillic/Greek homoglyphs map to Latin, separators become spaces.
     */
    fun skeleton(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text.take(4_000)) {
            val c = ch.code
            when {
                c in 0x200B..0x200F || c in 0x202A..0x202E || c in 0x2060..0x2064 || c == 0xFEFF || c == 0x00AD -> Unit
                c in 0xFF01..0xFF5E -> sb.append((c - 0xFEE0).toChar())
                else -> sb.append(HOMOGLYPHS[ch] ?: ch)
            }
        }
        return sb.toString().replace(Regex("[_\\-.]+"), " ")
    }

    private val HOMOGLYPHS: Map<Char, Char> = mapOf(
        'а' to 'a', 'е' to 'e', 'о' to 'o', 'р' to 'p', 'с' to 'c', 'у' to 'y', 'х' to 'x', 'і' to 'i', 'ј' to 'j',
        'ѕ' to 's', 'ԁ' to 'd', 'һ' to 'h', 'ԛ' to 'q', 'ԝ' to 'w', 'к' to 'k', 'м' to 'm', 'т' to 't', 'в' to 'b',
        'н' to 'h', 'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H', 'О' to 'O', 'Р' to 'P',
        'С' to 'C', 'Т' to 'T', 'Х' to 'X', 'ο' to 'o', 'α' to 'a', 'ρ' to 'p', 'τ' to 't', 'υ' to 'u', 'ν' to 'v',
        'ε' to 'e', 'ι' to 'i', 'κ' to 'k', 'Ο' to 'O', 'Α' to 'A', 'Ρ' to 'P', 'Τ' to 'T', 'Ε' to 'E', 'Ι' to 'I',
        'ɑ' to 'a', 'ɡ' to 'g', 'ı' to 'i', 'ⅼ' to 'l', 'ℓ' to 'l',
    )

    fun isAllowed(tool: McpTool): Boolean {
        if (!NAME_CHARS.matches(tool.name)) return false
        val words = words(tool.name)
        if (words.isEmpty()) return false
        if (words.any { w -> w in DENY_EXACT || DENY_WORDS.any { d -> w.startsWith(d) } }) return false
        if (words.any { it !in NAME_VOCAB }) return false
        val desc = skeleton(tool.description)
        if (DENY_PHRASES.containsMatchIn(desc)) return false
        if (words(desc).any { w -> w in DENY_EXACT_DESC }) return false
        if (tool.destructiveHint == true || tool.readOnlyHint == false) return false
        val normalized = words.joinToString("_")
        if (normalized in ALLOW_EXACT) return true
        if (words.first() !in LEADING_READ_VERBS) return false
        val aboutOrders = words.drop(1).any { it == "order" || it == "orders" || it == "purchases" }
        return aboutOrders
    }

    /** Single words in a description that only state-changing tools use. */
    private val DENY_EXACT_DESC = setOf("checkout", "addtocart", "placeorder", "reorder", "paynow", "buynow")

    /** True for a tool that lists past orders (as opposed to details of one order). */
    fun isHistoryTool(tool: McpTool): Boolean {
        val w = words(tool.name)
        return isAllowed(tool) && ("history" in w || "past" in w || "previous" in w || "recent" in w || "orders" in w || "list" in w) &&
            tool.required.none { it.contains("order", ignoreCase = true) }
    }

    /** True for a tool that returns one order given its id. */
    fun isDetailsTool(tool: McpTool): Boolean =
        isAllowed(tool) && !isHistoryTool(tool) && tool.params.any { it.contains("order", ignoreCase = true) && it.contains("id", ignoreCase = true) }
}
