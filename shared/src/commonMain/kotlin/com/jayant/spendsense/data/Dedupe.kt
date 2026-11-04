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
            if (existing.rawText == incoming.rawText) {
                return incoming.source != TxnSource.NOTIFICATION || abs(existing.timestamp - incoming.timestamp) <= NOTIFICATION_REDELIVERY_MS
            }
            // The row's text is another channel's (e.g. the bank SMS): compare with what this channel reported when it
            // was merged in, so a re-posted app notification doesn't become a second payment.
            val prefix = "h:${sourceKey(incoming)}:${fingerprint(incoming.rawText)}:"
            return seen.any { e ->
                e.startsWith(prefix) && e.substring(prefix.length).toLongOrNull()?.let { ts ->
                    incoming.source != TxnSource.NOTIFICATION || abs(ts - incoming.timestamp) <= NOTIFICATION_REDELIVERY_MS
                } == true
            }
        }
        return true
    }

    /** Stable 64-bit FNV-1a of the message text (never the text itself) for the seen-sources column. */
    fun fingerprint(text: String): String {
        var h = -0x340d631b7bdddcdbL
        for (c in text) { h = h xor c.code.toLong(); h *= 0x100000001b3L }
        return h.toULong().toString(16)
    }

    /** Seen-sources after [incoming] was merged into a row: its channel plus a text fingerprint (newest 12 kept). */
    fun withReport(seen: Set<String>, incoming: Transaction): Set<String> {
        val plain = seen.filterNot { it.startsWith("h:") }
        val echoes = seen.filter { it.startsWith("h:") }.takeLast(11) + "h:${sourceKey(incoming)}:${fingerprint(incoming.rawText)}:${incoming.timestamp}"
        return LinkedHashSet(plain + sourceKey(incoming) + echoes)
    }

    /**
     * Columns to copy onto [dup] from another report [incoming] of the same payment. A UPI-app notification rarely
     * names the bank; the bank SMS does, so the merged row takes bank, account, issuer and last 4 from whichever
     * report has them, and a specific instrument (e.g. a RuPay credit card on UPI) over a plain "UPI".
     */
    fun accountFill(dup: Transaction, incoming: Transaction): Map<String, Any?> = buildMap {
        if (dup.bank.isNullOrBlank() && !incoming.bank.isNullOrBlank()) put("bank", incoming.bank)
        if (dup.account.isNullOrBlank() && !incoming.account.isNullOrBlank()) put("account", incoming.account)
        if (dup.issuer == null && incoming.issuer != null) put("issuer", incoming.issuer)
        if (dup.last4 == null && incoming.last4 != null) put("last4", incoming.last4)
        val better = incoming.instrument != null && (dup.instrument == null ||
            (dup.instrument in setOf(Instrument.UPI, Instrument.OTHER) && incoming.instrument in setOf(Instrument.CREDIT_CARD, Instrument.DEBIT_CARD)))
        if (better) put("instrument", incoming.instrument!!.name)
        if (!dup.isTransfer && incoming.isTransfer) {
            put("is_transfer", 1); put("transfer_kind", incoming.transferKind?.name); put("reviewed", 1)
        }
    }
}
