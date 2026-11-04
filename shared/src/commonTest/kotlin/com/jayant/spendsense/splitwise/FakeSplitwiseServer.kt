package com.jayant.spendsense.splitwise

import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/**
 * In-process fake of the Splitwise v3.0 API (same pattern as FakeOrderServer). Nothing goes over the network; tests
 * never use a real key or account. Records every request so tests can assert host, auth and that reads never write.
 */
class FakeSplitwiseServer(val key: String = "fake-test-key-0123456789abcdef") {
    var meId = 100L
    val expenses = mutableListOf<JSONObject>()
    val friends = mutableListOf<JSONObject>()
    val groups = mutableListOf<JSONObject>()
    var overrideBody: String? = null
    var overrideStatus: Int? = null
    var redirectTo: String? = null

    val requests = mutableListOf<String>()      // "GET https://host/path?query"
    val authHeaders = mutableListOf<String?>()
    val created = mutableListOf<JSONObject>()

    val client: HttpClient = HttpClient(MockEngine { req ->
        requests += "${req.method.value} ${req.url}"
        authHeaders += req.headers["Authorization"]
        val json = headersOf("Content-Type", "application/json")
        redirectTo?.let { return@MockEngine respond("", HttpStatusCode.Found, headersOf("Location", it)) }
        if (req.headers["Authorization"] != "Bearer $key") return@MockEngine respond("{\"error\":\"Invalid API request: you are not logged in\"}", HttpStatusCode.Unauthorized, json)
        overrideStatus?.let { return@MockEngine respond(overrideBody ?: "{}", HttpStatusCode.fromValue(it), json) }
        overrideBody?.let { return@MockEngine respond(it, HttpStatusCode.OK, json) }
        val path = req.url.encodedPath.removePrefix("/api/v3.0/")
        val q = req.url.parameters
        val body = when (path) {
            "get_current_user" -> JSONObject().put("user", JSONObject().put("id", meId).put("first_name", "Jay").put("last_name", "N").put("default_currency", "INR"))
            "get_expenses" -> {
                val updatedAfter = q["updated_after"]
                val limit = q["limit"]?.toInt() ?: 20
                val offset = q["offset"]?.toInt() ?: 0
                val list = expenses.filter { updatedAfter == null || it.optString("updated_at") > updatedAfter }
                JSONObject().put("expenses", JSONArray().also { a -> list.drop(offset).take(limit).forEach { a.put(it) } })
            }
            "get_friends" -> JSONObject().put("friends", JSONArray().also { a -> friends.forEach { a.put(it) } })
            "get_groups" -> JSONObject().put("groups", JSONArray().also { a -> groups.forEach { a.put(it) } })
            "create_expense" -> {
                val sent = JSONObject((req.body as TextContent).text)
                created += sent
                JSONObject().put("expenses", JSONArray().put(JSONObject().put("id", 9000 + created.size))).put("errors", JSONObject())
            }
            else -> return@MockEngine respond("{}", HttpStatusCode.NotFound, json)
        }
        respond(body.toString(), HttpStatusCode.OK, json)
    }) { expectSuccess = false; followRedirects = false }

    fun user(id: Long, first: String, paid: Double, owed: Double) = JSONObject()
        .put("user_id", id).put("user", JSONObject().put("id", id).put("first_name", first).put("last_name", ""))
        .put("paid_share", paid.toString()).put("owed_share", owed.toString())

    fun expense(
        id: Long, description: String, cost: Double, date: String, users: List<JSONObject>,
        payment: Boolean = false, updated: String = date, deleted: String? = null, category: String = "Dining out", currency: String = "INR",
    ): JSONObject = JSONObject().put("id", id).put("description", description).put("cost", cost.toString()).put("currency_code", currency)
        .put("date", date).put("updated_at", updated).put("deleted_at", deleted ?: JSONObject.NULL).put("payment", payment)
        .put("category", JSONObject().put("name", category)).put("users", JSONArray().also { a -> users.forEach { a.put(it) } })
        .also { expenses.removeAll { e -> e.optLong("id") == id }; expenses += it }

    fun friend(id: Long, first: String, balance: Double) =
        JSONObject().put("id", id).put("first_name", first).put("last_name", "")
            .put("balance", JSONArray().put(JSONObject().put("currency_code", "INR").put("amount", balance.toString())))
            .also { friends += it }
}
