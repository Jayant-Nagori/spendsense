package com.jayant.spendsense.ingest

import com.jayant.spendsense.data.ParsedTransaction

// OWNER: ingestion agent. Pure Kotlin (no android.* imports) so it is unit-testable on the JVM.
object TransactionParser {
    /** Parses a bank/UPI SMS. Returns null for OTPs, promos, balance alerts, failed/declined txns, etc. */
    fun parseSms(sender: String, body: String, timestamp: Long): ParsedTransaction? = null

    /** Parses a payment-app notification (GPay, PhonePe, Paytm, bank apps...). Null if not a completed payment. */
    fun parseNotification(packageName: String, title: String?, text: String, timestamp: Long): ParsedTransaction? = null
}
