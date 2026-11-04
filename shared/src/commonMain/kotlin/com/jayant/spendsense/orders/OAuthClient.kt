package com.jayant.spendsense.orders

import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.http.formUrlEncode
import kotlin.coroutines.cancellation.CancellationException

/** RFC 8414 authorization server metadata (only the fields we use). */
data class AuthServerMetadata(
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val registrationEndpoint: String?,
    val revocationEndpoint: String?,
    val scopesSupported: List<String>,
    val issParameterSupported: Boolean,
)

/** Result of discovery for one MCP endpoint: the protected resource id and its authorization server. */
data class Discovery(val resource: String?, val authServer: AuthServerMetadata, val scopeHint: String?)

/** Access + refresh token. [expiresAt] is epoch ms (0 = unknown). Never logged. */
data class TokenSet(val accessToken: String, val refreshToken: String?, val expiresAt: Long, val scope: String?)

/**
 * OAuth 2.1 public client for the order services: discovery (401 → RFC 9728 → RFC 8414), dynamic client
 * registration (RFC 7591), authorization code + PKCE S256, refresh, revocation. The user signs in on the service's
 * own page in the system browser; this class never sees a password or OTP.
 */
class OAuthClient(
    private val http: HttpClient,
    /** Debug fake server origin allowed over http; null in release. */
    private val insecureOrigin: String? = null,
    private val clock: () -> Long = { com.jayant.spendsense.currentTimeMillis() },
) {

    suspend fun discover(mcpUrl: String): Discovery {
        requireSecureUrl(mcpUrl, insecureOrigin)
        // Unauthenticated MCP request: a protected server answers 401 with a pointer to its metadata.
        val probe = send { http.post(mcpUrl) {
            header("Accept", "application/json, text/event-stream")
            setBody(TextContent(McpClient.initializeRequest(0).toString(), ContentType.Application.Json))
        } }
        val challenge = probe.headers["WWW-Authenticate"]?.let(::parseAuthChallenge).orEmpty()
        if (probe.status.value != 401 && probe.status.value != 403) {
            throw OrderServiceException(OrderServiceException.Kind.NOT_SUPPORTED, "This service didn't ask for a sign-in")
        }
        val prm: JSONObject? = challenge["resource_metadata"]?.let { fetchJson(requireSecureUrl(it, insecureOrigin)) }
            ?: wellKnownPrm(mcpUrl).firstNotNullOfOrNull { runCatching { fetchJson(it) }.getOrNull() }
        val resource = prm?.optString("resource", "")?.ifBlank { null }
        // RFC 9728 §3.3: the metadata must describe the resource we asked about (same origin), not another server.
        if (resource != null && runCatching { originOf(resource) }.getOrNull() != originOf(mcpUrl)) {
            throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "The service's sign-in settings don't match it")
        }
        val issuer = prm?.optJSONArray("authorization_servers")?.optString(0, "")?.ifBlank { null } ?: originOf(mcpUrl)
        return Discovery(resource, authServerMetadata(requireSecureUrl(issuer, insecureOrigin)), challenge["scope"])
    }

    private suspend fun authServerMetadata(issuer: String): AuthServerMetadata {
        val u = Url(issuer)
        val path = u.encodedPath.trimEnd('/')
        val origin = originOf(issuer)
        val candidates = buildList {
            add("$origin/.well-known/oauth-authorization-server$path")
            if (path.isNotEmpty()) add("$origin/.well-known/openid-configuration$path")
            add("${issuer.trimEnd('/')}/.well-known/openid-configuration")
            if (path.isNotEmpty()) add("$origin/.well-known/oauth-authorization-server")
        }.distinct()
        val json = candidates.firstNotNullOfOrNull { runCatching { fetchJson(it) }.getOrNull() }
            ?: throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "Couldn't find the service's sign-in settings")
        val methods = json.optJSONArray("code_challenge_methods_supported")?.strings()
        if (methods != null && "S256" !in methods) {
            throw OrderServiceException(OrderServiceException.Kind.NOT_SUPPORTED, "The service doesn't support secure app sign-in (PKCE)")
        }
        // RFC 8414 §3.3: the issuer in the document must be the one we looked up (blocks metadata substitution /
        // issuer mix-up, and makes the later `iss` check meaningful).
        val claimed = json.optString("issuer", "").trimEnd('/')
        if (claimed.isNotEmpty() && claimed != issuer.trimEnd('/')) {
            throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "The service's sign-in settings don't match it")
        }
        fun endpoint(key: String): String? = json.optString(key, "").ifBlank { null }?.let { requireSecureUrl(it, insecureOrigin) }
        return AuthServerMetadata(
            issuer = json.optString("issuer", issuer).ifBlank { issuer },
            authorizationEndpoint = endpoint("authorization_endpoint")
                ?: throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "Sign-in settings are incomplete"),
            tokenEndpoint = endpoint("token_endpoint")
                ?: throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "Sign-in settings are incomplete"),
            registrationEndpoint = endpoint("registration_endpoint"),
            revocationEndpoint = endpoint("revocation_endpoint"),
            scopesSupported = json.optJSONArray("scopes_supported")?.strings().orEmpty(),
            issParameterSupported = json.optBoolean("authorization_response_iss_parameter_supported", false),
        )
    }

    /** RFC 7591 registration as a public client (no secret). Returns the client_id. */
    suspend fun register(meta: AuthServerMetadata, redirectUri: String, scopes: List<String>): String {
        val endpoint = meta.registrationEndpoint
            ?: throw OrderServiceException(OrderServiceException.Kind.REGISTRATION, "This service doesn't accept new apps")
        val body = JSONObject()
            .put("client_name", "SpendSense")
            .put("redirect_uris", JSONArray().put(redirectUri))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
            .put("application_type", "native")
        if (scopes.isNotEmpty()) body.put("scope", scopes.joinToString(" "))
        val resp = send { http.post(endpoint) {
            header("Accept", "application/json")
            setBody(TextContent(body.toString(), ContentType.Application.Json))
        } }
        val text = resp.limitedText()
        if (resp.status.value !in 200..299) {
            val err = safeJsonObject(text)?.optString("error", "").orEmpty()
            throw OrderServiceException(
                OrderServiceException.Kind.REGISTRATION,
                if (err == "invalid_redirect_uri") "invalid_redirect_uri" else "registration refused (${resp.status.value})",
            )
        }
        val json = safeJsonObject(text) ?: throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "Unreadable registration response")
        return json.optString("client_id", "").ifBlank { null }
            ?: throw OrderServiceException(OrderServiceException.Kind.REGISTRATION, "registration returned no client id")
    }

    fun authorizationUrl(
        meta: AuthServerMetadata, clientId: String, redirectUri: String, scopes: List<String>,
        pkce: Pkce, state: String, resource: String?,
    ): String = URLBuilder(meta.authorizationEndpoint).apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", clientId)
        parameters.append("redirect_uri", redirectUri)
        parameters.append("code_challenge", pkce.challenge)
        parameters.append("code_challenge_method", "S256")
        parameters.append("state", state)
        if (scopes.isNotEmpty()) parameters.append("scope", scopes.joinToString(" "))
        if (resource != null) parameters.append("resource", resource)
    }.buildString()

    /**
     * Validates the redirect the browser came back with and returns the authorization code. Rejects a wrong
     * state (CSRF), a wrong issuer (mix-up, RFC 9207) and error responses.
     */
    fun codeFromCallback(
        callbackUrl: String, expectedState: String, meta: AuthServerMetadata,
        redirectUri: String = OrderConnector.REDIRECT_URI,
    ): String {
        if (!isOurRedirect(callbackUrl, redirectUri)) {
            throw OrderServiceException(OrderServiceException.Kind.AUTH, "Sign-in response didn't match this request")
        }
        val p = runCatching { Url(callbackUrl).parameters }.getOrNull()
            ?: throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "Bad sign-in response")
        // Exactly one of each security-relevant parameter: no "state=a&state=b" / duplicated code smuggling.
        for (name in listOf("state", "code", "iss", "error")) {
            if ((p.getAll(name)?.size ?: 0) > 1) {
                throw OrderServiceException(OrderServiceException.Kind.AUTH, "Sign-in response didn't match this request")
            }
        }
        if (expectedState.isBlank() || p["state"] != expectedState) throw OrderServiceException(OrderServiceException.Kind.AUTH, "Sign-in response didn't match this request")
        p["error"]?.let { err ->
            throw OrderServiceException(
                if (err == "access_denied") OrderServiceException.Kind.CANCELLED else OrderServiceException.Kind.AUTH,
                if (err == "access_denied") "Sign-in was cancelled" else "Sign-in failed ($err)".take(80),
            )
        }
        val iss = p["iss"]
        if (iss != null && iss.trimEnd('/') != meta.issuer.trimEnd('/')) {
            throw OrderServiceException(OrderServiceException.Kind.AUTH, "Sign-in response came from the wrong server")
        }
        if (iss == null && meta.issParameterSupported) {
            throw OrderServiceException(OrderServiceException.Kind.AUTH, "Sign-in response is missing its issuer")
        }
        return p["code"]?.takeIf { it.isNotBlank() } ?: throw OrderServiceException(OrderServiceException.Kind.AUTH, "Sign-in returned no code")
    }

    suspend fun exchangeCode(
        meta: AuthServerMetadata, clientId: String, redirectUri: String, code: String, verifier: String, resource: String?,
    ): TokenSet = tokenRequest(meta.tokenEndpoint, buildList {
        add("grant_type" to "authorization_code")
        add("code" to code)
        add("redirect_uri" to redirectUri)
        add("client_id" to clientId)
        add("code_verifier" to verifier)
        if (resource != null) add("resource" to resource)
    }, previousRefresh = null)

    suspend fun refresh(tokenEndpoint: String, clientId: String, refreshToken: String, resource: String?): TokenSet =
        tokenRequest(tokenEndpoint, buildList {
            add("grant_type" to "refresh_token")
            add("refresh_token" to refreshToken)
            add("client_id" to clientId)
            if (resource != null) add("resource" to resource)
        }, previousRefresh = refreshToken)

    /** Best-effort RFC 7009 revocation; failures are ignored (tokens are deleted locally either way). */
    suspend fun revoke(revocationEndpoint: String, clientId: String, token: String, hint: String) {
        runCatching {
            http.post(revocationEndpoint) {
                setBody(TextContent(listOf("token" to token, "token_type_hint" to hint, "client_id" to clientId).formUrlEncode(), ContentType.Application.FormUrlEncoded))
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun tokenRequest(endpoint: String, form: List<Pair<String, String>>, previousRefresh: String?): TokenSet {
        val resp = send { http.post(endpoint) {
            header("Accept", "application/json")
            setBody(TextContent(form.formUrlEncode(), ContentType.Application.FormUrlEncoded))
        } }
        val text = resp.limitedText()
        val json = safeJsonObject(text)
        if (resp.status.value !in 200..299 || json == null) {
            val err = json?.optString("error", "").orEmpty()
            throw OrderServiceException(
                OrderServiceException.Kind.AUTH,
                if (err == "invalid_grant") "invalid_grant" else "token request failed (${resp.status.value})",
            )
        }
        val access = json.optString("access_token", "").ifBlank { null }
            ?: throw OrderServiceException(OrderServiceException.Kind.AUTH, "token response had no access token")
        val type = json.optString("token_type", "Bearer")
        if (!type.equals("bearer", ignoreCase = true)) throw OrderServiceException(OrderServiceException.Kind.AUTH, "unsupported token type")
        val expiresIn = json.optLong("expires_in", 0L)
        return TokenSet(
            accessToken = access,
            refreshToken = json.optString("refresh_token", "").ifBlank { null } ?: previousRefresh,
            expiresAt = if (expiresIn > 0) clock() + expiresIn * 1000 else 0L,
            scope = json.optString("scope", "").ifBlank { null },
        )
    }

    private suspend fun fetchJson(url: String): JSONObject {
        val resp = send { http.get(url) { header("Accept", "application/json") } }
        if (resp.status.value !in 200..299) throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "metadata ${resp.status.value}")
        return safeJsonObject(resp.limitedText()) ?: throw OrderServiceException(OrderServiceException.Kind.PROTOCOL, "metadata unreadable")
    }

    private suspend fun send(block: suspend () -> HttpResponse): HttpResponse = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: OrderServiceException) {
        throw e
    } catch (e: Exception) {
        throw OrderServiceException(OrderServiceException.Kind.NETWORK, "Couldn't reach the service")
    }

    companion object {
        /**
         * True only for `<scheme>:/oauth/callback?…` exactly as registered: same scheme (case-insensitive), no host /
         * authority, same path, no fragment, bounded length. Anything else (other paths, `scheme://evil/…`,
         * `#code=` fragments) is not our redirect and is ignored.
         */
        fun isOurRedirect(url: String, redirectUri: String): Boolean {
            if (url.length > 8_192 || url.any { it.isWhitespace() || it.code < 0x20 }) return false
            val scheme = redirectUri.substringBefore(':')
            val expectedPath = redirectUri.substringAfter(':')
            val colon = url.indexOf(':')
            if (colon <= 0 || !url.substring(0, colon).equals(scheme, ignoreCase = true)) return false
            if ('#' in url) return false
            val rest = url.substring(colon + 1)
            val path = rest.substringBefore('?')
            return path == expectedPath
        }

        /** RFC 9728 well-known locations for [mcpUrl]: path-inserted first, then the host root. */
        fun wellKnownPrm(mcpUrl: String): List<String> {
            val u = Url(mcpUrl)
            val origin = originOf(mcpUrl)
            val path = u.encodedPath.trimEnd('/')
            return listOfNotNull(
                if (path.isNotEmpty()) "$origin/.well-known/oauth-protected-resource$path" else null,
                "$origin/.well-known/oauth-protected-resource",
            )
        }

        /** Parses `Bearer resource_metadata="…", scope="…", error="…"` into lower-cased keys. */
        fun parseAuthChallenge(header: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            Regex("([A-Za-z_]+)=(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s,]+))").findAll(header).forEach { m ->
                val v = m.groupValues[2].ifEmpty { m.groupValues[3] }.replace("\\\"", "\"")
                out.getOrPut(m.groupValues[1].lowercase()) { v }
            }
            return out
        }
    }
}

internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { i -> optString(i, "").ifBlank { null } }
