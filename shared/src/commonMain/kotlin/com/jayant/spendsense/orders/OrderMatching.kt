package com.jayant.spendsense.orders

import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType
import kotlin.math.abs

/**
 * Pairs fetched orders with payments: same provider (payee / app / message), amount within ₹1 or 2% (tips,
 * rounding, wallet top-ups), placed within ±3 hours. Closest in time wins; each order and each payment is used at
 * most once.
 */
object OrderMatcher {
    const val WINDOW_MS = 3L * 60 * 60 * 1000

    fun amountMatches(order: Double, paid: Double): Boolean = abs(order - paid) <= maxOf(1.0, order * 0.02)

    fun isCandidate(order: StoredOrder, t: Transaction): Boolean =
        t.type == TxnType.DEBIT &&
            !order.cancelled &&
            abs(t.timestamp - order.placedAt) <= WINDOW_MS &&
            amountMatches(order.total, t.amount) &&
            OrderProvider.forPayment(t) == OrderProvider.forSource(order.source)

    /** (order, transaction) pairs to link. [linkedTxnIds] are payments that already have an order. */
    fun match(orders: List<StoredOrder>, txns: List<Transaction>, linkedTxnIds: Set<Long> = emptySet()): List<Pair<StoredOrder, Transaction>> {
        val pairs = buildList {
            for (o in orders) if (o.txnId == null) for (t in txns) if (t.id !in linkedTxnIds && isCandidate(o, t)) add(o to t)
        }.sortedWith(compareBy({ abs(it.second.timestamp - it.first.placedAt) }, { abs(it.second.amount - it.first.total) }))
        val usedOrders = mutableSetOf<Long>()
        val usedTxns = linkedTxnIds.toMutableSet()
        return pairs.filter { (o, t) -> o.id !in usedOrders && t.id !in usedTxns && usedOrders.add(o.id) && usedTxns.add(t.id) }
    }
}

/** What a matched order writes onto its payment. */
object OrderPrefill {
    /** "Butter Chicken, 2× Butter Naan · Punjabi Grill" (max ~120 chars). */
    fun summary(order: StoredOrder): String {
        val shown = order.items.take(3).joinToString(", ") { if (it.quantity > 1) "${it.quantity}× ${it.name}" else it.name }
        val more = (order.items.size - 3).takeIf { it > 0 }?.let { " +$it more" }.orEmpty()
        val itemsPart = (shown + more).ifBlank { null }
        val text = listOfNotNull(itemsPart, order.merchant ?: order.source.label).joinToString(" · ")
        return if (text.length <= 120) text else text.take(119).trimEnd() + "…"
    }

    /**
     * [t] with the order's details filled in, never overwriting anything the user entered: the note only when empty,
     * the category only while the payment is unanswered, the payee only when it is just the delivery app's UPI handle.
     */
    fun apply(t: Transaction, order: StoredOrder): Transaction {
        val provider = OrderProvider.forSource(order.source)
        val payeeIsApp = t.counterparty.isNullOrBlank() || provider.payeeHints.any { t.counterparty.lowercase().contains(it) }
        return t.copy(
            note = t.note?.takeIf { it.isNotBlank() } ?: summary(order),
            category = if (!t.reviewed) order.source.category else t.category,
            counterparty = if (payeeIsApp && order.merchant != null) order.merchant else t.counterparty,
        )
    }
}
