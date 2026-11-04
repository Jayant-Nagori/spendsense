package com.jayant.spendsense.orders

import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.http.parseUrlEncodedParameters

/**
 * In-process fake of an order service: RFC 9728/8414 metadata, RFC 7591 registration, authorize (simulated browser),
 * token endpoint with PKCE S256 verification and refresh, revocation, and an MCP server over Streamable HTTP that
 * can answer in JSON or SSE. Records everything so tests can assert what the client did — especially that it never
 * called a state-changing tool.
 */
class FakeOrderServer(val base: String = "https://fake.test") {
    // --- knobs ---
    var sse = false
    var rejectRegistration = false
    var sendIss = true
    var wrongIss = false
    var advertiseIss = true
    /** Round 2: metadata that claims another issuer / protected-resource metadata for another resource. */
    var metadataIssuer: String? = null
    var prmResource: String? = null
    var expireFirstAccessToken = false
    var ordersPayload: (String) -> JSONObject = { _ -> textResult(JSONObject().put("orders", JSONArray()).toString()) }
    var rawMcpBody: String? = null
    var tools: List<JSONObject> = defaultTools()

    // --- records ---
    val registrations = mutableListOf<JSONObject>()
    val toolCalls = mutableListOf<String>()
    val toolArgs = mutableListOf<JSONObject>()
    val methods = mutableListOf<String>()
    val tokenGrants = mutableListOf<String>()
    val revoked = mutableListOf<String>()
    val sessionIdsSeen = mutableListOf<String?>()
    val unauthenticatedProbes = mutableListOf<String>()

    private val clients = mutableMapOf<String, String>() // client_id -> redirect_uri
    private class Grant(val clientId: String, val redirectUri: String, val challenge: String, val scope: String?)
    private val codes = mutableMapOf<String, Grant>()
    private val access = mutableSetOf<String>()
    private val refresh = mutableSetOf<String>()
    private var counter = 0
    private var expiredOnce = false

    val issuer get() = "$base/auth"

    val client: HttpClient = HttpClient(MockEngine { req -> handle(req) }) { expectSuccess = false; followRedirects = false }

    /** Plays the user's browser: checks the authorize request and returns the redirect URL with a code. */
    val browser = AuthBrowser { url, redirectUri ->
        val u = Url(url)
        require(url.startsWith("$issuer/authorize")) { "authorize went to the wrong place" }
        val p = u.parameters
        require(p["response_type"] == "code")
        require(p["code_challenge_method"] == "S256")
        require(p["redirect_uri"] == redirectUri)
        val clientId = p["client_id"]!!
        require(clients[clientId] == redirectUri) { "unregistered client/redirect" }
        val code = "code${++counter}"
        codes[code] = Grant(clientId, redirectUri, p["code_challenge"]!!, p["scope"])
        lastAuthorizeScope = p["scope"]
        lastResource = p["resource"]
        buildString {
            append(redirectUri).append("?code=").append(code).append("&state=").append(p["state"])
            if (sendIss) append("&iss=").append(if (wrongIss) "https://evil.test" else issuer)
        }
    }
    var lastAuthorizeScope: String? = null
    var lastResource: String? = null

