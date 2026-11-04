package com.jayant.spendsense.data

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ReentrantLock
import com.jayant.spendsense.withLock
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User's choices for a card / account: nickname, hidden from the split, or merged into another key. */
data class AccountPref(val key: String, val nickname: String? = null, val hidden: Boolean = false, val mergedInto: String? = null)

/** One card / account seen in the transactions, for Settings › Accounts. */
data class KnownAccount(val key: String, val count: Int, val total: Double, val pref: AccountPref?) {
    val label: String get() = pref?.nickname ?: Accounts.labelForKey(key)
}

/** A row of the dashboard's "By card & account". */
data class AccountShare(val key: String, val label: String, val monogram: String, val amount: Double, val share: Double, val count: Int)

/** Card / account preferences (account_pref table) and the per-account totals. Blocking; call off the main thread. */
class AccountRepository internal constructor(private val db: SqlDb) {
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    fun prefs(): Map<String, AccountPref> = db.query("SELECT * FROM account_pref").associate { r ->
        val k = r.string("account_key")!!
        k to AccountPref(k, r.string("nickname"), r.int("hidden") == 1, r.string("merged_into"))
    }

    fun known(): List<KnownAccount> {
        val prefs = prefs()
        return db.query("SELECT issuer, instrument, last4, COUNT(*) AS n, SUM(amount) AS total FROM txn WHERE type = 'DEBIT' GROUP BY issuer, instrument, last4")
            .map { r ->
                val key = Accounts.key(r.string("issuer"), r.string("instrument")?.let { runCatching { Instrument.valueOf(it) }.getOrNull() }, r.string("last4"))
                KnownAccount(key, r.int("n") ?: 0, r.double("total") ?: 0.0, prefs[key])
            }
            .groupBy { it.key }.map { (k, v) -> KnownAccount(k, v.sumOf { it.count }, v.sumOf { it.total }, prefs[k]) }
            .sortedByDescending { it.total }
    }

    private fun upsert(p: AccountPref) {
        db.execute(
            "INSERT OR REPLACE INTO account_pref (account_key, nickname, hidden, merged_into) VALUES (?, ?, ?, ?)",
            listOf(p.key, p.nickname?.trim()?.take(40)?.ifBlank { null }, if (p.hidden) 1 else 0, p.mergedInto),
        )
        _changes.value++
    }

    fun rename(key: String, nickname: String?) = upsert((prefs()[key] ?: AccountPref(key)).copy(nickname = nickname))

    fun setHidden(key: String, hidden: Boolean) = upsert((prefs()[key] ?: AccountPref(key)).copy(hidden = hidden))

    /** Shows [from] as part of [into] (e.g. the same card reported with and without its bank). */
    fun merge(from: String, into: String) {
        if (from == into) return
        val target = resolve(into, prefs())
        if (target == from) return // would loop
        upsert((prefs()[from] ?: AccountPref(from)).copy(mergedInto = target))
    }

    fun unmerge(key: String) = upsert((prefs()[key] ?: AccountPref(key)).copy(mergedInto = null))

    companion object {
        @Volatile private var instance: AccountRepository? = null
        private val instanceLock = ReentrantLock()
        fun get(context: PlatformContext): AccountRepository =
            instance ?: instanceLock.withLock { instance ?: AccountRepository(openDatabase(context)).also { instance = it } }

        fun resetForRecovery() { instanceLock.withLock { instance = null } }

        /** Follows merges (bounded, so a bad loop can't hang). */
        fun resolve(key: String, prefs: Map<String, AccountPref>): String {
            var k = key
            repeat(8) { k = prefs[k]?.mergedInto ?: return k }
            return k
        }

        /**
         * Money paid out of each card / account in [txns] (debits, excluding transfers, money paid for someone else
         * stays in: it still left that card). Merged keys add up, hidden ones are left out. Largest first.
         */
        fun split(txns: List<Transaction>, prefs: Map<String, AccountPref>): List<AccountShare> {
            val groups = txns.asSequence()
                .filter { it.type == TxnType.DEBIT && !it.isTransfer && !(it.splitwiseId != null && it.source == TxnSource.MANUAL) }
                .groupBy { resolve(Accounts.key(it), prefs) }
                .filterKeys { prefs[it]?.hidden != true }
            val total = groups.values.sumOf { list -> list.sumOf { it.amount } }
            if (total <= 0) return emptyList()
            return groups.map { (k, list) ->
                val amount = list.sumOf { it.amount }
                AccountShare(k, prefs[k]?.nickname ?: Accounts.labelForKey(k), Accounts.monogram(k), amount, amount / total, list.size)
            }.sortedByDescending { it.amount }
        }

        /** Whether [t] belongs to [key] once merges are applied. */
        fun belongsTo(t: Transaction, key: String, prefs: Map<String, AccountPref>): Boolean = resolve(Accounts.key(t), prefs) == key
    }
}
