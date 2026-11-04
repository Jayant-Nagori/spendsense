package com.jayant.spendsense.orders

import com.jayant.spendsense.data.Transaction

/** One MCP endpoint of a provider (Swiggy has two: Food and Instamart, behind one sign-in). */
data class OrderEndpoint(val source: OrderSource, val url: String)

/** Where an order came from. Stored in order_details.source; never rename. */
enum class OrderSource(val label: String, val category: String) {
    ZOMATO("Zomato", "Food & Dining"),
    SWIGGY_FOOD("Swiggy", "Food & Dining"),
    SWIGGY_INSTAMART("Instamart", "Groceries"),
    ZEPTO("Zepto", "Groceries"),
    ;

    companion object {
        fun fromName(name: String?): OrderSource? = entries.firstOrNull { it.name == name }
    }
}

/**
 * A service the user can connect in Settings › Connected apps. Endpoints are the official MCP servers; OAuth
 * metadata is always discovered at runtime (RFC 9728 / RFC 8414), never hard-coded.
 */
enum class OrderProvider(
    val displayName: String,
    val subtitle: String,
    private val realEndpoints: List<Pair<OrderSource, String>>,
    /** Scopes to ask for. Zepto offers write scopes too; we only ever ask for read. */
    val scopes: List<String>,
    /** Payee / descriptor fragments that identify this provider's payments. */
    val payeeHints: List<String>,
) {
    ZOMATO(
        "Zomato", "Food delivery",
        listOf(OrderSource.ZOMATO to "https://mcp-server.zomato.com/mcp"),
        listOf("mcp:tools"),
        listOf("zomato", "zomatoonline", "zomatoltd", "eternal"),
    ),
    SWIGGY(
        "Swiggy", "Food + Instamart",
        listOf(OrderSource.SWIGGY_FOOD to "https://mcp.swiggy.com/food", OrderSource.SWIGGY_INSTAMART to "https://mcp.swiggy.com/im"),
        listOf("mcp:tools"),
        listOf("swiggy", "bundl", "instamart", "scootsy"),
    ),
    ZEPTO(
        "Zepto", "Groceries",
        listOf(OrderSource.ZEPTO to "https://mcp.zepto.co.in/mcp"),
        listOf("tools:read"),
        listOf("zepto", "kiranakart", "kirana kart", "geddit", "zeptonow"),
    ),
    ;

    /** Real endpoints, or the debug fake server's equivalents when [fakeBase] is set (debug builds only). */
    fun endpoints(fakeBase: String?): List<OrderEndpoint> = realEndpoints.map { (source, url) ->
        OrderEndpoint(source, if (fakeBase == null) url else fakeBase.trimEnd('/') + "/" + name.lowercase() + "/" + url.substringAfterLast('/'))
    }

    val sources: List<OrderSource> get() = realEndpoints.map { it.first }

    companion object {
        fun forSource(source: OrderSource): OrderProvider = entries.first { source in it.sources }

        fun fromName(name: String?): OrderProvider? = entries.firstOrNull { it.name == name }

        /** Package names of the providers' own apps (payment notifications posted by them). */
        private val PACKAGES = mapOf(
            "in.swiggy.android" to SWIGGY,
            "com.application.zomato" to ZOMATO,
            "com.zeptoconsumerapp" to ZEPTO,
        )

        /** Which provider a payment looks like it went to, from its payee, source app and message. */
        fun forPayment(t: Transaction): OrderProvider? {
            PACKAGES[t.sourceApp]?.let { return it }
            val hay = listOfNotNull(t.counterparty, t.rawText).joinToString(" ").lowercase()
            return entries.firstOrNull { p -> p.payeeHints.any { hay.contains(it) } }
        }
    }
}
