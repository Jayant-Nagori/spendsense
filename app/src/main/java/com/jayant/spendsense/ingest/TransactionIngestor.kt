package com.jayant.spendsense.ingest

import android.content.Context
import com.jayant.spendsense.data.ParsedTransaction
import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.notify.SplitPromptNotifier

/** Glue between parsers and storage: persist (deduped) and, for live debits, ask about the split. */
object TransactionIngestor {

    /**
     * Blocking — call off the main thread. [live] is true for freshly received SMS/notifications
     * and false for historical backfill (backfilled rows are marked reviewed and never prompt).
     */
    fun ingest(
        context: Context,
        parsed: ParsedTransaction,
        source: TxnSource,
        sourceApp: String?,
        rawText: String,
        live: Boolean,
    ): Transaction? {
        val txn = Transaction(
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
            category = CategoryGuesser.guess(parsed.counterparty, rawText),
            paymentMode = parsed.paymentMode,
            // Credits and ATM withdrawals never need a split answer (cash spends are logged manually).
            reviewed = !live || parsed.type == TxnType.CREDIT || parsed.paymentMode == PaymentMode.CASH,
        )
        val stored = TransactionRepository.get(context).insertIfNew(txn) ?: return null
        if (live && !stored.reviewed && stored.type == TxnType.DEBIT && Settings(context).askSplitAfterPayment) {
            SplitPromptNotifier.notifyNewDebit(context, stored)
        }
        return stored
    }
}
