package com.jayant.spendsense.orders

import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.json.JSONObject
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Everything needed to call a provider again later. Kept only in the platform secret store; never logged. */
data class Connection(
    val clientId: String,
    val redirectUri: String,
    val issuer: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String?,
    val resource: String?,
    val tokens: TokenSet,
    val connectedAt: Long,
) {
    fun toJson(): String = JSONObject()
        .put("client_id", clientId).put("redirect_uri", redirectUri).put("issuer", issuer)
        .put("token_endpoint", tokenEndpoint).put("revocation_endpoint", revocationEndpoint).put("resource", resource)
        .put("access_token", tokens.accessToken).put("refresh_token", tokens.refreshToken)
        .put("expires_at", tokens.expiresAt).put("scope", tokens.scope).put("connected_at", connectedAt)
        .toString()

    companion object {
        fun fromJson(s: String?): Connection? = runCatching {
            val o = JSONObject(s ?: return null)
            Connection(
                clientId = o.getString("client_id"),
                redirectUri = o.getString("redirect_uri"),
                issuer = o.optString("issuer", ""),
                tokenEndpoint = o.getString("token_endpoint"),
                revocationEndpoint = o.optString("revocation_endpoint", "").ifBlank { null },
                resource = o.optString("resource", "").ifBlank { null },
                tokens = TokenSet(
                    o.getString("access_token"), o.optString("refresh_token", "").ifBlank { null },
                    o.optLong("expires_at", 0L), o.optString("scope", "").ifBlank { null },
                ),
                connectedAt = o.optLong("connected_at", 0L),
            )
        }.getOrNull()
    }
}

/** Where connections are kept (platform secret store in the app, a map in tests). */
interface ConnectionVault {
    fun load(provider: OrderProvider): Connection?
    fun save(provider: OrderProvider, connection: Connection?)
}

/** Opens the provider's sign-in page in the system browser and returns the redirect URL, or null if the user closed it. */
fun interface AuthBrowser {
    suspend fun authorize(url: String, redirectUri: String): String?
}

/**
 * Connect / fetch / disconnect for one set of providers. Pure common code (Ktor), driven by the app through
 * [OrderSync] and by tests through a fake server.
 */
