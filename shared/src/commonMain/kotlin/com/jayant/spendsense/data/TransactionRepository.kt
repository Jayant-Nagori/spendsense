package com.jayant.spendsense.data

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ReentrantLock
import com.jayant.spendsense.ingest.CategoryGuesser
import com.jayant.spendsense.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * Single source of truth for transactions. All methods are blocking SQLite calls:
 * call them from Dispatchers.IO. [changes] ticks on every write so UIs can reload.
 */
class TransactionRepository internal constructor(private val db: SqlDb) {

    private val lock = ReentrantLock()
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    private fun bump() { _changes.value = _changes.value + 1 }

    /** Tell observers to reload (after a restore or a Splitwise sync wrote rows directly). */
    fun notifyChanged() = bump()

    /**
     * Inserts unless it duplicates an existing row (same UPI ref, or same amount+type within
     * 10 minutes — the same payment often arrives as both a bank SMS and an app notification).
     * Returns the stored row, or null when it was a duplicate.
     */
    /**
     * Synchronized + one DB transaction: the bank SMS and the app notification for one payment often arrive within
     * milliseconds on different threads, and both must not pass the duplicate check before either inserts.
     */
    fun insertIfNew(txn: Transaction): Transaction? {
        // Last line of defence for every import path: one-time-code messages are never stored.
        if (com.jayant.spendsense.ingest.OtpClassifier.isOtp(txn.rawText)) return null
        val result = lock.withLock { db.transaction { insertIfNewLocked(txn) } }
        if (result != null) bump()
        return result
    }

    private fun insertIfNewLocked(incoming: Transaction): Transaction? {
        val txn = Accounts.derive(incoming)
        findDuplicate(txn)?.let { dup ->
            // Same payment from another channel: remember that channel (so its next payment isn't merged here too)
            // and keep the reference number if only the new copy had one.
            val values = linkedMapOf<String, Any?>("seen_sources" to Dedupe.withReport(seenSources(dup.id), txn).joinToString(","))
            if (dup.upiRef.isNullOrBlank() && !txn.upiRef.isNullOrBlank()) values["upi_ref"] = txn.upiRef
            values.putAll(Dedupe.accountFill(dup, txn))
            db.update("txn", values, "id = ?", listOf(dup.id.toString()))
            return null
        }
        val values = txn.toValues() + ("seen_sources" to sourceKey(txn))
        val id = db.insert("txn", values)
        return txn.copy(id = id)
    }

    /**
     * A bank message (SMS, email) that contradicts an unanswered app-notification row for the same amount within
     * 10 minutes ("credited" vs a notification read as "paid"): the bank is authoritative, so the row takes the bank's
     * type and details. Returns the row as it was before the fix, or null when nothing was contradicted.
     */
    fun correctFromBank(txn: Transaction): Transaction? {
        if (txn.source == TxnSource.NOTIFICATION) return null
        return lock.withLock {
            db.transaction {
                val window = 10 * 60 * 1000L
                val wrong = db.query(
                    "SELECT * FROM txn WHERE type <> ? AND source = ? AND reviewed = 0 AND ABS(amount - ?) < 0.005 AND timestamp BETWEEN ? AND ? " +
                        "ORDER BY ABS(timestamp - ${txn.timestamp}) ASC LIMIT 1",
                    listOf(txn.type.name, TxnSource.NOTIFICATION.name, txn.amount, txn.timestamp - window, txn.timestamp + window),
                ).firstOrNull()?.toTransaction()
                    ?.takeIf { it.upiRef.isNullOrBlank() || txn.upiRef.isNullOrBlank() || it.upiRef == txn.upiRef }
                    ?: return@transaction null
                val fixed = Accounts.derive(txn).toValues().filterKeys {
                    it in setOf("type", "counterparty", "account", "bank", "upi_ref", "source", "source_app", "raw_text", "category",
                        "payment_mode", "issuer", "instrument", "last4", "is_transfer", "transfer_kind")
                } + ("seen_sources" to Dedupe.withReport(seenSources(wrong.id), txn).joinToString(","))
                db.update("txn", fixed, "id = ?", listOf(wrong.id.toString()))
                wrong
            }
        }?.also { bump() }
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
        if (!txn.upiRef.isNullOrBlank()) {
            db.query("SELECT * FROM txn WHERE upi_ref = ? LIMIT 1", listOf(txn.upiRef)).firstOrNull()?.let { return it.toTransaction() }
        }
        val window = 10 * 60 * 1000L
        val rows = db.query(
            "SELECT * FROM txn WHERE type = ? AND timestamp BETWEEN ? AND ? ORDER BY ABS(timestamp - ${txn.timestamp}) ASC",
            listOf(txn.type.name, (txn.timestamp - window).toString(), (txn.timestamp + window).toString()),
        )
        for (row in rows) {
            val t = row.toTransaction()
            if (Dedupe.isSamePayment(t, parseSeen(row.string("seen_sources"), t), txn)) return t
        }
        return null
    }

