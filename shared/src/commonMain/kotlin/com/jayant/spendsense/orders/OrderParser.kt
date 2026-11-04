package com.jayant.spendsense.orders

import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.math.roundToLong
import kotlin.time.Instant

data class OrderItem(val name: String, val quantity: Int, val price: Double?)

/** One past order as read from a service. All strings already cleaned with [cleanText]. */
data class ParsedOrder(
    val source: OrderSource,
    val orderId: String,
    val merchant: String?,
    val items: List<OrderItem>,
    val total: Double,
    val placedAt: Long,
    val cancelled: Boolean = false,
)

/**
 * Turns an MCP tools/call result into orders. The services don't publish a schema, so this accepts the shapes such
 * APIs commonly use (structuredContent, JSON in text content, nested data/result wrappers, camel or snake case, amounts
 * as numbers, strings with ₹, or paise) plus a plain-text listing. Everything is treated as untrusted: depth and size
 * are capped, strings are cleaned, and anything unparseable is skipped rather than guessed.
 */
object OrderParser {
    private val IST = TimeZone.of("Asia/Kolkata")

    private val ORDER_LIST_KEYS = listOf("orders", "order_history", "orderHistory", "past_orders", "pastOrders", "items", "results", "data", "list", "records")
    private val WRAPPER_KEYS = listOf("data", "result", "response", "payload", "body", "order", "order_details", "orderDetails")
    private val ID_KEYS = listOf("order_id", "orderId", "id", "order_number", "orderNumber", "order_no", "orderNo")
    private val MERCHANT_KEYS = listOf("restaurant_name", "restaurantName", "restaurant", "store_name", "storeName", "store", "merchant_name", "merchant", "outlet_name", "outlet", "res_name", "brand", "vendor")
    private val ITEM_KEYS = listOf("items", "order_items", "orderItems", "dishes", "products", "line_items", "lineItems", "cart_items")
    private val ITEM_NAME_KEYS = listOf("name", "item_name", "itemName", "title", "dish_name", "product_name", "productName", "display_name")
    private val QTY_KEYS = listOf("quantity", "qty", "count", "units")
    private val PRICE_KEYS = listOf("total", "price", "amount", "final_price", "total_price", "cost")
    private val TOTAL_KEYS = listOf("total_amount", "totalAmount", "order_total", "orderTotal", "bill_total", "billTotal", "grand_total", "grandTotal", "net_amount", "netAmount", "amount_paid", "amountPaid", "paid_amount", "total", "amount", "final_amount", "order_amount", "price")
    private val PAISE_KEYS = listOf("total_paise", "amount_paise", "amount_in_paise", "totalPaise", "amountPaise", "amountInPaise")
    private val TIME_KEYS = listOf("placed_at", "placedAt", "order_time", "orderTime", "ordered_at", "orderedAt", "created_at", "createdAt", "order_date", "orderDate", "date", "time", "timestamp", "delivered_at")
    private val STATUS_KEYS = listOf("status", "order_status", "orderStatus", "state")

    /** Parses a tools/call `result` object. Returns at most [Limits.MAX_ORDERS] orders. */
    fun parseToolResult(source: OrderSource, result: JSONObject): List<ParsedOrder> {
        if (result.optBoolean("isError", false)) return emptyList()
        val out = mutableListOf<ParsedOrder>()
        result.optJSONObject("structuredContent")?.let { out += fromValue(source, it) }
        if (out.isEmpty()) {
            val content = result.optJSONArray("content") ?: JSONArray()
            for (i in 0 until content.length()) {
                val c = content.optJSONObject(i) ?: continue
                if (c.optString("type", "") != "text") continue
                val text = c.optString("text", "")
                val json = runCatching { safeJsonValue(text) }.getOrNull()
                out += if (json != null) fromValue(source, json) else parseText(source, text)
                if (out.size >= Limits.MAX_ORDERS) break
            }
        }
        return out.distinctBy { it.orderId }.take(Limits.MAX_ORDERS)
    }

