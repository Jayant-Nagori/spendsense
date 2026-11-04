package com.jayant.spendsense.security

import com.jayant.spendsense.ai.AssistantEngine
import com.jayant.spendsense.ai.NOW
import com.jayant.spendsense.notify.PopupQueue
import com.jayant.spendsense.orders.AuthServerMetadata
import com.jayant.spendsense.orders.McpTool
import com.jayant.spendsense.orders.OAuthClient
import com.jayant.spendsense.orders.OrderConnector
import com.jayant.spendsense.orders.OrderServiceException
import com.jayant.spendsense.orders.ReadOnlyGuard
import com.jayant.spendsense.platform.DebugGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression tests for SECURITY_REVIEW.md findings (MCP guard, OAuth redirect, integrity latch, pop-up flood, AI). */
class SecurityHardeningTest {

    // ---- MCP read-only guard bypass attempts ------------------------------------------------------------------

    private fun t(name: String, desc: String = "Your past orders", ro: Boolean? = null, destructive: Boolean? = null) =
        McpTool(name, desc, null, ro, destructive)

    @Test fun guardStillAllowsRealHistoryTools() {
        listOf("get_order_history", "getOrderHistory", "list_orders", "get_order_details", "fetch-orders", "orders.history",
            "get_instamart_orders", "GET_ORDER_HISTORY", "get_food_orders", "list_past_orders_v2").forEach {
            assertTrue(ReadOnlyGuard.isAllowed(t(it)), it)
        }
        assertTrue(ReadOnlyGuard.isAllowed(t("get_order_history", "Past orders, including cancelled ones and payment method used")))
    }

    @Test fun guardRejectsHomoglyphAndInvisibleCharacterNames() {
        listOf(
            "get_orders_and_раy", // Cyrillic р а: "pay" in disguise
            "рlace_order", "get_order​_history", "get_order_history‮", "ｇｅｔ_ｏｒｄｅｒｓ", "get orders",
            "get_orders;pay", "get_orders/checkout", "get_order_history\n", "",
        ).forEach { assertFalse(ReadOnlyGuard.isAllowed(t(it)), it) }
    }

    @Test fun guardRejectsCaseSeparatorAndNovelVerbTricks() {
        listOf(
            "PLACE_ORDER", "PlaceOrder", "PLACEORDER", "place.order", "place-order", "Place__Order", "pl_ace_order",
            "get_orders_and_settle", "make_my_order", "repeat_last_order", "buy_again_orders", "get_orders_then_purchase",
            "order_now", "get_order_and_checkout", "get_my_orders_and_reorder", "do_my_order", "list_orders_charge",
            "get_orders_transfer", "get_orders_v2_upsert", "authorize_orders", "get-ORDERS-PAY",
        ).forEach { assertFalse(ReadOnlyGuard.isAllowed(t(it)), it) }
    }

    @Test fun round2GuardRejectsOrderAsAVerb() {
        listOf("order_my_food", "order_my_items", "order_all_items", "order_the_items", "my_order_food", "orders_for_user",
            "history_order", "all_orders_now").forEach { assertFalse(ReadOnlyGuard.isAllowed(t(it)), it) }
        assertTrue(ReadOnlyGuard.isAllowed(t("my_orders")))
        assertTrue(ReadOnlyGuard.isAllowed(t("get_my_orders")))
    }

    @Test fun round2OtpVariants() {
        listOf(
            "123456 is your Amazon Pay code to pay Rs 500",
            "Your 0TP is 482913",
            "O T P 482913 for login",
            "482913 is the Paytm code for your payment",
        ).forEach { assertTrue(com.jayant.spendsense.ingest.OtpClassifier.isOtp(it), it) }
        assertFalse(com.jayant.spendsense.ingest.OtpClassifier.isOtp("Rs 1234 is your total spend at Swiggy this month"))
        assertFalse(com.jayant.spendsense.ingest.OtpClassifier.isOtp("Rs 500 debited, UPI Ref 529512345678. Your PIN is never needed to receive money"))
    }

    @Test fun round2AiStatementRepliesAreCapped() {
        val row = """{"date":"2026-10-22","description":"X","amount":1,"type":"debit","ref":""}"""
        val reply = "[" + List(5000) { row }.joinToString(",") + "]"
        assertEquals(com.jayant.spendsense.imports.statement.AiStatementParser.MAX_ROWS_PER_REPLY,
            com.jayant.spendsense.imports.statement.AiStatementParser.parseReply(reply).size)
    }

    @Test fun guardReadsDescriptionsThroughLookalikes() {
        listOf(
            "Plаces an order for you", // Cyrillic а
            "Places​ an order",
            "Ｐｌａｃｅｓ ａｎ ｏｒｄｅｒ",
            "Buys the items in your basket",
            "Repeats your last order",
            "Re-orders what you had last time",
            "Charges your saved card",
            "Proceeds to check-out",
        ).forEach { assertFalse(ReadOnlyGuard.isAllowed(t("get_orders", it)), it) }
    }

