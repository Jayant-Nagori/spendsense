package com.jayant.spendsense.data

import kotlin.math.abs

/** Pure rules for deciding whether an incoming transaction is another report of an already stored one. */
object Dedupe {
    // App notifications for two equal payments can have identical text ("Paid ₹1 to Ramesh"), so only a near-instant
    // repeat counts as redelivery. Bank SMS/emails/statements carry a ref or balance, so identical text is the same message.
    private const val NOTIFICATION_REDELIVERY_MS = 5_000L

    /** Capture channel: SMS, NOTIFICATION, or for imports the importer ("email", "statement"); "manual" for typed entries. */
    fun sourceKey(t: Transaction): String = when (t.source) {
        TxnSource.MANUAL -> t.sourceApp?.substringBefore(':')?.ifBlank { null } ?: "manual"
        else -> t.source.name
    }

    /**
     * [existing] is a stored row of the same type within the time window; [seen] are the channels that already
     * reported it. Different reference numbers never match, and a channel that already reported the row only matches
     * when it repeats the identical message (redelivery), otherwise it is a separate payment of the same amount.
     */
    fun isSamePayment(existing: Transaction, seen: Set<String>, incoming: Transaction): Boolean {
        if (existing.type != incoming.type || abs(existing.amount - incoming.amount) >= 0.005) return false
        val a = existing.upiRef?.takeIf { it.isNotBlank() }
        val b = incoming.upiRef?.takeIf { it.isNotBlank() }
        if (a != null && b != null) return a == b
        // A channel that already reported this row: only an exact redelivery (same text, same moment) is the same payment.
        if (sourceKey(incoming) in seen) {
            if (existing.rawText != incoming.rawText) return false
            return incoming.source != TxnSource.NOTIFICATION || abs(existing.timestamp - incoming.timestamp) <= NOTIFICATION_REDELIVERY_MS
        }
        return true
    }
}
