package com.jayant.spendsense.orders

import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionTest {
    @Test
    fun roundTrips() {
        val c = Connection("cid", OrderConnector.REDIRECT_URI, "https://a/auth", "https://a/token", null, "https://a/mcp",
            TokenSet("at", "rt", 1_792_800_000_000L, null), 5L)
        assertEquals(c, Connection.fromJson(c.toJson()))
        val noRefresh = c.copy(tokens = TokenSet("at", null, 0L, "mcp:tools"), resource = null)
        assertEquals(noRefresh, Connection.fromJson(noRefresh.toJson()))
    }
}