    /** Orders from any JSON value: an array of orders, an object holding one, or a single order object. */
    fun fromValue(source: OrderSource, value: Any?, depth: Int = 0): List<ParsedOrder> {
        if (depth > 4) return emptyList()
        when (value) {
            is JSONArray -> return (0 until minOf(value.length(), Limits.MAX_ORDERS)).mapNotNull { value.optJSONObject(it)?.let { o -> order(source, o) } }
            is JSONObject -> {
                for (k in ORDER_LIST_KEYS) {
                    val arr = value.optJSONArray(k) ?: continue
                    val orders = fromValue(source, arr, depth + 1)
                    if (orders.isNotEmpty()) return orders
                }
                for (k in WRAPPER_KEYS) {
                    val inner = value.optJSONObject(k) ?: continue
                    val orders = fromValue(source, inner, depth + 1)
                    if (orders.isNotEmpty()) return orders
                }
                return listOfNotNull(order(source, value))
            }
            else -> return emptyList()
        }
    }

    private fun order(source: OrderSource, o: JSONObject): ParsedOrder? {
        val id = ID_KEYS.firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { it is String || it is Number }?.toString() }
            ?.let { cleanText(it, 64) } ?: return null
        val total = PAISE_KEYS.firstNotNullOfOrNull { k -> money(o.opt(k))?.div(100) }
            ?: TOTAL_KEYS.firstNotNullOfOrNull { k -> money(o.opt(k)) ?: o.optJSONObject(k)?.let { inner -> money(inner.opt("amount")) ?: money(inner.opt("value")) } }
            ?: o.optJSONObject("bill")?.let { b -> TOTAL_KEYS.firstNotNullOfOrNull { money(b.opt(it)) } }
            ?: return null
        if (total <= 0 || total > 1_000_000) return null
        val placed = TIME_KEYS.firstNotNullOfOrNull { k -> time(o.opt(k)) } ?: return null
        val merchant = MERCHANT_KEYS.firstNotNullOfOrNull { k ->
            when (val v = o.opt(k)) {
                is String -> cleanText(v)
                is JSONObject -> cleanText(v.optString("name", "").ifBlank { null })
                else -> null
            }
        }
        val items = ITEM_KEYS.firstNotNullOfOrNull { k -> o.optJSONArray(k) }?.let(::items).orEmpty()
        val status = STATUS_KEYS.firstNotNullOfOrNull { k -> o.opt(k) as? String }?.lowercase().orEmpty()
        return ParsedOrder(source, id, merchant, items, total, placed, cancelled = "cancel" in status || "fail" in status || "refund" in status)
    }

    private fun items(arr: JSONArray): List<OrderItem> = (0 until minOf(arr.length(), Limits.MAX_ITEMS)).mapNotNull { i ->
        when (val v = arr.opt(i)) {
            is String -> cleanText(v)?.let { OrderItem(it, 1, null) }
            is JSONObject -> {
                val name = ITEM_NAME_KEYS.firstNotNullOfOrNull { k -> cleanText(v.optString(k, "").ifBlank { null }) } ?: return@mapNotNull null
                val qty = QTY_KEYS.firstNotNullOfOrNull { k -> (v.opt(k) as? Number)?.toInt() ?: (v.opt(k) as? String)?.trim()?.toIntOrNull() }
                    ?.coerceIn(1, 999) ?: 1
                OrderItem(name, qty, PRICE_KEYS.firstNotNullOfOrNull { k -> money(v.opt(k)) })
            }
            else -> null
        }
    }

    /** 486, 486.5, "486", "₹486.00", "Rs. 1,234.50", "INR 99" → rupees. */
    fun money(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> Regex("(\\d[\\d,]*(?:\\.\\d{1,2})?)").find(v.replace(" ", " "))?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
        else -> null
    }?.takeIf { it >= 0 }

    /** Epoch seconds or ms, ISO-8601 with or without a zone (none = IST), "yyyy-MM-dd HH:mm[:ss]". */
    fun time(v: Any?): Long? = when (v) {
        is Number -> v.toLong().let { if (it in 1_000_000_000L..9_999_999_999L) it * 1000 else it }.takeIf { it > 1_000_000_000_000L && it < 9_999_999_999_999L }
        is String -> parseTimeString(v.trim())
        else -> null
    }

    private fun parseTimeString(s: String): Long? {
        if (s.isEmpty() || s.length > 40) return null
        s.toLongOrNull()?.let { return time(it) }
        runCatching { return Instant.parse(s).toEpochMilliseconds() }
        val normal = s.replace(' ', 'T').let { if (Regex("T\\d{2}:\\d{2}$").containsMatchIn(it)) "$it:00" else it }
        runCatching { return Instant.parse(normal).toEpochMilliseconds() }
        runCatching { return LocalDateTime.parse(normal.substringBefore('.')).toInstant(IST).toEpochMilliseconds() }
        return parseHumanDate(s)
    }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "24 Oct 2026, 12:30 PM" / "Oct 24, 2026 7:05 pm" (IST). */
    private fun parseHumanDate(s: String): Long? {
        val m1 = Regex("(\\d{1,2})\\s+([A-Za-z]{3})[a-z]*,?\\s+(\\d{4}),?\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?").find(s)
        val m2 = Regex("([A-Za-z]{3})[a-z]*\\s+(\\d{1,2}),?\\s+(\\d{4}),?\\s+(?:at\\s+)?(\\d{1,2}):(\\d{2})\\s*([AaPp][Mm])?").find(s)
        val (day, mon, year, hh, mm, ampm) = when {
            m1 != null -> m1.groupValues.let { listOf(it[1], it[2], it[3], it[4], it[5], it[6]) }
            m2 != null -> m2.groupValues.let { listOf(it[2], it[1], it[3], it[4], it[5], it[6]) }
            else -> return null
        }
        val month = MONTHS.indexOf(mon.lowercase().take(3)) + 1
        if (month == 0) return null
        var hour = hh.toInt()
        if (ampm.equals("pm", true) && hour < 12) hour += 12
        if (ampm.equals("am", true) && hour == 12) hour = 0
        return runCatching { LocalDateTime(year.toInt(), month, day.toInt(), hour, mm.toInt()).toInstant(IST).toEpochMilliseconds() }.getOrNull()
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    /**
     * Plain-text listing, one order per block:
     * "Order #123 · Punjabi Grill · ₹486 · 24 Oct 2026, 12:30 PM" followed by "- 2x Butter Naan" item lines.
     */
    fun parseText(source: OrderSource, rawText: String): List<ParsedOrder> {
        val text = com.jayant.spendsense.ingest.TextSafety.bound(rawText, 200_000)
        val out = mutableListOf<ParsedOrder>()
        var current: ParsedOrder? = null
        val items = mutableListOf<OrderItem>()
        fun flush() { current?.let { out += it.copy(items = items.toList()) }; current = null; items.clear() }
        for (raw in text.lineSequence().take(5000)) {
            val line = raw.trim()
            val head = Regex("(?i)order\\s*(?:id|#|no\\.?)?\\s*[:#]?\\s*([A-Za-z0-9-]{3,40})").find(line)
            val amount = Regex("(?:₹|Rs\\.?|INR)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)", RegexOption.IGNORE_CASE).find(line)
            if (head != null && amount != null && !line.startsWith("-") && !line.startsWith("*")) {
                flush()
                val placed = parseHumanDate(line) ?: Regex("\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}(:\\d{2})?([+-]\\d{2}:\\d{2}|Z)?").find(line)?.value?.let(::parseTimeString)
                val parts = line.split('·', '|', '—').map { it.trim() }
                val merchant = Regex("(?i)\\bfrom\\s+([^·|—₹]+)").find(line)?.groupValues?.get(1)?.trim()
                    ?: parts.getOrNull(1)?.takeIf { p -> !p.contains("₹") && !p.contains(Regex("\\d{4}")) }
                val total = money(amount.groupValues[1])
                if (placed != null && total != null && total > 0) {
                    current = ParsedOrder(source, cleanText(head.groupValues[1], 64)!!, cleanText(merchant), emptyList(), total, placed,
                        cancelled = line.contains("cancel", ignoreCase = true))
                }
                continue
            }
            val item = Regex("^[-*•]\\s*(?:(\\d{1,3})\\s*[x×]\\s*)?(.+?)(?:\\s*[x×]\\s*(\\d{1,3}))?(?:\\s*[-—:]?\\s*(?:₹|Rs\\.?)\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?))?$").find(line)
            if (item != null && current != null && items.size < Limits.MAX_ITEMS) {
                val name = cleanText(item.groupValues[2]) ?: continue
                val qty = (item.groupValues[1].ifEmpty { item.groupValues[3] }).toIntOrNull()?.coerceIn(1, 999) ?: 1
                items += OrderItem(name, qty, money(item.groupValues[4].ifEmpty { null }))
            }
            if (out.size >= Limits.MAX_ORDERS) break
        }
        flush()
        return out
    }

    /** Rounds to paise so totals compare cleanly. */
    fun paise(v: Double): Long = (v * 100).roundToLong()
}
