package com.jayant.spendsense.splitwise

import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import com.jayant.spendsense.orders.checkJsonDepth
import com.jayant.spendsense.orders.cleanText
import com.jayant.spendsense.orders.limitedText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** Something went wrong talking to Splitwise. [message] is safe to show: it never contains response data or the key. */
class SplitwiseException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { AUTH, NETWORK, RATE_LIMIT, PROTOCOL, TOO_LARGE, NOT_ALLOWED }
}

data class SwUser(val id: Long, val name: String)

data class SwShare(val userId: Long, val name: String, val paid: Double, val owed: Double)

data class SwExpense(
    val id: Long,
    val description: String,
    val cost: Double,
    val currency: String,
    val date: Long,
    val updatedAt: String?,
    val deleted: Boolean,
    val payment: Boolean,
    val category: String?,
    val groupId: Long?,
    val shares: List<SwShare>,
)

data class SwFriend(val id: Long, val name: String, val balance: Double, val currency: String)

data class SwGroup(val id: Long, val name: String, val memberIds: List<Long>)

/**
 * Splitwise REST v3.0 with a personal API key (`Authorization: Bearer`). Every response is untrusted: size-capped,
 * depth-checked, parsed tolerantly (numbers as strings or numbers, missing fields skipped) and every string is cleaned
 * for plain-text display. Only https://secure.splitwise.com/api/v3.0/ is ever contacted, and redirects are not followed
 * (the client is built with followRedirects = false). Never logs the key or any response.
 */
class SplitwiseApi(private val http: HttpClient, private val key: String, private val base: String = BASE) {
    init {
        if (!isAllowed(base)) throw SplitwiseException(SplitwiseException.Kind.NOT_ALLOWED, "Only secure.splitwise.com is allowed")
    }

    suspend fun currentUser(): SwUser {
        val o = getJson("get_current_user")
        val u = o.optJSONObject("user") ?: throw protocol()
        return SwUser(u.optLong("id", -1L).takeIf { it > 0 } ?: throw protocol(), personName(u) ?: "You")
    }

    /** Expenses updated after [updatedAfter] (ISO time) or dated after [datedAfter], newest first, paged, capped. */
    suspend fun expenses(datedAfter: String? = null, updatedAfter: String? = null, pageSize: Int = 100, maxTotal: Int = MAX_EXPENSES): List<SwExpense> {
        val out = ArrayList<SwExpense>()
        var offset = 0
        var pages = 0
        while (out.size < maxTotal && pages++ < maxTotal / pageSize + 3) {
            val q = buildList {
                add("limit=$pageSize"); add("offset=$offset")
                datedAfter?.let { add("dated_after=" + enc(it)) }
                updatedAfter?.let { add("updated_after=" + enc(it)) }
            }.joinToString("&")
            val arr = getJson("get_expenses?$q").optJSONArray("expenses") ?: break
            val page = (0 until arr.length()).mapNotNull { parseExpense(arr.opt(it) as? JSONObject) }
            out += page
            if (arr.length() < pageSize || page.isEmpty()) break
            offset += pageSize
        }
        return out.take(maxTotal)
    }

    suspend fun friends(): List<SwFriend> {
        val arr = getJson("get_friends").optJSONArray("friends") ?: return emptyList()
        return (0 until minOf(arr.length(), MAX_FRIENDS)).mapNotNull { i ->
            val f = arr.opt(i) as? JSONObject ?: return@mapNotNull null
            val id = f.optLong("id", -1L).takeIf { it > 0 } ?: return@mapNotNull null
            val bal = f.optJSONArray("balance")
            var inr = 0.0
            if (bal != null) for (j in 0 until minOf(bal.length(), 20)) {
                val b = bal.opt(j) as? JSONObject ?: continue
                if (b.optString("currency_code").equals(CURRENCY, true)) inr += num(b.opt("amount")) ?: 0.0
            }
            SwFriend(id, personName(f) ?: "Friend", inr, CURRENCY)
        }
    }