class OrderConnector(
    private val http: HttpClient,
    private val vault: ConnectionVault,
    /** Debug fake server base URL (http allowed for its origin only); null in release builds. */
    private val fakeBase: String? = null,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val insecureOrigin = fakeBase?.let { runCatching { originOf(it) }.getOrNull() }
    private val oauth = OAuthClient(http, insecureOrigin, clock)
    private val refreshLock = Mutex()

    fun isConnected(provider: OrderProvider): Boolean = vault.load(provider) != null

    /** Full sign-in. Throws [OrderServiceException] with a user-presentable message. */
    suspend fun connect(provider: OrderProvider, browser: AuthBrowser): Connection {
        val endpoints = provider.endpoints(fakeBase)
        val discovery = oauth.discover(endpoints.first().url)
        val meta = discovery.authServer
        val scopes = chooseScopes(provider, meta.scopesSupported)
        val clientId = try {
            oauth.register(meta, REDIRECT_URI, scopes)
        } catch (e: OrderServiceException) {
            if (e.kind == OrderServiceException.Kind.REGISTRATION || e.kind == OrderServiceException.Kind.PROTOCOL) throw notOpenYet(provider)
            throw e
        }
        val pkce = Pkce.create()
        val state = randomState()
        val url = oauth.authorizationUrl(meta, clientId, REDIRECT_URI, scopes, pkce, state, discovery.resource)
        val callback = browser.authorize(url, REDIRECT_URI)
            ?: throw OrderServiceException(OrderServiceException.Kind.CANCELLED, "Sign-in was cancelled")
        val code = try {
            oauth.codeFromCallback(callback, state, meta)
        } catch (e: OrderServiceException) {
            // The service's own page refused this app (unauthorized_client, invalid_scope, …).
            if (e.message?.startsWith("Sign-in failed") == true) throw notOpenYet(provider)
            throw e
        }
        val tokens = oauth.exchangeCode(meta, clientId, REDIRECT_URI, code, pkce.verifier, discovery.resource)
        val conn = Connection(clientId, REDIRECT_URI, meta.issuer, meta.tokenEndpoint, meta.revocationEndpoint, discovery.resource, tokens, clock())
        vault.save(provider, conn)
        return conn
    }

    /** Revokes (best effort) and forgets the provider's tokens and client registration. */
    suspend fun disconnect(provider: OrderProvider) {
        val conn = vault.load(provider)
        vault.save(provider, null)
        if (conn?.revocationEndpoint != null) {
            conn.tokens.refreshToken?.let { oauth.revoke(conn.revocationEndpoint, conn.clientId, it, "refresh_token") }
            oauth.revoke(conn.revocationEndpoint, conn.clientId, conn.tokens.accessToken, "access_token")
        }
    }

    /** Orders placed since [sinceMs] from every endpoint of [provider]. Read-only tools only. */
    suspend fun fetchOrders(provider: OrderProvider, sinceMs: Long): List<ParsedOrder> {
        if (vault.load(provider) == null) throw OrderServiceException(OrderServiceException.Kind.AUTH, "not connected")
        val out = mutableListOf<ParsedOrder>()
        var lastError: OrderServiceException? = null
        for (ep in provider.endpoints(fakeBase)) {
            try {
                out += fetchFrom(provider, ep, sinceMs)
            } catch (e: OrderServiceException) {
                if (e.kind == OrderServiceException.Kind.AUTH) throw e
                lastError = e
            }
        }
        if (out.isEmpty() && lastError != null) throw lastError
        return out.filter { it.placedAt >= sinceMs - OrderMatcher.WINDOW_MS }
    }

    private suspend fun fetchFrom(provider: OrderProvider, ep: OrderEndpoint, sinceMs: Long): List<ParsedOrder> {
        val mcp = McpClient(http, requireSecureUrl(ep.url, insecureOrigin)) { force -> token(provider, force) }
        mcp.initialize()
        val tools = mcp.listTools()
        val history = tools.filter { ReadOnlyGuard.isHistoryTool(it) }
            .sortedByDescending { t -> ReadOnlyGuard.words(t.name).let { w -> (if ("history" in w) 2 else 0) + (if ("past" in w) 1 else 0) } }
            .firstOrNull { canFill(it) }
            ?: throw OrderServiceException(OrderServiceException.Kind.NOT_SUPPORTED, "${provider.displayName} doesn't share order history")
        val orders = OrderParser.parseToolResult(ep.source, mcp.callTool(history.name, argumentsFor(history, sinceMs))).toMutableList()
        // Lists often carry only totals: fill in items for the most recent few from a details tool, if there is one.
        val details = tools.firstOrNull { ReadOnlyGuard.isDetailsTool(it) }
        if (details != null) {
            val idParam = details.params.first { it.contains("order", true) && it.contains("id", true) }
            val missing = orders.withIndex().filter { it.value.items.isEmpty() && !it.value.cancelled && it.value.placedAt >= sinceMs }.take(10)
            for ((i, o) in missing) {
                val extra = runCatching {
                    OrderParser.parseToolResult(ep.source, mcp.callTool(details.name, JSONObject().put(idParam, o.orderId)))
                }.getOrNull()?.firstOrNull { it.orderId == o.orderId || it.items.isNotEmpty() } ?: continue
                orders[i] = o.copy(items = extra.items, merchant = o.merchant ?: extra.merchant)
            }
        }
        return orders
    }

    /** False when the tool requires an argument we can't supply. */
    private fun canFill(tool: McpTool): Boolean = tool.required.all { it in argumentsFor(tool, 0L).keySet() }

    private fun argumentsFor(tool: McpTool, sinceMs: Long): JSONObject {
        val args = JSONObject()
        val props = tool.inputSchema?.optJSONObject("properties")
        val sinceDays = (((clock() - sinceMs) / 86_400_000L) + 1).coerceIn(1, 90)
        val date = Instant.fromEpochMilliseconds(sinceMs).toLocalDateTime(TimeZone.of("Asia/Kolkata")).date.toString()
        for (name in tool.params) {
            val n = name.lowercase()
            val type = props?.optJSONObject(name)?.optString("type", "").orEmpty()
            when {
                n in setOf("limit", "page_size", "pagesize", "count", "max_results", "maxresults", "size", "per_page", "n") -> args.put(name, 50)
                n in setOf("days", "last_n_days", "lastndays", "num_days", "duration_days") -> args.put(name, sinceDays)
                n in setOf("from", "from_date", "fromdate", "start_date", "startdate", "since", "after", "start") ->
                    args.put(name, if (type == "integer" || type == "number") sinceMs / 1000 else date)
                n in setOf("page", "page_number", "pageno") -> args.put(name, 1)
                n in setOf("offset", "skip") -> args.put(name, 0)
            }
        }
        return args
    }

    /** Current access token, refreshing when it is about to expire or when [force] is set. */
    private suspend fun token(provider: OrderProvider, force: Boolean): String? = refreshLock.withLock {
        val conn = vault.load(provider) ?: return@withLock null
        val t = conn.tokens
        val stale = t.expiresAt != 0L && t.expiresAt - 60_000 < clock()
        if (!force && !stale) return@withLock t.accessToken
        val refresh = t.refreshToken ?: return@withLock null
        val fresh = try {
            oauth.refresh(conn.tokenEndpoint, conn.clientId, refresh, conn.resource)
        } catch (e: OrderServiceException) {
            if (e.message == "invalid_grant") vault.save(provider, null)
            throw OrderServiceException(OrderServiceException.Kind.AUTH, "sign-in expired")
        }
        vault.save(provider, conn.copy(tokens = fresh))
        fresh.accessToken
    }

    companion object {
        /** Custom URL scheme redirect (RFC 8252 §7.1): works with a free Apple ID and needs no associated domains. */
        const val REDIRECT_SCHEME = "com.jayant.spendsense"
        const val REDIRECT_URI = "$REDIRECT_SCHEME:/oauth/callback"

        /** The provider's own scopes, intersected with what the server lists (when it lists any). Never write scopes. */
        fun chooseScopes(provider: OrderProvider, supported: List<String>): List<String> {
            val wanted = provider.scopes.filter { s -> !s.contains("write") && !s.contains("manage") }
            return if (supported.isEmpty()) wanted else wanted.filter { it in supported }.ifEmpty { wanted }
        }

        fun notOpenYet(provider: OrderProvider) = OrderServiceException(
            OrderServiceException.Kind.REGISTRATION,
            "${provider.displayName} hasn't opened this to apps like SpendSense yet.",
        )
    }
}
