package com.jayant.spendsense.orders

import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_792_800_000_000L // 2026-10-24 (approx), fixed for tests

private fun historyPayload(): JSONObject = FakeOrderServer.textResult(
    JSONObject().put("data", JSONObject().put("orders", JSONArray()
        .put(JSONObject().put("order_id", "Z-1001").put("restaurant_name", "Punjabi Grill").put("total_amount", "₹486.00")
            .put("placed_at", "2026-10-24T12:30:00+05:30")
            .put("items", JSONArray()
                .put(JSONObject().put("name", "Butter Chicken").put("quantity", 1).put("price", 320))
                .put(JSONObject().put("name", "Butter Naan").put("quantity", 2).put("price", 60))))
        .put(JSONObject().put("order_id", "Z-1000").put("restaurant_name", "Chai Point").put("total_amount", 120)
            .put("placed_at", "2026-10-23T09:00:00+05:30").put("status", "CANCELLED")),
    )).toString(),
)

class OrderFlowTest {

    private fun connector(server: FakeOrderServer, vault: MemoryVault = MemoryVault()) =
        OrderConnector(server.client, vault, fakeBase = server.base, clock = { NOW })

    @Test
    fun connectDiscoversRegistersAndExchangesWithPkce() = runTest {
        val server = FakeOrderServer()
        val vault = MemoryVault()
        val conn = connector(server, vault).connect(OrderProvider.ZOMATO, server.browser)

        // Discovery started from an unauthenticated MCP request answered with 401 + WWW-Authenticate.
        assertEquals(listOf("/zomato/mcp"), server.unauthenticatedProbes)
        // DCR as a public client with the custom-scheme redirect.
        val reg = server.registrations.single()
        assertEquals("none", reg.getString("token_endpoint_auth_method"))
        assertEquals(OrderConnector.REDIRECT_URI, reg.getJSONArray("redirect_uris").getString(0))
        // The server verified the PKCE S256 verifier against the challenge before issuing tokens.
        assertEquals(listOf("authorization_code"), server.tokenGrants)
        assertEquals("${server.base}/zomato/mcp", server.lastResource)
        assertEquals(conn, vault.load(OrderProvider.ZOMATO))
        assertTrue(conn.tokens.refreshToken!!.startsWith("rt"))
        assertEquals(NOW + 3_600_000, conn.tokens.expiresAt)
    }

    @Test
    fun zeptoAsksOnlyForReadScope() = runTest {
        val server = FakeOrderServer()
        connector(server).connect(OrderProvider.ZEPTO, server.browser)
        assertEquals("tools:read", server.lastAuthorizeScope)
        assertEquals("tools:read", server.registrations.single().getString("scope"))
    }

    @Test
    fun wrongIssuerInCallbackIsRejected() = runTest {
        val server = FakeOrderServer().apply { wrongIss = true }
        val vault = MemoryVault()
        val e = assertFailsWith<OrderServiceException> { connector(server, vault).connect(OrderProvider.ZOMATO, server.browser) }
        assertEquals(OrderServiceException.Kind.AUTH, e.kind)
        assertNull(vault.load(OrderProvider.ZOMATO))
        assertTrue(server.tokenGrants.isEmpty(), "code must not be exchanged")
    }

    @Test
    fun metadataForAnotherIssuerOrResourceIsRejected() = runTest {
        for (server in listOf(
            FakeOrderServer().apply { metadataIssuer = "https://evil.test/auth" },
            FakeOrderServer().apply { prmResource = "https://evil.test/mcp" },
        )) {
            val e = assertFailsWith<OrderServiceException> { connector(server).connect(OrderProvider.ZOMATO, server.browser) }
            assertTrue(e.kind == OrderServiceException.Kind.PROTOCOL || e.kind == OrderServiceException.Kind.REGISTRATION, e.kind.name)
            assertTrue(server.tokenGrants.isEmpty(), "no token may be requested")
        }
    }

    @Test
    fun missingIssuerWhenAdvertisedIsRejected() = runTest {
        val server = FakeOrderServer().apply { sendIss = false }
        assertFailsWith<OrderServiceException> { connector(server).connect(OrderProvider.ZOMATO, server.browser) }
        val lax = FakeOrderServer().apply { sendIss = false; advertiseIss = false }
        connector(lax).connect(OrderProvider.ZOMATO, lax.browser) // fine when the server never promised iss
    }

    @Test
    fun forgedStateIsRejected() = runTest {
        val server = FakeOrderServer()
        val forging = AuthBrowser { url, redirect -> server.browser.authorize(url, redirect)!!.replace(Regex("state=[^&]+"), "state=attacker") }
        val e = assertFailsWith<OrderServiceException> { connector(server).connect(OrderProvider.ZOMATO, forging) }
        assertEquals(OrderServiceException.Kind.AUTH, e.kind)
        assertTrue(server.tokenGrants.isEmpty())
    }