    suspend fun groups(): List<SwGroup> {
        val arr = getJson("get_groups").optJSONArray("groups") ?: return emptyList()
        return (0 until minOf(arr.length(), MAX_GROUPS)).mapNotNull { i ->
            val g = arr.opt(i) as? JSONObject ?: return@mapNotNull null
            val id = g.optLong("id", -1L).takeIf { it > 0 } ?: return@mapNotNull null
            val members = g.optJSONArray("members")
            val ids = if (members == null) emptyList() else (0 until minOf(members.length(), 50)).mapNotNull { j ->
                (members.opt(j) as? JSONObject)?.optLong("id", -1L)?.takeIf { it > 0 }
            }
            SwGroup(id, cleanText(g.optString("name"), 60) ?: "Group", ids)
        }
    }

    /**
     * Push (user-initiated only): one expense paid in full by [me]; [owedBy] maps user id to their share, including
     * [me]'s own. Returns the new expense id.
     */
    suspend fun createExpense(me: Long, cost: Double, description: String, owedBy: Map<Long, Double>, groupId: Long? = null): Long {
        require(cost > 0 && owedBy.isNotEmpty() && owedBy.size <= 50)
        val body = JSONObject()
            .put("cost", money(cost))
            .put("description", cleanText(description, 120) ?: "Payment")
            .put("currency_code", CURRENCY)
            .put("group_id", groupId ?: 0)
        val users = owedBy.entries.toList()
        users.forEachIndexed { i, (id, owed) ->
            body.put("users__${i}__user_id", id)
            body.put("users__${i}__paid_share", money(if (id == me) cost else 0.0))
            body.put("users__${i}__owed_share", money(owed))
        }
        val resp = call { http.post(url("create_expense")) { auth(); setBody(TextContent(body.toString(), ContentType.Application.Json)) } }
        val o = parse(resp)
        val errors = o.optJSONObject("errors")
        if (errors != null && errors.length() > 0) throw SplitwiseException(SplitwiseException.Kind.PROTOCOL, "Splitwise didn't accept the expense")
        val arr = o.optJSONArray("expenses")
        return (arr?.opt(0) as? JSONObject)?.optLong("id", -1L)?.takeIf { it > 0 } ?: throw protocol()
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    private fun url(path: String): String {
        val u = base + path
        if (!isAllowed(u)) throw SplitwiseException(SplitwiseException.Kind.NOT_ALLOWED, "Only secure.splitwise.com is allowed")
        return u
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        header("Authorization", "Bearer $key")
        header("Accept", "application/json")
    }

    private suspend fun getJson(path: String): JSONObject = parse(call { http.get(url(path)) { auth() } })

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SplitwiseException) {
        throw e
    } catch (e: Exception) {
        throw SplitwiseException(SplitwiseException.Kind.NETWORK, "Couldn't reach Splitwise. Check your connection.")
    }

    private suspend fun parse(resp: HttpResponse): JSONObject {
        when (resp.status.value) {
            401, 403 -> throw SplitwiseException(SplitwiseException.Kind.AUTH, "Splitwise didn't accept the API key")
            429 -> throw SplitwiseException(SplitwiseException.Kind.RATE_LIMIT, "Splitwise is busy. Try again in a few minutes.")
            in 300..399 -> throw SplitwiseException(SplitwiseException.Kind.NOT_ALLOWED, "Splitwise tried to redirect; refused")
            !in 200..299 -> throw SplitwiseException(SplitwiseException.Kind.PROTOCOL, "Splitwise returned an error (${resp.status.value})")
        }
        val text = try {
            resp.limitedText(MAX_BODY)
        } catch (e: com.jayant.spendsense.orders.OrderServiceException) {
            throw SplitwiseException(SplitwiseException.Kind.TOO_LARGE, "Splitwise sent more data than expected")
        }
        val t = text.trim()
        if (!t.startsWith("{")) throw protocol()
        try { checkJsonDepth(t, 24) } catch (e: Exception) { throw protocol() }
        return try { JSONObject(t) } catch (e: Exception) { throw protocol() }
    }

    private fun protocol() = SplitwiseException(SplitwiseException.Kind.PROTOCOL, "Unexpected reply from Splitwise")

