package com.jayant.spendsense.data

import android.content.ContentValues
import android.content.Context
import com.jayant.spendsense.ingest.CategoryGuesser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * Single source of truth for transactions. All methods are blocking SQLite calls:
 * call them from Dispatchers.IO. [changes] ticks on every write so UIs can reload.
 */
class TransactionRepository private constructor(context: Context) {

    private val db = TransactionDb.get(context)
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    private fun bump() { _changes.value = _changes.value + 1 }

    /**
     * Inserts unless it duplicates an existing row (same UPI ref, or same amount+type within
     * 10 minutes — the same payment often arrives as both a bank SMS and an app notification).
     * Returns the stored row, or null when it was a duplicate.
     */
    /**
     * Synchronized + one DB transaction: the bank SMS and the app notification for one payment often arrive within
     * milliseconds on different threads, and both must not pass the duplicate check before either inserts.
     */
    @Synchronized
    fun insertIfNew(txn: Transaction): Transaction? {
        val w = db.writableDatabase
        w.beginTransaction()
        val result = try {
            insertIfNewLocked(txn).also { w.setTransactionSuccessful() }
        } finally {
            w.endTransaction()
        }
        if (result != null) bump()
        return result
    }

    private fun insertIfNewLocked(txn: Transaction): Transaction? {
        findDuplicate(txn)?.let { dup ->
            // Same payment from another channel: remember that channel (so its next payment isn't merged here too)
            // and keep the reference number if only the new copy had one.
            val values = ContentValues().apply {
                put("seen_sources", (seenSources(dup.id) + sourceKey(txn)).joinToString(","))
                if (dup.upiRef.isNullOrBlank() && !txn.upiRef.isNullOrBlank()) put("upi_ref", txn.upiRef)
            }
            db.writableDatabase.update("txn", values, "id = ?", arrayOf(dup.id.toString()))
            return null
        }
        val values = txn.toValues().apply { put("seen_sources", sourceKey(txn)) }
        val id = db.writableDatabase.insert("txn", null, values)
        return txn.copy(id = id)
    }

    /**
     * The stored row this transaction is another report of, or null if it is a new payment.
     *
     * The same payment usually arrives more than once (bank SMS + GPay notification + email + statement), often
     * without a shared reference. Rows of the same amount within 10 minutes are treated as one payment, except:
     * different reference numbers are always different payments, and a channel that already reported a row can
     * only repeat it verbatim — two ₹1 payments a minute apart, each with its own SMS, stay two payments.
     */
    fun findDuplicate(txn: Transaction): Transaction? {
        val r = db.readableDatabase
        if (!txn.upiRef.isNullOrBlank()) {
            r.query("txn", null, "upi_ref = ?", arrayOf(txn.upiRef), null, null, null, "1").use { c ->
                if (c.moveToFirst()) return c.toTransaction()
            }
        }
        val window = 10 * 60 * 1000L
        r.query(
            "txn", null,
            "type = ? AND timestamp BETWEEN ? AND ?",
            arrayOf(txn.type.name, (txn.timestamp - window).toString(), (txn.timestamp + window).toString()),
            null, null, "ABS(timestamp - ${txn.timestamp}) ASC",
        ).use { c ->
            val iSeen = c.getColumnIndex("seen_sources")
            while (c.moveToNext()) {
                val t = c.toTransaction()
                if (Dedupe.isSamePayment(t, parseSeen(if (iSeen >= 0) c.getString(iSeen) else null, t), txn)) return t
            }
        }
        return null
    }

    private fun seenSources(id: Long): Set<String> =
        db.readableDatabase.query("txn", arrayOf("seen_sources", "source", "source_app"), "id = ?", arrayOf(id.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) parseSeen(c.getString(0), get(id)) else emptySet() }

    private fun parseSeen(raw: String?, t: Transaction?): Set<String> =
        raw?.split(',')?.filter { it.isNotBlank() }?.toSet()?.takeIf { it.isNotEmpty() } ?: setOfNotNull(t?.let { sourceKey(it) })

