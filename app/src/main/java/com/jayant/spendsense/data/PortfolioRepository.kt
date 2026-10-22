package com.jayant.spendsense.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** CRUD for investments. Blocking — call from Dispatchers.IO. Shares the encrypted DB with transactions. */
class PortfolioRepository private constructor(context: Context) {

    private val db = TransactionDb.get(context)
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    private fun bump() { _changes.value = _changes.value + 1 }

    fun all(): List<Investment> =
        db.readableDatabase.query("investment", null, null, null, null, null, "invested_amount DESC").use { c ->
            buildList { while (c.moveToNext()) add(c.toInvestment()) }
        }

    fun get(id: Long): Investment? =
        db.readableDatabase.query("investment", null, "id = ?", arrayOf(id.toString()), null, null, null).use { c ->
            if (c.moveToFirst()) c.toInvestment() else null
        }

    /** Inserts when id == 0, else updates. Returns the id. */
    fun upsert(inv: Investment): Long {
        val values = inv.copy(updatedAt = System.currentTimeMillis()).toValues()
        val id = if (inv.id == 0L) {
            db.writableDatabase.insert("investment", null, values)
        } else {
            db.writableDatabase.update("investment", values, "id = ?", arrayOf(inv.id.toString()))
            inv.id
        }
        bump()
        return id
    }

    fun delete(id: Long) {
        db.writableDatabase.delete("investment", "id = ?", arrayOf(id.toString()))
        bump()
    }

    companion object {
        @Volatile private var instance: PortfolioRepository? = null
        fun get(context: Context): PortfolioRepository =
            instance ?: synchronized(this) { instance ?: PortfolioRepository(context).also { instance = it } }
    }
}