    @Test fun annotationsOnlyTighten() {
        assertFalse(ReadOnlyGuard.isAllowed(t("get_order_history", destructive = true)))
        assertFalse(ReadOnlyGuard.isAllowed(t("get_order_history", ro = false)))
        assertFalse(ReadOnlyGuard.isAllowed(t("place_order", "Read your order history", ro = true)))
    }

    // ---- OAuth redirect / state / issuer --------------------------------------------------------------------

    private val meta = AuthServerMetadata(
        issuer = "https://auth.example.test", authorizationEndpoint = "https://auth.example.test/authorize",
        tokenEndpoint = "https://auth.example.test/token", registrationEndpoint = null, revocationEndpoint = null,
        scopesSupported = emptyList(), issParameterSupported = true,
    )
    private val oauth = OAuthClient(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }))
    private val redirect = OrderConnector.REDIRECT_URI
    private val ok = "$redirect?code=abc&state=S1&iss=https%3A%2F%2Fauth.example.test"

    @Test fun acceptsOnlyTheExactRedirect() {
        assertEquals("abc", oauth.codeFromCallback(ok, "S1", meta))
        listOf(
            "evil:/oauth/callback?code=abc&state=S1&iss=https%3A%2F%2Fauth.example.test",
            "com.jayant.spendsense://evil.test/oauth/callback?code=abc&state=S1",
            "com.jayant.spendsense:/oauth/callbackX?code=abc&state=S1",
            "com.jayant.spendsense:/other?code=abc&state=S1",
            "com.jayant.spendsense:/oauth/callback#code=abc&state=S1",
            "com.jayant.spendsense:/oauth/callback?code=abc&state=S1&iss=https%3A%2F%2Fauth.example.test\n",
            "https://auth.example.test/oauth/callback?code=abc&state=S1",
        ).forEach { url ->
            assertFailsWith<OrderServiceException>(url) { oauth.codeFromCallback(url, "S1", meta) }
            assertFalse(OAuthClient.isOurRedirect(url, redirect), url)
        }
        assertTrue(OAuthClient.isOurRedirect("COM.JAYANT.SPENDSENSE:/oauth/callback?code=x", redirect))
    }

    @Test fun rejectsStateAndCodeSmuggling() {
        listOf(
            "$redirect?code=abc&state=WRONG&iss=https%3A%2F%2Fauth.example.test",
            "$redirect?code=abc&iss=https%3A%2F%2Fauth.example.test",
            "$redirect?code=abc&state=S1&state=ATTACKER&iss=https%3A%2F%2Fauth.example.test",
            "$redirect?code=abc&code=evil&state=S1&iss=https%3A%2F%2Fauth.example.test",
            "$redirect?code=abc&state=S1&iss=https%3A%2F%2Fevil.test", // issuer mix-up
            "$redirect?code=abc&state=S1", // issuer required when advertised
            "$redirect?code=abc&state=S1&iss=https%3A%2F%2Fauth.example.test&iss=https%3A%2F%2Fevil.test",
        ).forEach { url -> assertFailsWith<OrderServiceException>(url) { oauth.codeFromCallback(url, "S1", meta) } }
        // An empty expected state never matches (no "state=" bypass).
        assertFailsWith<OrderServiceException> { oauth.codeFromCallback("$redirect?code=abc&state=&iss=https%3A%2F%2Fauth.example.test", "", meta) }
    }

    // ---- integrity latch --------------------------------------------------------------------------------------

    @Test fun tamperedInstallNeverOpensStorage() {
        try {
            AppIntegrity.requireGenuine()
            AppIntegrity.markTampered()
            assertTrue(AppIntegrity.tampered)
            assertFailsWith<IntegrityException> { AppIntegrity.requireGenuine() }
        } finally {
            AppIntegrity.resetForTests()
        }
    }

    @Test fun debugGateIsOffWithoutAPlatform() = assertFalse(DebugGate.enabled())

    // ---- pop-up queue flood -----------------------------------------------------------------------------------

    @Test fun popupQueueFloodIsBoundedAndDeduplicated() {
        val q = PopupQueue(capacity = 20)
        repeat(10_000) { q.offer((it % 600).toLong()) }
        assertEquals(20, q.size)
        val drained = generateSequence { q.poll() }.toList()
        // The newest 20 distinct ids survive (the last 600 offers were 400..599 then 0..399).
        assertEquals((380L until 400L).toList(), drained)
        assertEquals(null, q.poll())
    }

    // ---- prompt injection ---------------------------------------------------------------------------------------

    @Test fun systemPromptTreatsMessageAndOrderTextAsData() {
        val p = AssistantEngine.systemPrompt(NOW)
        assertTrue(p.contains("untrusted data"))
        assertTrue(p.contains("order items"))
        assertTrue(p.contains("never ask the user for passwords, OTPs, PINs, CVVs or card numbers"))
    }
}
