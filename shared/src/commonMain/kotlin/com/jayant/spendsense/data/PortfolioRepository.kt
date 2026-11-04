package com.jayant.spendsense.data

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ReentrantLock
import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/** CRUD for investments. Blocking — call from Dispatchers.IO. Shares the encrypted DB with transactions. */
class PortfolioRepository internal constructor(private val db: SqlDb) {

    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    private fun bump() { _changes.value = _changes.value + 1 }

    fun all(): List<Investment> =
        db.query("SELECT * FROM investment ORDER BY invested_amount DESC").map { it.toInvestment() }

    fun get(id: Long): Investment? =
        db.query("SELECT * FROM investment WHERE id = ?", listOf(id.toString())).firstOrNull()?.toInvestment()

    /** Inserts when id == 0, else updates. Returns the id. */
    fun upsert(inv: Investment): Long {
        val values = inv.copy(updatedAt = currentTimeMillis()).toValues()
        val id = if (inv.id == 0L) {
            db.insert("investment", values)
        } else {
            db.update("investment", values, "id = ?", listOf(inv.id.toString()))
            inv.id
        }
        bump()
        return id
    }

    fun delete(id: Long) {
        db.delete("investment", "id = ?", listOf(id.toString()))
        bump()
    }

    companion object {
        @Volatile private var instance: PortfolioRepository? = null
        private val instanceLock = ReentrantLock()

        /** Recovery replaced the database: the next [get] opens the new one. */
        fun resetForRecovery() { instanceLock.withLock { instance = null } }
        fun get(context: PlatformContext): PortfolioRepository =
            instance ?: instanceLock.withLock { instance ?: PortfolioRepository(openDatabase(context)).also { instance = it } }
    }
}
