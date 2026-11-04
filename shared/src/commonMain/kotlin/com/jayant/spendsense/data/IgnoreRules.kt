package com.jayant.spendsense.data

import com.jayant.spendsense.ingest.TextSafety

/**
 * "Not a payment" rules: the sender plus a fingerprint of the message's template (lower-cased, amounts / numbers and
 * UPI ids blanked). Similar alerts from that sender are skipped from then on. Only the fingerprint is stored, never
 * the text.
 */
object IgnoreRules {
    private val VPA = Regex("[a-z0-9._\\-]{2,}@[a-z]{2,}")
    private val NUMBER = Regex("[0-9][0-9,.:/\\-]*")
    private val NON_WORD = Regex("[^a-z#@₹]+")

    /** SMS sender without its operator / route prefix and suffix ("VM-HDFCBK-S" → "HDFCBK"); package for notifications. */
    fun senderKey(source: TxnSource, sourceApp: String?): String? {
        val s = sourceApp?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when (source) {
            TxnSource.SMS -> {
                val parts = s.uppercase().split('-').filter { it.isNotBlank() }
                (if (parts.size >= 2) parts[1] else parts.firstOrNull())?.take(20)
            }
            TxnSource.NOTIFICATION -> s.take(120)
            TxnSource.MANUAL -> null
        }
    }

    fun template(text: String): String =
        TextSafety.bound(text, 600).lowercase().replace(VPA, "@").replace(NUMBER, "#").replace(NON_WORD, " ").trim().take(300)

    fun hash(text: String): String = Dedupe.fingerprint(template(text))
}