    private suspend fun MockRequestHandleScope.handle(req: HttpRequestData): HttpResponseData {
        val url = req.url.toString().substringBefore('?')
        val body = (req.body as? TextContent)?.text.orEmpty()
        val path = url.removePrefix(base)
        return when {
            path.startsWith("/.well-known/oauth-protected-resource/") -> {
                val res = base + path.removePrefix("/.well-known/oauth-protected-resource")
                json(JSONObject().put("resource", prmResource ?: res).put("authorization_servers", JSONArray().put(issuer)))
            }
            path == "/.well-known/oauth-authorization-server/auth" -> json(
                JSONObject()
                    .put("issuer", metadataIssuer ?: issuer)
                    .put("authorization_endpoint", "$issuer/authorize")
                    .put("token_endpoint", "$issuer/token")
                    .put("registration_endpoint", "$issuer/register")
                    .put("revocation_endpoint", "$issuer/revoke")
                    .put("code_challenge_methods_supported", JSONArray().put("S256"))
                    .put("scopes_supported", JSONArray().put("mcp:tools").put("tools:read").put("tools:write"))
                    .put("token_endpoint_auth_methods_supported", JSONArray().put("none"))
                    .put("authorization_response_iss_parameter_supported", advertiseIss),
            )
            path == "/auth/register" -> {
                val o = JSONObject(body)
                registrations += o
                if (rejectRegistration) return respondJson(JSONObject().put("error", "invalid_client_metadata").toString(), HttpStatusCode.Forbidden)
                val id = "client${++counter}"
                clients[id] = o.getJSONArray("redirect_uris").getString(0)
                json(JSONObject().put("client_id", id).put("token_endpoint_auth_method", "none"), HttpStatusCode.Created)
            }
            path == "/auth/token" -> token(body.parseUrlEncodedParameters().entries().associate { it.key to it.value.first() })
            path == "/auth/revoke" -> {
                body.parseUrlEncodedParameters()["token"]?.let { revoked += it; access.remove(it); refresh.remove(it) }
                respond("", HttpStatusCode.OK)
            }
            path.matches(Regex("/(zomato|swiggy|zepto)/(mcp|food|im)")) -> mcp(req, path, body)
            else -> respond("not found", HttpStatusCode.NotFound)
        }
    }

    private fun MockRequestHandleScope.token(form: Map<String, String>): HttpResponseData {
        tokenGrants += form["grant_type"].orEmpty()
        return when (form["grant_type"]) {
            "authorization_code" -> {
                val g = codes.remove(form["code"]) ?: return error("invalid_grant")
                if (g.clientId != form["client_id"] || g.redirectUri != form["redirect_uri"]) return error("invalid_grant")
                if (Pkce.challengeFor(form["code_verifier"].orEmpty()) != g.challenge) return error("invalid_grant")
                issue(g.scope)
            }
            "refresh_token" -> {
                if (form["refresh_token"] !in refresh) return error("invalid_grant")
                issue(null, keepRefresh = true)
            }
            else -> error("unsupported_grant_type")
        }
    }

    private fun MockRequestHandleScope.issue(scope: String?, keepRefresh: Boolean = false): HttpResponseData {
        val at = "at${++counter}"
        access += at
        val o = JSONObject().put("access_token", at).put("token_type", "Bearer").put("expires_in", 3600).put("scope", scope)
        if (!keepRefresh) { val rt = "rt${++counter}"; refresh += rt; o.put("refresh_token", rt) }
        return json(o)
    }

    private fun MockRequestHandleScope.error(code: String) = respondJson(JSONObject().put("error", code).toString(), HttpStatusCode.BadRequest)