    private fun seenSources(id: Long): Set<String> =
        db.query("SELECT seen_sources, source, source_app FROM txn WHERE id = ?", listOf(id.toString())).firstOrNull()
            ?.let { parseSeen(it.string("seen_sources"), get(id)) } ?: emptySet()

    private fun parseSeen(raw: String?, t: Transaction?): Set<String> =
        raw?.split(',')?.filter { it.isNotBlank() }?.let { LinkedHashSet(it) }?.takeIf { it.isNotEmpty() } ?: setOfNotNull(t?.let { sourceKey(it) })

    private fun sourceKey(t: Transaction) = Dedupe.sourceKey(t)

    fun get(id: Long): Transaction? =
        db.query("SELECT * FROM txn WHERE id = ?", listOf(id.toString())).firstOrNull()?.toTransaction()

    fun update(txn: Transaction) {
        db.update("txn", txn.toValues(), "id = ?", listOf(txn.id.toString()))
        bump()
    }

    /** Records the answer to the split prompt. [myShare] is ignored when [isSplit] is false. */
    fun setSplit(id: Long, isSplit: Boolean, myShare: Double?) {
        val t = get(id) ?: return
        update(t.copy(isSplit = isSplit, myShare = if (isSplit) myShare else null, reviewed = true, paidForOthers = false, paidFor = null))
    }

    /** Persists a split-prompt answer, including "paid for someone else" ([paidForOthers], optional [paidFor] name). */
    fun setAnswer(id: Long, isSplit: Boolean, myShare: Double?, paidForOthers: Boolean, paidFor: String?) {
        if (paidForOthers) setPaidFor(id, paidFor) else setSplit(id, isSplit, myShare)
    }

