package com.jayant.spendsense.data

enum class TxnType { DEBIT, CREDIT }

enum class TxnSource { SMS, NOTIFICATION, MANUAL }

/** NONE: normal. EXPECTED: user returned the item, refund not yet seen. RECEIVED: refund credit arrived/linked. */
enum class RefundStatus { NONE, EXPECTED, RECEIVED }

enum class PaymentMode { UPI, CARD, CASH, NETBANKING, WALLET, OTHER }

/** How the money left / arrived, for the "By card & account" split. */
enum class Instrument(val label: String) {
    CREDIT_CARD("Credit Card"), DEBIT_CARD("Debit Card"), UPI("UPI"), NETBANKING("Net Banking"),
    WALLET("Wallet"), CASH("Cash"), OTHER("Account"),
}

/** Why a row is a transfer rather than spending or income. */
enum class TransferKind(val label: String) { CARD_BILL("Card bill payment"), SELF("Own-account transfer"), SPLITWISE_SETTLE("Splitwise settle-up") }

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
    val paidForOthers: Boolean = false, // whole payment was for someone else (isSplit = true, myShare = 0)
    val paidFor: String? = null,        // who it was for; only meaningful with [paidForOthers], may be blank
    val issuer: String? = null,         // canonical issuing bank, e.g. "Axis Bank" (v6)
    val instrument: Instrument? = null, // credit / debit card, UPI, net banking, wallet, cash (v6)
    val last4: String? = null,          // last 4 digits of the card / account (v6)
    val isTransfer: Boolean = false,    // card bill payment, own-account transfer, settle-up: not spend, not income
    val transferKind: TransferKind? = null,
    val splitwiseId: String? = null,    // Splitwise expense id this row was imported from / linked to
) {
    /** Name shown for a paid-for-others payment, or null when it has none. */
    val paidForName: String? get() = if (paidForOthers) paidFor?.trim()?.takeIf { it.isNotEmpty() } else null

    /** What others owe me on this payment: the full amount if paid for someone else, else the split remainder. */
    val othersOwe: Double get() = when {
        type != TxnType.DEBIT -> 0.0
        paidForOthers -> amount
        isSplit && myShare != null -> (amount - myShare).coerceAtLeast(0.0)
        else -> 0.0
    }

    val isRefundCredit: Boolean get() = type == TxnType.CREDIT && refundOfId != null

    /**
     * What this transaction cost *me*: the split share if known, else the full amount, reduced proportionally
     * by any refund already received. Refund credits themselves cost nothing and are not income.
     */
    val effectiveAmount: Double get() {
        if (paidForOthers || isTransfer) return 0.0
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
    val category: String? = null,       // set when the parser knows better than CategoryGuesser (e.g. "Cashback")
    val issuer: String? = null,
    val instrument: Instrument? = null,
    val last4: String? = null,
    val transferKind: TransferKind? = null,
)

/** Marks this payment as made entirely for someone else (name optional, trimmed and capped). */
fun Transaction.withPaidFor(who: String?): Transaction =
    copy(isSplit = true, myShare = 0.0, reviewed = true, paidForOthers = true, paidFor = who?.trim()?.take(60)?.ifBlank { null })