    private fun MockRequestHandleScope.mcp(req: HttpRequestData, path: String, body: String): HttpResponseData {
        val auth = req.headers["Authorization"]?.removePrefix("Bearer ")
        if (auth == null || auth !in access) {
            if (auth == null) unauthenticatedProbes += path
            return respond(
                JSONObject().put("error", "invalid_token").toString(), HttpStatusCode.Unauthorized,
                headersOf("WWW-Authenticate", "Bearer error=\"invalid_token\", resource_metadata=\"$base/.well-known/oauth-protected-resource$path\", scope=\"mcp:tools\""),
            )
        }
        if (expireFirstAccessToken && !expiredOnce) {
            expiredOnce = true
            access.remove(auth)
            return respond("", HttpStatusCode.Unauthorized, headersOf("WWW-Authenticate", "Bearer error=\"invalid_token\""))
        }
        val msg = JSONObject(body)
        val method = msg.optString("method")
        methods += method
        sessionIdsSeen += req.headers["Mcp-Session-Id"]
        if (!msg.has("id")) return respond("", HttpStatusCode.Accepted)
        rawMcpBody?.let { if (method == "tools/call") return reply(it) }
        val result = when (method) {
            "initialize" -> JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject().put("tools", JSONObject()))
                .put("serverInfo", JSONObject().put("name", "fake").put("version", "1"))
            "tools/list" -> JSONObject().put("tools", JSONArray().also { a -> tools.forEach { a.put(it) } })
            "tools/call" -> {
                val name = msg.getJSONObject("params").getString("name")
                toolCalls += name
                toolArgs += msg.getJSONObject("params").optJSONObject("arguments") ?: JSONObject()
                ordersPayload(name)
            }
            else -> return reply(JSONObject().put("jsonrpc", "2.0").put("id", msg.get("id")).put("error", JSONObject().put("code", -32601).put("message", "nope")).toString())
        }
        val response = JSONObject().put("jsonrpc", "2.0").put("id", msg.get("id")).put("result", result).toString()
        return reply(response, session = if (method == "initialize") "sess-${path.hashCode()}" else null)
    }

    private fun MockRequestHandleScope.reply(response: String, session: String? = null): HttpResponseData {
        val headers = buildList {
            add("Content-Type" to listOf(if (sse) "text/event-stream" else "application/json"))
            session?.let { add("Mcp-Session-Id" to listOf(it)) }
        }
        val text = if (sse) {
            // Keep-alive comment and a progress notification first, then the response split over two data lines.
            val cut = response.indexOf(",\"").takeIf { it > 0 } ?: response.length
            ": keep-alive\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                "event: message\r\nid: 7\r\ndata: " + response.substring(0, cut) + "\r\ndata:" + response.substring(cut) + "\r\n\r\n"
        } else response
        return respond(text, HttpStatusCode.OK, io.ktor.http.Headers.build { headers.forEach { (k, v) -> appendAll(k, v) } })
    }

    private fun MockRequestHandleScope.json(o: JSONObject, status: HttpStatusCode = HttpStatusCode.OK) = respondJson(o.toString(), status)

    private fun MockRequestHandleScope.respondJson(text: String, status: HttpStatusCode) =
        respond(text, status, headersOf("Content-Type", "application/json"))

    companion object {
        fun tool(name: String, description: String, props: JSONObject = JSONObject(), required: List<String> = emptyList(), annotations: JSONObject? = null): JSONObject =
            JSONObject().put("name", name).put("description", description)
                .put("inputSchema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray().also { a -> required.forEach { a.put(it) } }))
                .also { if (annotations != null) it.put("annotations", annotations) }

        fun defaultTools(): List<JSONObject> = listOf(
            tool("search_restaurants", "Search restaurants near you", JSONObject().put("query", JSONObject().put("type", "string")), listOf("query")),
            tool("add_to_cart", "Add an item to the cart"),
            tool("place_order", "Places the order in the cart"),
            tool("get_order_history", "Your past orders", JSONObject().put("limit", JSONObject().put("type", "integer")).put("from_date", JSONObject().put("type", "string"))),
            tool("get_order_details", "Details of one past order", JSONObject().put("order_id", JSONObject().put("type", "string")), listOf("order_id")),
            tool("cancel_order", "Cancel an order", JSONObject().put("order_id", JSONObject().put("type", "string")), listOf("order_id")),
            tool("make_payment", "Pay for the cart"),
        )

        fun textResult(text: String): JSONObject =
            JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", false)
    }
}

/** In-memory [ConnectionVault]. */
class MemoryVault : ConnectionVault {
    val map = mutableMapOf<OrderProvider, Connection>()
    override fun load(provider: OrderProvider): Connection? = map[provider]
    override fun save(provider: OrderProvider, connection: Connection?) { if (connection == null) map.remove(provider) else map[provider] = connection }
}