    companion object {
        const val BASE = "https://secure.splitwise.com/api/v3.0/"
        const val HOST = "secure.splitwise.com"
        const val CURRENCY = "INR"
        const val MAX_BODY = 2_000_000L
        const val MAX_EXPENSES = 2_000
        const val MAX_FRIENDS = 500
        const val MAX_GROUPS = 200
        private const val MAX_USERS_PER_EXPENSE = 50

        /** The only URLs ever requested: https, host secure.splitwise.com, default port, under /api/v3.0/. */
        fun isAllowed(url: String): Boolean {
            val u = runCatching { Url(url) }.getOrNull() ?: return false
            if (url.any { it.isWhitespace() || it == '\\' || it == '@' }) return false
            return u.protocol.name == "https" && u.host.equals(HOST, ignoreCase = false) && u.port == 443 &&
                u.encodedPath.startsWith("/api/v3.0/") && !u.encodedPath.contains("..") && u.user == null
        }

        /** Personal API keys are long opaque tokens; reject obvious junk before it is ever sent. */
        fun looksLikeKey(key: String): Boolean = key.length in 20..200 && key.all { it.isLetterOrDigit() || it in "-_." }

        /** Only a real ISO instant goes into a query, re-serialised and percent-encoded (R3-08). */
        private fun enc(s: String): String {
            val iso = runCatching { Instant.parse(s).toString() }.getOrElse { throw SplitwiseException(SplitwiseException.Kind.PROTOCOL, "Bad sync time") }
            return iso.map { c -> if (c.isLetterOrDigit() || c in "-._~") c.toString() else "%" + c.code.toString(16).uppercase().padStart(2, '0') }.joinToString("")
        }

        private fun money(v: Double): String = com.jayant.spendsense.formatFixed(v, 2)

        internal fun num(v: Any?): Double? = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        }?.takeIf { !it.isNaN() && !it.isInfinite() && kotlin.math.abs(it) < 1e10 }

        internal fun personName(o: JSONObject): String? {
            val first = cleanText(o.optString("first_name"), 40)
            val last = cleanText(o.optString("last_name"), 40)
            return listOfNotNull(first, last).joinToString(" ").ifBlank { null }
        }

        /** Tolerant expense parser: null when the essentials (id, cost, date, users) are missing or malformed. */
        internal fun parseExpense(o: JSONObject?): SwExpense? {
            o ?: return null
            val id = o.optLong("id", -1L).takeIf { it > 0 } ?: return null
            val cost = num(o.opt("cost"))?.takeIf { it >= 0 } ?: return null
            val date = o.optString("date").let { s -> runCatching { Instant.parse(s).toEpochMilliseconds() }.getOrNull() } ?: return null
            val users = o.optJSONArray("users") ?: return null
            val shares = (0 until minOf(users.length(), MAX_USERS_PER_EXPENSE)).mapNotNull { i ->
                val u = users.opt(i) as? JSONObject ?: return@mapNotNull null
                val uid = u.optLong("user_id", -1L).takeIf { it > 0 } ?: (u.optJSONObject("user")?.optLong("id", -1L)?.takeIf { it > 0 }) ?: return@mapNotNull null
                SwShare(
                    uid, u.optJSONObject("user")?.let(::personName) ?: "Someone",
                    num(u.opt("paid_share")) ?: 0.0, num(u.opt("owed_share")) ?: 0.0,
                )
            }
            val deleted = o.opt("deleted_at").let { it != null && it != JSONObject.NULL && it.toString().isNotBlank() }
            return SwExpense(
                id = id,
                description = cleanText(o.optString("description"), 80) ?: "Splitwise expense",
                cost = cost,
                currency = cleanText(o.optString("currency_code"), 6)?.uppercase() ?: CURRENCY,
                date = date,
                updatedAt = runCatching { Instant.parse(o.optString("updated_at")).toString() }.getOrNull(),
                deleted = deleted,
                payment = o.optBoolean("payment", false),
                category = o.optJSONObject("category")?.let { cleanText(it.optString("name"), 40) },
                groupId = o.optLong("group_id", 0L).takeIf { it > 0 },
                shares = shares,
            )
        }

        @Suppress("unused")
        private fun JSONArray.size() = length()
    }
}