    /** The whole payment was for someone else: my share is 0 and they owe me [Transaction.amount]. [who] may be blank. */
    fun setPaidFor(id: Long, who: String?) {
        val t = get(id) ?: return
        update(t.withPaidFor(who))
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
                db.update("txn", mapOf("category" to guess), "id = ?", listOf(t.id.toString()))
                changed++
            }
        }
        if (changed > 0) bump()
        return changed
    }

    /**
     * v6 backfill: fills issuer / instrument / last 4 / transfer on rows stored before 1.3, from their own stored
     * fields (bank, account, payment mode, message). Idempotent; returns how many rows changed.
     */
    fun backfillAccounts(): Int {
        var changed = 0
        val rows = db.query("SELECT * FROM txn WHERE issuer IS NULL AND instrument IS NULL AND last4 IS NULL", emptyList()).map { it.toTransaction() }
        lock.withLock {
            db.transaction {
                for (t in rows) {
                    val d = Accounts.derive(t)
                    if (d.issuer == null && d.instrument == null && d.last4 == null && !d.isTransfer) continue
                    db.update(
                        "txn",
                        mapOf(
                            "issuer" to d.issuer, "instrument" to d.instrument?.name, "last4" to d.last4,
                            "is_transfer" to if (d.isTransfer) 1 else 0, "transfer_kind" to d.transferKind?.name,
                            "reviewed" to if (d.reviewed) 1 else 0,
                        ),
                        "id = ?", listOf(t.id.toString()),
                    )
                    changed++
                }
            }
        }
        if (changed > 0) bump()
        return changed
    }

    // ---- "not a payment" ------------------------------------------------------------------------------------

    /**
     * The user says this row isn't a payment (offer, promo, reminder…): deletes it and, for SMS / app notifications,
     * remembers sender + template so similar alerts are ignored. Returns true when a rule was saved.
     */
    fun markNotPayment(id: Long): Boolean {
        val t = get(id) ?: return false
        // A message that proves a real debit/credit only gets deleted: a rule for its template would silently drop
        // every later real alert in that bank's format (R3-05).
        val sender = IgnoreRules.senderKey(t.source, t.sourceApp)
            ?.takeIf { !com.jayant.spendsense.ingest.PromoClassifier.hasCompletionEvidence(t.rawText) }
        val saved = sender != null && lock.withLock {
            db.query("SELECT id FROM ignore_rule WHERE sender = ? AND template_hash = ?", listOf(sender, IgnoreRules.hash(t.rawText))).isEmpty().also { fresh ->
                if (fresh) db.insert("ignore_rule", mapOf("sender" to sender, "template_hash" to IgnoreRules.hash(t.rawText), "created_at" to com.jayant.spendsense.currentTimeMillis()))
            }
            true
        }
        delete(id)
        return saved
    }

    /** True when a "not a payment" rule matches this sender and message. */
    fun isIgnored(source: TxnSource, sourceApp: String?, rawText: String): Boolean {
        // A message proving a real debit/credit is never dropped, whatever rule matches it (colliding or restored rules).
        if (com.jayant.spendsense.ingest.PromoClassifier.hasCompletionEvidence(rawText)) return false
        val sender = IgnoreRules.senderKey(source, sourceApp) ?: return false
        return db.query("SELECT 1 AS x FROM ignore_rule WHERE sender = ? AND template_hash = ? LIMIT 1", listOf(sender, IgnoreRules.hash(rawText))).isNotEmpty()
    }

    fun ignoreRuleCount(): Int = db.query("SELECT COUNT(*) AS n FROM ignore_rule").firstOrNull()?.int("n") ?: 0

    fun clearIgnoreRules() { db.delete("ignore_rule", "1 = 1", emptyList()); bump() }

    fun delete(id: Long) {
        db.delete("txn", "id = ?", listOf(id.toString()))
        // Its order receipt (if any) becomes unmatched again.
        db.update("order_details", mapOf("txn_id" to null), "txn_id = ?", listOf(id.toString()))
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
            // Always bound, never concatenated. LIKE wildcards in the user's text are escaped so "%" or "_" match
            // literally instead of everything.
            val like = "LIKE ? ESCAPE '\\'"
            where += "(counterparty $like OR raw_text $like OR note $like OR category $like OR bank $like OR paid_for $like OR " +
                "id IN (SELECT txn_id FROM order_details WHERE txn_id IS NOT NULL AND (source $like OR restaurant $like OR items_json $like)))"
            val pattern = "%" + it.take(200).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(9) { _ -> args += pattern }
        }
        type?.let { where += "type = ?"; args += it.name }
        category?.let { where += "category = ?"; args += it }
        return query(where.joinToString(" AND ").ifEmpty { null }, args.toTypedArray().ifEmpty { null }, limit)
    }

    private fun query(where: String?, args: Array<String>?, limit: Int = 5000): List<Transaction> {
        val sql = "SELECT * FROM txn" + (where?.let { " WHERE $it" } ?: "") + " ORDER BY timestamp DESC LIMIT ${limit.coerceIn(1, 50_000)}"
        return db.query(sql, args?.toList() ?: emptyList()).map { it.toTransaction() }
    }

    companion object {
        @Volatile private var instance: TransactionRepository? = null
        private val instanceLock = ReentrantLock()

        /** Recovery replaced the database: the next [get] opens the new one. */
        fun resetForRecovery() { instanceLock.withLock { instance = null } }
        fun get(context: PlatformContext): TransactionRepository =
            instance ?: instanceLock.withLock { instance ?: TransactionRepository(openDatabase(context)).also { instance = it } }
    }
}
