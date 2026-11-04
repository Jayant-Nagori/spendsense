package com.jayant.spendsense.ingest

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.data.ParsedTransaction
import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType

/** Platform reactions to a stored transaction (notifications, the payment pop-up). Never given message text to log. */
interface IngestEvents {
    /** A refund credit was matched to [purchase]. */
    fun onRefundLinked(context: PlatformContext, credit: Transaction, purchase: Transaction)

    /** A live debit needs the "what was it for / split?" answer. */
    fun onNewDebit(context: PlatformContext, txn: Transaction)

    /** The bank showed debit [id] was really money in: withdraw its pop-up and notification. */
    fun onDebitWithdrawn(context: PlatformContext, id: Long) {}
}

/** Glue between parsers and storage: persist (deduped) and, for live debits, ask about the split. */
object TransactionIngestor {

    /** Set once at app start by the platform (Android: notification + overlay pop-up; iOS: local notification + in-app pop-up). */
    var events: IngestEvents? = null

    private val REFUND_WORDS = Regex("\\b(refund(ed)?|reversed|reversal|returned)\\b", RegexOption.IGNORE_CASE)

    /** The row [ingest] would store for [parsed], or null when [rawText] must never be stored (OTP / codes). */
    internal fun toTransaction(
        parsed: ParsedTransaction,
        source: TxnSource,
        sourceApp: String?,
        rawText: String,
        live: Boolean,
    ): Transaction? {
        // Defence in depth for every channel (SMS, notifications, email, statements, Shortcuts): one-time codes are
        // never stored, shown or handed to the assistant.
        if (OtpClassifier.isOtp(rawText)) return null
        return Transaction(
            amount = parsed.amount,
            type = parsed.type,
            counterparty = parsed.counterparty,
            account = parsed.account,
            bank = parsed.bank,
            upiRef = parsed.upiRef,
            source = source,
            sourceApp = sourceApp,
            rawText = rawText,
            timestamp = parsed.timestamp,
            category = parsed.category ?: CategoryGuesser.guess(parsed.counterparty, rawText),
            paymentMode = parsed.paymentMode,
            issuer = parsed.issuer,
            instrument = parsed.instrument,
            last4 = parsed.last4,
            transferKind = parsed.transferKind,
            isTransfer = parsed.transferKind != null,
            // Credits and ATM withdrawals never need a split answer (cash spends are logged manually).
            reviewed = !live || parsed.type == TxnType.CREDIT || parsed.paymentMode == PaymentMode.CASH,
        )
    }

    /**
     * Blocking — call off the main thread. [live] is true for freshly received SMS/notifications
     * and false for historical backfill (backfilled rows are marked reviewed and never prompt).
     */
    fun ingest(
        context: PlatformContext,
        parsed: ParsedTransaction,
        source: TxnSource,
        sourceApp: String?,
        rawText: String,
        live: Boolean,
    ): Transaction? {
        val txn = toTransaction(parsed, source, sourceApp, rawText, live) ?: return null
        // The user marked a similar alert from this sender "Not a payment".
        if (runCatching { TransactionRepository.get(context).isIgnored(source, sourceApp, rawText) }.getOrDefault(false)) return null
        TransactionRepository.get(context).correctFromBank(txn)?.let { wrong ->
            if (live && wrong.type == TxnType.DEBIT) events?.onDebitWithdrawn(context, wrong.id)
            return null
        }
        val stored = TransactionRepository.get(context).insertIfNew(txn) ?: return null
        if (stored.type == TxnType.CREDIT && REFUND_WORDS.containsMatchIn(rawText)) {
            val repo = TransactionRepository.get(context)
            repo.findRefundTarget(stored)?.let { purchase ->
                repo.linkRefund(stored.id, purchase.id)
                if (live) events?.onRefundLinked(context, stored, purchase)
            }
        }
        if (live && stored.type == TxnType.DEBIT) {
            // Delivery-app payment with that app connected: look up what was ordered (async, retries).
            runCatching { com.jayant.spendsense.orders.OrderSync.onPaymentIngested(context, stored) }
        }
        if (live && !stored.reviewed && stored.type == TxnType.DEBIT && Settings(context).askSplitAfterPayment) {
            events?.onNewDebit(context, stored)
        }
        return stored
    }
}