    private fun sourceKey(t: Transaction) = Dedupe.sourceKey(t)

    fun get(id: Long): Transaction? =
        db.readableDatabase.query("txn", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toTransaction() else null
        }

    fun update(txn: Transaction) {
        db.writableDatabase.update("txn", txn.toValues(), "id = ?", arrayOf(txn.id.toString()))
        bump()
    }

    /** Records the answer to the split prompt. [myShare] is ignored when [isSplit] is false. */
    fun setSplit(id: Long, isSplit: Boolean, myShare: Double?) {
        val t = get(id) ?: return
        update(t.copy(isSplit = isSplit, myShare = if (isSplit) myShare else null, reviewed = true))
    }

    /** Saves what the payment was for (from the notification reply or the split dialog). Blank clears it. */
    fun setNote(id: Long, note: String?) {
        val t = get(id) ?: return
        update(t.copy(note = note?.trim()?.take(200)?.ifBlank { null }))
    }

    // ---- refunds -------------------------------------------------------------------------------

    /** User says they returned (part of) this purchase. [amount] defaults to the full amount. */
    fun markReturned(id: Long, amount: Double? = null) {
        val t = get(id) ?: return
        if (t.type != TxnType.DEBIT || t.refundStatus == RefundStatus.RECEIVED) return
        update(t.copy(refundStatus = RefundStatus.EXPECTED, refundAmount = (amount ?: t.amount).coerceIn(0.01, t.amount)))
    }

    /** Undo "I returned this" (only while the refund has not arrived). */
    fun cancelReturn(id: Long) {
        val t = get(id) ?: return
        if (t.refundStatus != RefundStatus.EXPECTED) return
        update(t.copy(refundStatus = RefundStatus.NONE, refundAmount = null))
    }

    /** Debits waiting for a refund, newest first. */
    fun refundsExpected(): List<Transaction> =
        query("refund_status = ? AND type = ?", arrayOf(RefundStatus.EXPECTED.name, TxnType.DEBIT.name))

    /** Links a refund credit to its purchase and marks the purchase refunded (adds up partial refunds). */
    fun linkRefund(creditId: Long, debitId: Long) {
        val credit = get(creditId) ?: return
        val debit = get(debitId) ?: return
        update(credit.copy(refundOfId = debit.id, category = "Refund", reviewed = true))
        val already = if (debit.refundStatus == RefundStatus.RECEIVED) debit.refundAmount ?: 0.0 else 0.0
        update(debit.copy(refundStatus = RefundStatus.RECEIVED, refundAmount = (already + credit.amount).coerceAtMost(debit.amount)))
    }

    /** Detaches a refund credit from its purchase (e.g. wrong auto-match). */
    fun unlinkRefund(creditId: Long) {
        val credit = get(creditId) ?: return
        val debitId = credit.refundOfId ?: return
        update(credit.copy(refundOfId = null, category = "Other"))
        get(debitId)?.let { update(it.copy(refundStatus = RefundStatus.NONE, refundAmount = null)) }
    }

    /**
     * Best purchase for an incoming refund credit: within 60 days before it, not yet refunded, amount >= the credit.
     * Prefers EXPECTED returns, then a matching counterparty, then the exact amount, then the most recent.
     */
    fun findRefundTarget(credit: Transaction): Transaction? {
        val from = credit.timestamp - 60L * 24 * 60 * 60 * 1000
        val candidates = query(
            "type = ? AND timestamp >= ? AND timestamp <= ? AND amount >= ? AND refund_status != ?",
            arrayOf(TxnType.DEBIT.name, from.toString(), credit.timestamp.toString(), (credit.amount - 0.5).toString(), RefundStatus.RECEIVED.name),
            200,
        )
        if (candidates.isEmpty()) return null
        val cp = credit.counterparty?.lowercase()?.take(6)
        fun sameMerchant(t: Transaction) = cp != null && t.counterparty?.lowercase()?.contains(cp) == true ||
            cp != null && t.counterparty?.lowercase()?.take(6)?.let { credit.rawText.lowercase().contains(it) } == true
        fun score(t: Transaction): Int =
            (if (t.refundStatus == RefundStatus.EXPECTED) 8 else 0) +
                (if (sameMerchant(t)) 4 else 0) +
                (if (kotlin.math.abs(t.amount - credit.amount) < 0.5 || kotlin.math.abs((t.refundAmount ?: -1.0) - credit.amount) < 0.5) 2 else 0)
        val best = candidates.maxWith(compareBy<Transaction> { score(it) }.thenBy { it.timestamp })
        // Without a user-marked return, require at least a merchant match to avoid linking random credits.
        return best.takeIf { score(it) >= 4 }
    }