    @Test
    fun cancelledBrowserIsCancelled() = runTest {
        val server = FakeOrderServer()
        val e = assertFailsWith<OrderServiceException> { connector(server).connect(OrderProvider.SWIGGY, AuthBrowser { _, _ -> null }) }
        assertEquals(OrderServiceException.Kind.CANCELLED, e.kind)
    }

    @Test
    fun refusedRegistrationSaysServiceHasNotOpenedUp() = runTest {
        val server = FakeOrderServer().apply { rejectRegistration = true }
        val e = assertFailsWith<OrderServiceException> { connector(server).connect(OrderProvider.SWIGGY, server.browser) }
        assertEquals("Swiggy hasn't opened this to apps like SpendSense yet.", e.message)
    }

    @Test
    fun fetchesOrdersOverJsonUsingOnlyReadTools() = runTest {
        val server = FakeOrderServer().apply { ordersPayload = { historyPayload() } }
        val c = connector(server)
        c.connect(OrderProvider.ZOMATO, server.browser)
        val orders = c.fetchOrders(OrderProvider.ZOMATO, NOW - 30 * 86_400_000L)

        assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/call"), server.methods)
        assertEquals(listOf("get_order_history"), server.toolCalls)
        assertEquals(50, server.toolArgs.single().getInt("limit"))
        assertTrue(server.sessionIdsSeen.drop(1).all { it != null }, "Mcp-Session-Id is sent after initialize")
        val o = orders.first { it.orderId == "Z-1001" }
        assertEquals("Punjabi Grill", o.merchant)
        assertEquals(486.0, o.total)
        assertEquals(listOf(OrderItem("Butter Chicken", 1, 320.0), OrderItem("Butter Naan", 2, 60.0)), o.items)
        assertTrue(orders.first { it.orderId == "Z-1000" }.cancelled)
    }

    @Test
    fun fetchesOrdersOverSse() = runTest {
        val server = FakeOrderServer().apply { sse = true; ordersPayload = { historyPayload() } }
        val c = connector(server)
        c.connect(OrderProvider.SWIGGY, server.browser)
        val orders = c.fetchOrders(OrderProvider.SWIGGY, NOW - 30 * 86_400_000L)
        // Swiggy: Food and Instamart endpoints behind one sign-in.
        assertEquals(setOf(OrderSource.SWIGGY_FOOD, OrderSource.SWIGGY_INSTAMART), orders.map { it.source }.toSet())
        assertEquals(486.0, orders.first { it.source == OrderSource.SWIGGY_FOOD && it.orderId == "Z-1001" }.total)
    }

    @Test
    fun refreshesTokenOnceOn401() = runTest {
        val server = FakeOrderServer().apply { expireFirstAccessToken = true; ordersPayload = { historyPayload() } }
        val vault = MemoryVault()
        val c = connector(server, vault)
        c.connect(OrderProvider.ZOMATO, server.browser)
        val before = vault.load(OrderProvider.ZOMATO)!!.tokens
        val orders = c.fetchOrders(OrderProvider.ZOMATO, NOW - 86_400_000L * 30)
        assertTrue(orders.isNotEmpty())
        assertEquals(listOf("authorization_code", "refresh_token"), server.tokenGrants)
        val after = vault.load(OrderProvider.ZOMATO)!!.tokens
        assertTrue(after.accessToken != before.accessToken)
        assertEquals(before.refreshToken, after.refreshToken, "refresh token kept when the server doesn't rotate it")
    }

    @Test
    fun fillsMissingItemsFromDetailsTool() = runTest {
        val server = FakeOrderServer().apply {
            ordersPayload = { name ->
                if (name == "get_order_history") FakeOrderServer.textResult("""[{"orderId":"S9","storeName":"Instamart","amountPaise":24900,"orderTime":${NOW - 3_600_000}}]""")
                else JSONObject().put("structuredContent", JSONObject().put("order", JSONObject().put("id", "S9").put("total", 249)
                    .put("created_at", (NOW - 3_600_000) / 1000).put("products", JSONArray().put(JSONObject().put("product_name", "Amul Milk 1L").put("qty", "2")))))
            }
        }
        val c = connector(server)
        c.connect(OrderProvider.ZEPTO, server.browser)
        val o = c.fetchOrders(OrderProvider.ZEPTO, NOW - 86_400_000L).single()
        assertEquals(listOf("get_order_history", "get_order_details"), server.toolCalls)
        assertEquals("S9", server.toolArgs[1].getString("order_id"))
        assertEquals(listOf(OrderItem("Amul Milk 1L", 2, null)), o.items)
    }

