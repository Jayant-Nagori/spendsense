package com.jayant.spendsense.data

import android.content.Context
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
    fun insertIfNew(txn: Transaction): Transaction? {
        if (findDuplicate(txn) != null) return null
        val id = db.writableDatabase.insert("txn", null, txn.toValues())
        bump()
        return txn.copy(id = id)
    }

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
            null, null, null,
        ).use { c ->
            while (c.moveToNext()) {
                val t = c.toTransaction()
                if (abs(t.amount - txn.amount) < 0.005) return t
            }
        }
        return null
    }

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
