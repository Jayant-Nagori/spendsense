package com.jayant.spendsense.orders

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ReentrantLock
import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.data.SqlDb
import com.jayant.spendsense.data.SqlRow
import com.jayant.spendsense.data.openDatabase
import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import com.jayant.spendsense.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An order as stored in order_details. [txnId] is null until it is matched to a payment. */
data class StoredOrder(
    val id: Long,
    val txnId: Long?,
    val source: OrderSource,
    val orderId: String,
    val merchant: String?,
    val items: List<OrderItem>,
    val total: Double,
    val placedAt: Long,
    val cancelled: Boolean,
)

/** order_details table. Blocking SQLite calls: use from an IO dispatcher. */
class OrderRepository internal constructor(private val db: SqlDb) {
    private val lock = ReentrantLock()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    /** Inserts new orders and refreshes unmatched ones (a match, once made, is never moved). Returns how many were new. */
    fun upsert(orders: List<ParsedOrder>): Int = lock.withLock {
        var added = 0
        db.transaction {
            for (o in orders) {
                val existing = db.query("SELECT id, txn_id FROM order_details WHERE source = ? AND order_id = ?", listOf(o.source.name, o.orderId)).firstOrNull()
                val values = linkedMapOf<String, Any?>(
                    "restaurant" to o.merchant,
                    "items_json" to itemsJson(o.items),
                    "total" to o.total,
                    "placed_at" to o.placedAt,
                    "cancelled" to if (o.cancelled) 1 else 0,
                    "fetched_at" to currentTimeMillis(),
                )
                if (existing == null) {
                    db.insert("order_details", values + mapOf("source" to o.source.name, "order_id" to o.orderId))
                    added++
                } else {
                    db.update("order_details", values, "id = ?", listOf(existing.long("id").toString()))
                }
            }
        }
        if (orders.isNotEmpty()) _changes.value++
        added
    }

    fun forTransaction(txnId: Long): StoredOrder? =
        db.query("SELECT * FROM order_details WHERE txn_id = ? LIMIT 1", listOf(txnId.toString())).firstOrNull()?.toOrder()

    /** Unmatched, not-cancelled orders placed in [from, to]. */
    fun unmatched(from: Long, to: Long): List<StoredOrder> =
        db.query(
            "SELECT * FROM order_details WHERE txn_id IS NULL AND cancelled = 0 AND placed_at BETWEEN ? AND ? ORDER BY placed_at DESC LIMIT 500",
            listOf(from.toString(), to.toString()),
        ).map { it.toOrder() }

    /** Orders between [from] and [to], newest first, optionally only from [sources]. For the assistant. */
    fun between(from: Long, to: Long, sources: Set<OrderSource>? = null): List<StoredOrder> =
        db.query("SELECT * FROM order_details WHERE placed_at BETWEEN ? AND ? AND cancelled = 0 ORDER BY placed_at DESC LIMIT 500", listOf(from.toString(), to.toString()))
            .map { it.toOrder() }.filter { sources == null || it.source in sources }

    /** Links [orderId] (row id) to [txnId] unless either side is already linked. Returns true on success. */
    fun assign(orderRowId: Long, txnId: Long): Boolean = lock.withLock {
        val ok = db.transaction {
            val taken = db.query("SELECT id FROM order_details WHERE txn_id = ?", listOf(txnId.toString())).isNotEmpty()
            if (taken) false else db.update("order_details", mapOf("txn_id" to txnId), "id = ? AND txn_id IS NULL", listOf(orderRowId.toString())) == 1
        }
        if (ok) _changes.value++
        ok
    }

    /** A transaction was deleted: its order becomes unmatched again. */
    fun detach(txnId: Long) {
        db.update("order_details", mapOf("txn_id" to null), "txn_id = ?", listOf(txnId.toString()))
        _changes.value++
    }

    private fun SqlRow.toOrder() = StoredOrder(
        id = long("id")!!,
        txnId = long("txn_id"),
        source = OrderSource.fromName(string("source")) ?: OrderSource.SWIGGY_FOOD,
        orderId = string("order_id").orEmpty(),
        merchant = string("restaurant"),
        items = parseItems(string("items_json")),
        total = double("total") ?: 0.0,
        placedAt = long("placed_at") ?: 0L,
        cancelled = int("cancelled") == 1,
    )

    companion object {
        @Volatile private var instance: OrderRepository? = null
        private val instanceLock = ReentrantLock()

        /** Recovery replaced the database: the next [get] opens the new one. */
        fun resetForRecovery() { instanceLock.withLock { instance = null } }

        fun get(context: PlatformContext): OrderRepository =
            instance ?: instanceLock.withLock { instance ?: OrderRepository(openDatabase(context)).also { instance = it } }

        fun itemsJson(items: List<OrderItem>): String = JSONArray().also { arr ->
            items.take(Limits.MAX_ITEMS).forEach { i ->
                arr.put(JSONObject().put("n", i.name).put("q", i.quantity).also { o -> i.price?.let { o.put("p", it) } })
            }
        }.toString()

        fun parseItems(json: String?): List<OrderItem> {
            val arr = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = cleanText(o.optString("n", "")) ?: return@mapNotNull null
                OrderItem(name, o.optInt("q", 1).coerceIn(1, 999), o.optDouble("p", Double.NaN).takeIf { !it.isNaN() })
            }
        }
    }
}