    @Test
    fun disconnectRevokesAndForgets() = runTest {
        val server = FakeOrderServer()
        val vault = MemoryVault()
        val c = connector(server, vault)
        val conn = c.connect(OrderProvider.ZEPTO, server.browser)
        c.disconnect(OrderProvider.ZEPTO)
        assertNull(vault.load(OrderProvider.ZEPTO))
        assertEquals(listOf(conn.tokens.refreshToken, conn.tokens.accessToken), server.revoked)
    }

    @Test
    fun oversizedResponseIsRejected() = runTest {
        val server = FakeOrderServer().apply { rawMcpBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"pad\":\"" + "x".repeat(1_100_000) + "\"}}" }
        val c = connector(server)
        c.connect(OrderProvider.ZOMATO, server.browser)
        val e = assertFailsWith<OrderServiceException> { c.fetchOrders(OrderProvider.ZOMATO, NOW) }
        assertEquals(OrderServiceException.Kind.TOO_LARGE, e.kind)
    }

    @Test
    fun deeplyNestedResponseIsRejected() = runTest {
        val server = FakeOrderServer().apply { rawMcpBody = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":" + "[".repeat(5000) + "]".repeat(5000) + "}" }
        val c = connector(server)
        c.connect(OrderProvider.ZOMATO, server.browser)
        val e = assertFailsWith<OrderServiceException> { c.fetchOrders(OrderProvider.ZOMATO, NOW) }
        assertEquals(OrderServiceException.Kind.PROTOCOL, e.kind)
    }

    @Test
    fun serverWithOnlyWriteToolsIsNotSupported() = runTest {
        val server = FakeOrderServer().apply {
            tools = listOf(FakeOrderServer.tool("place_order", "Places an order"), FakeOrderServer.tool("update_order_history", "x"))
        }
        val c = connector(server)
        c.connect(OrderProvider.ZOMATO, server.browser)
        val e = assertFailsWith<OrderServiceException> { c.fetchOrders(OrderProvider.ZOMATO, NOW) }
        assertEquals(OrderServiceException.Kind.NOT_SUPPORTED, e.kind)
        assertTrue(server.toolCalls.isEmpty())
    }

    @Test
    fun mcpClientRefusesUnapprovedToolsBeforeSending() = runTest {
        val server = FakeOrderServer()
        val vault = MemoryVault()
        connector(server, vault).connect(OrderProvider.ZOMATO, server.browser)
        val mcp = McpClient(server.client, "${server.base}/zomato/mcp") { vault.load(OrderProvider.ZOMATO)?.tokens?.accessToken }
        mcp.initialize()
        assertFailsWith<ReadOnlyViolation> { mcp.callTool("get_order_history", JSONObject()) } // not listed yet: fail closed
        mcp.listTools()
        for (bad in listOf("place_order", "add_to_cart", "cancel_order", "make_payment", "search_restaurants", "made_up")) {
            assertFailsWith<ReadOnlyViolation> { mcp.callTool(bad, JSONObject()) }
        }
        assertTrue(server.toolCalls.isEmpty())
    }

    @Test
    fun wellKnownAndChallengeParsing() {
        assertEquals(
            listOf("https://mcp.swiggy.com/.well-known/oauth-protected-resource/food", "https://mcp.swiggy.com/.well-known/oauth-protected-resource"),
            OAuthClient.wellKnownPrm("https://mcp.swiggy.com/food"),
        )
        val c = OAuthClient.parseAuthChallenge("Bearer error=\"invalid_token\", resource_metadata=\"https://x/.well-known/oauth-protected-resource/mcp\", scope=mcp:tools")
        assertEquals("https://x/.well-known/oauth-protected-resource/mcp", c["resource_metadata"])
        assertEquals("mcp:tools", c["scope"])
    }

    @Test
    fun insecureMetadataUrlsAreRejected() {
        assertFailsWith<OrderServiceException> { requireSecureUrl("http://evil.test/token", null) }
        assertFailsWith<OrderServiceException> { requireSecureUrl("http://evil.test/token", "http://127.0.0.1:8787") }
        assertEquals("http://127.0.0.1:8787/x", requireSecureUrl("http://127.0.0.1:8787/x", "http://127.0.0.1:8787"))
        assertNotNull(requireSecureUrl("https://auth.zepto.co.in/token", null))
        assertFalse(OrderConnector.chooseScopes(OrderProvider.ZEPTO, listOf("tools:read", "tools:write")).contains("tools:write"))
    }
}
