package com.jayant.spendsense.data

enum class TxnType { DEBIT, CREDIT }

enum class TxnSource { SMS, NOTIFICATION, MANUAL }

/** NONE: normal. EXPECTED: user returned the item, refund not yet seen. RECEIVED: refund credit arrived/linked. */
enum class RefundStatus { NONE, EXPECTED, RECEIVED }

enum class PaymentMode { UPI, CARD, CASH, NETBANKING, WALLET, OTHER }

/**
 * One money movement. [amount] is always the full amount that left/entered the account.
 * [myShare] is what was actually "mine" after a split; null until the user answers the split prompt.
 */
data class Transaction(
    val id: Long = 0,
    val amount: Double,
    val type: TxnType,
    val counterparty: String?,      // merchant / payee / payer, e.g. "Swiggy", "ramesh@okaxis"
    val account: String?,           // masked account or card, e.g. "XX1234"
    val bank: String?,              // e.g. "HDFC Bank"
    val upiRef: String?,            // UPI / IMPS / bank reference number, used for dedupe
    val source: TxnSource,
    val sourceApp: String?,         // SMS sender id or notifying package, e.g. "VM-HDFCBK", "com.phonepe.app"
    val rawText: String,
    val timestamp: Long,            // epoch millis
    val category: String = "Other",
    val isSplit: Boolean = false,
    val myShare: Double? = null,
    val reviewed: Boolean = false,  // true once the user answered "fully mine" or entered a split share
    val note: String? = null,
    val paymentMode: PaymentMode? = null,
    val refundStatus: RefundStatus = RefundStatus.NONE,
    val refundAmount: Double? = null,   // on a DEBIT: amount returned (expected or received)
    val refundOfId: Long? = null,       // on a CREDIT: id of the purchase this refund belongs to
) {
    val isRefundCredit: Boolean get() = type == TxnType.CREDIT && refundOfId != null

    /**
     * What this transaction cost *me*: the split share if known, else the full amount, reduced proportionally
     * by any refund already received. Refund credits themselves cost nothing and are not income.
     */
    val effectiveAmount: Double get() {
        val base = if (isSplit && myShare != null) myShare else amount
        if (refundStatus != RefundStatus.RECEIVED || refundAmount == null || amount <= 0) return base
        return (base * (1 - (refundAmount / amount).coerceIn(0.0, 1.0))).coerceAtLeast(0.0)
    }
}

/** Output of a parser before it is persisted. */
data class ParsedTransaction(
    val amount: Double,
    val type: TxnType,
    val counterparty: String?,
    val account: String?,
    val bank: String?,
    val upiRef: String?,
    val timestamp: Long,
    val paymentMode: PaymentMode? = null,
)