    /**
     * Re-guesses rows auto-filed as Investment by the old substring matcher (e.g. "Aradhana" hit "dhan").
     * Rows whose text still looks like an investment keep the category. Returns how many changed.
     */
    fun recategorizeFalseInvestments(): Int {
        var changed = 0
        for (t in query("category = ?", arrayOf("Investment"))) {
            val guess = CategoryGuesser.guess(t.counterparty, t.rawText)
            if (guess != "Investment") {
                db.writableDatabase.update("txn", ContentValues().apply { put("category", guess) }, "id = ?", arrayOf(t.id.toString()))
                changed++
            }
        }
        if (changed > 0) bump()
        return changed
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("txn", "id = ?", arrayOf(id.toString()))
        bump()
    }

    /** Transactions with timestamp in [fromMillis, toMillis), newest first. */
    fun between(fromMillis: Long, toMillis: Long): List<Transaction> =
        query("timestamp >= ? AND timestamp < ?", arrayOf(fromMillis.toString(), toMillis.toString()))

    fun all(limit: Int = 5000): List<Transaction> = query(null, null, limit)

    /** Debits the user has not yet answered the split question for, newest first. */
    fun pendingReview(): List<Transaction> =
        query("reviewed = 0 AND type = ?", arrayOf(TxnType.DEBIT.name))

    /** Flexible search used by the AI assistant. All filters optional; amounts match within ±0.5. */
    fun search(
        fromMillis: Long? = null,
        toMillis: Long? = null,
        amount: Double? = null,
        minAmount: Double? = null,
        maxAmount: Double? = null,
        text: String? = null,
        type: TxnType? = null,
        category: String? = null,
        limit: Int = 200,
    ): List<Transaction> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        fromMillis?.let { where += "timestamp >= ?"; args += it.toString() }
        toMillis?.let { where += "timestamp < ?"; args += it.toString() }
        amount?.let { where += "(ABS(amount - ?) < 0.5 OR ABS(IFNULL(my_share, -1) - ?) < 0.5)"; args += it.toString(); args += it.toString() }
        minAmount?.let { where += "amount >= ?"; args += it.toString() }
        maxAmount?.let { where += "amount <= ?"; args += it.toString() }
        text?.takeIf { it.isNotBlank() }?.let {
            where += "(counterparty LIKE ? OR raw_text LIKE ? OR note LIKE ? OR category LIKE ? OR bank LIKE ?)"
            repeat(5) { _ -> args += "%$it%" }
        }
        type?.let { where += "type = ?"; args += it.name }
        category?.let { where += "category = ?"; args += it }
        return query(where.joinToString(" AND ").ifEmpty { null }, args.toTypedArray().ifEmpty { null }, limit)
    }

    private fun query(where: String?, args: Array<String>?, limit: Int = 5000): List<Transaction> =
        db.readableDatabase.query("txn", null, where, args, null, null, "timestamp DESC", limit.toString()).use { c ->
            buildList { while (c.moveToNext()) add(c.toTransaction()) }
        }

    companion object {
        @Volatile private var instance: TransactionRepository? = null
        fun get(context: Context): TransactionRepository =
            instance ?: synchronized(this) { instance ?: TransactionRepository(context).also { instance = it } }
    }
}
