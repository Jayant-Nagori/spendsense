package com.jayant.spendsense.splitwise

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ReentrantLock
import com.jayant.spendsense.data.SqlDb
import com.jayant.spendsense.data.SqlRow
import com.jayant.spendsense.data.TransferKind
import com.jayant.spendsense.data.openDatabase
import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import com.jayant.spendsense.withLock
import kotlin.concurrent.Volatile
import kotlin.math.abs

/** What one Splitwise expense became locally. */
enum class SwRole { OWE, PAID, SETTLE, NONE }

/** A stored Splitwise expense link (splitwise_expense row), for the transaction detail breakdown. */
data class SwRecord(
    val swId: String, val txnId: Long?, val role: SwRole, val description: String?, val cost: Double, val myPaid: Double,
    val myOwed: Double, val payer: String?, val shares: List<SwShare>, val group: String?, val date: Long, val updatedAt: String?,
)

data class SwFriendBalance(val name: String, val balance: Double)

data class SwImportStats(val owed: Int = 0, val linked: Int = 0, val settled: Int = 0, val removed: Int = 0, val unmatched: Int = 0)

/**
 * Splitwise ⇄ local rows. Idempotent by Splitwise expense id: re-importing never duplicates, edits update the
 * imported row, deletions remove it (or undo the link). Splitwise text is data only: it is cleaned and shown as
 * plain text, never handed to the AI as instructions. Blocking; call off the main thread.
 */
class SplitwiseStore internal constructor(private val db: SqlDb) {
    private val lock = ReentrantLock()

    fun apply(expenses: List<SwExpense>, me: Long): SwImportStats = lock.withLock {
        var s = SwImportStats()
        db.transaction {
            for (e in expenses) s = applyOne(e, me, s)
        }
        s
    }

    private fun applyOne(e: SwExpense, me: Long, stats: SwImportStats): SwImportStats {
        val swId = e.id.toString()
        val existing = record(swId)
        val mine = e.shares.firstOrNull { it.userId == me }
        if (e.deleted || mine == null || !e.currency.equals(SplitwiseApi.CURRENCY, true)) {
            if (existing != null) { undo(existing); return stats.copy(removed = stats.removed + 1) }
            return stats
        }
        // Unchanged and already resolved: nothing to do. An unmatched PAID / SETTLE retries linking every sync.
        if (existing != null && existing.updatedAt == e.updatedAt && (existing.txnId != null || existing.role == SwRole.NONE)) return stats

        val role = when {
            e.payment -> SwRole.SETTLE
            mine.owed < 0 || mine.paid < 0 -> SwRole.NONE
            mine.owed - mine.paid > 0.004 -> SwRole.OWE
            mine.paid - mine.owed > 0.004 -> SwRole.PAID
            else -> SwRole.NONE
        }
        val payer = e.shares.filter { it.userId != me && it.paid > 0.004 }.maxByOrNull { it.paid }?.name
        if (existing != null && !(existing.role == SwRole.OWE && role == SwRole.OWE)) undo(existing)

        var txnId: Long? = null
        var out = stats
        when (role) {
            SwRole.OWE -> {
                // A friend controls these numbers: never more than the expense itself, and capped (R3-06).
                val amount = round2((mine.owed - mine.paid).coerceAtMost(e.cost).coerceAtMost(MAX_AMOUNT))
                val note = ("Paid by ${payer ?: "a friend"} · ${e.description} (Splitwise)").take(200)
                val values = linkedMapOf<String, Any?>(
                    "amount" to amount, "type" to "DEBIT", "counterparty" to (payer ?: "Splitwise"), "source" to "MANUAL",
                    "source_app" to SOURCE_APP, "raw_text" to "", "timestamp" to e.date, "category" to mapCategory(e.category),
                    // Unreviewed: it shows in "Needs your answer" (no pop-up) so a bogus share can be checked.
                    "reviewed" to 0L, "note" to note, "splitwise_id" to swId, "seen_sources" to SOURCE_APP,
                )
                val prev = existing?.txnId?.takeIf { id -> db.query("SELECT id FROM txn WHERE id = ? AND splitwise_id = ?", listOf(id, swId)).isNotEmpty() }
                txnId = if (prev != null) {
                    // Edited on Splitwise: follow amount / date / payer / description, keep the user's own category edits.
                    values.remove("category")
                    // A changed amount or date needs another look (R4-01); an unchanged row keeps the user's answer.
                    val old = db.query("SELECT amount, timestamp FROM txn WHERE id = ?", listOf(prev)).firstOrNull()
                    val changed = old == null || kotlin.math.abs((old.double("amount") ?: 0.0) - amount) > 0.004 || old.long("timestamp") != e.date
                    if (!changed) values.remove("reviewed")
                    db.update("txn", values, "id = ?", listOf(prev)); prev
                } else db.insert("txn", values)
                out = out.copy(owed = out.owed + 1)
            }
            SwRole.PAID -> {
                txnId = match("DEBIT", mine.paid, e.date)
                if (txnId != null) {
                    val forOthers = mine.owed < 0.005
                    val others = e.shares.filter { it.userId != me && it.owed > 0.004 }.joinToString(", ") { it.name }.take(60)
                    db.update(
                        "txn",
                        mapOf(
                            "splitwise_id" to swId, "is_split" to 1L, "my_share" to (if (forOthers) 0.0 else round2(mine.owed.coerceIn(0.0, mine.paid))),
                            "paid_for_others" to (if (forOthers) 1L else 0L), "paid_for" to (if (forOthers) others.ifBlank { null } else null),
                            "reviewed" to 1L,
                        ),
                        "id = ?", listOf(txnId),
                    )
                    out = out.copy(linked = out.linked + 1)
                } else out = out.copy(unmatched = out.unmatched + 1)
            }
            SwRole.SETTLE -> {
                // I paid a friend back (my paid share) → my debit; a friend paid me back (my owed share) → my credit.
                val (type, amount) = if (mine.paid > 0.004) "DEBIT" to mine.paid else "CREDIT" to mine.owed
                txnId = match(type, amount, e.date)
                if (txnId != null) {
                    db.update(
                        "txn",
                        mapOf("splitwise_id" to swId, "is_transfer" to 1L, "transfer_kind" to TransferKind.SPLITWISE_SETTLE.name, "reviewed" to 1L),
                        "id = ?", listOf(txnId),
                    )
                    out = out.copy(settled = out.settled + 1)
                } else out = out.copy(unmatched = out.unmatched + 1)
            }
            SwRole.NONE -> Unit
        }
        val shares = JSONArray().also { a ->
            e.shares.take(20).forEach { a.put(JSONObject().put("name", it.name).put("paid", it.paid).put("owed", it.owed).put("me", it.userId == me)) }
        }
        db.execute(
            "INSERT OR REPLACE INTO splitwise_expense (sw_id, txn_id, role, description, cost, currency, my_paid, my_owed, payer, shares_json, category, group_name, date, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(swId, txnId, role.name, e.description, e.cost, e.currency, mine.paid, mine.owed, payer, shares.toString(), e.category, null, e.date, e.updatedAt),
        )
        return out
    }

    /** Best unlinked payment of [amount] (±₹0.50) within a day either side of [date]; never one already linked. */
    private fun match(type: String, amount: Double, date: Long): Long? {
        val rows = db.query(
            "SELECT id, amount, timestamp FROM txn WHERE type = ? AND splitwise_id IS NULL AND timestamp BETWEEN ? AND ? " +
                "AND NOT (source = 'MANUAL' AND source_app = ?)",
            listOf(type, date - DAY - DAY / 2, date + DAY + DAY / 2, SOURCE_APP),
        )
        // Only an unambiguous match is linked: a friend-recorded expense must not quietly re-label one of several
        // equal payments (R3-06).
        return rows.filter { abs((it.double("amount") ?: -1.0) - amount) < 0.5 }.singleOrNull()?.long("id")
    }

    /** Removes what an expense created locally: deletes an imported row, restores a linked one. */
    private fun undo(r: SwRecord) {
        val id = r.txnId
        if (id != null) when (r.role) {
            SwRole.OWE -> db.delete("txn", "id = ? AND splitwise_id = ? AND source = 'MANUAL'", listOf(id, r.swId))
            SwRole.PAID -> db.update(
                "txn", mapOf("splitwise_id" to null, "is_split" to 0L, "my_share" to null, "paid_for_others" to 0L, "paid_for" to null),
                "id = ? AND splitwise_id = ?", listOf(id, r.swId),
            )
            SwRole.SETTLE -> db.update(
                "txn", mapOf("splitwise_id" to null, "is_transfer" to 0L, "transfer_kind" to null),
                "id = ? AND splitwise_id = ?", listOf(id, r.swId),
            )
            SwRole.NONE -> Unit
        }
        db.delete("splitwise_expense", "sw_id = ?", listOf(r.swId))
    }

    fun record(swId: String): SwRecord? = db.query("SELECT * FROM splitwise_expense WHERE sw_id = ?", listOf(swId)).firstOrNull()?.toRecord()

    fun forTransaction(txnId: Long): SwRecord? =
        db.query("SELECT * FROM splitwise_expense WHERE txn_id = ? LIMIT 1", listOf(txnId)).firstOrNull()?.toRecord()

    fun replaceFriends(friends: List<SwFriend>) = lock.withLock {
        db.transaction {
            db.delete("splitwise_friend", "1 = 1", emptyList())
            friends.forEach { f ->
                db.insert("splitwise_friend", mapOf("friend_id" to f.id.toString(), "name" to f.name, "balance" to f.balance, "currency" to f.currency))
            }
        }
    }

    /** Positive balance: they owe you. Largest first. */
    fun balances(): List<SwFriendBalance> =
        db.query("SELECT name, balance FROM splitwise_friend WHERE ABS(balance) >= 0.005 ORDER BY ABS(balance) DESC").map {
            SwFriendBalance(it.string("name") ?: "Friend", it.double("balance") ?: 0.0)
        }

    /** Disconnect without keeping: imported rows go, linked rows are restored, link records and balances cleared. */
    fun removeAll() = lock.withLock {
        db.transaction {
            db.query("SELECT * FROM splitwise_expense").map { it.toRecord() }.forEach { undo(it) }
            db.delete("txn", "source = 'MANUAL' AND source_app = ?", listOf(SOURCE_APP))
            db.delete("splitwise_friend", "1 = 1", emptyList())
        }
    }

    /** Disconnect but keep: imported rows and links stay; only the friend balances (live data) are cleared. */
    fun forgetBalances() = lock.withLock { db.delete("splitwise_friend", "1 = 1", emptyList()) }

    private fun SqlRow.toRecord(): SwRecord = SwRecord(
        swId = string("sw_id")!!,
        txnId = long("txn_id"),
        role = runCatching { SwRole.valueOf(string("role")!!) }.getOrDefault(SwRole.NONE),
        description = string("description"),
        cost = double("cost") ?: 0.0,
        myPaid = double("my_paid") ?: 0.0,
        myOwed = double("my_owed") ?: 0.0,
        payer = string("payer"),
        shares = string("shares_json")?.let { s ->
            runCatching {
                val a = JSONArray(s)
                (0 until minOf(a.length(), 20)).mapNotNull { i ->
                    (a.opt(i) as? JSONObject)?.let { o -> SwShare(if (o.optBoolean("me")) ME else 0L, o.optString("name"), o.optDouble("paid", 0.0), o.optDouble("owed", 0.0)) }
                }
            }.getOrNull()
        } ?: emptyList(),
        group = string("group_name"),
        date = long("date") ?: 0L,
        updatedAt = string("updated_at"),
    )

    companion object {
        const val SOURCE_APP = "splitwise"
        /** Marker id for "you" in a stored breakdown. */
        const val ME = -1L
        private const val DAY = 86_400_000L
        private const val MAX_AMOUNT = 10_000_000.0

        @Volatile private var instance: SplitwiseStore? = null
        private val instanceLock = ReentrantLock()
        fun get(context: PlatformContext): SplitwiseStore =
            instance ?: instanceLock.withLock { instance ?: SplitwiseStore(openDatabase(context)).also { instance = it } }

        fun resetForRecovery() { instanceLock.withLock { instance = null } }

        private fun round2(v: Double) = kotlin.math.round(v * 100) / 100

        /** Splitwise category name → SpendSense category. */
        fun mapCategory(name: String?): String {
            val n = name?.lowercase() ?: return "Other"
            return when {
                listOf("dining", "food", "restaurant", "liquor", "drink").any { it in n } -> "Food & Dining"
                "grocer" in n -> "Groceries"
                listOf("taxi", "bus", "train", "car", "gas/fuel", "fuel", "parking", "bicycle", "transport").any { it in n } -> "Transport"
                listOf("hotel", "plane", "flight", "travel").any { it in n } -> "Travel"
                listOf("rent", "mortgage").any { it in n } -> "Rent"
                listOf("electric", "heat", "water", "internet", "phone", "utilit", "cleaning", "trash").any { it in n } -> "Bills & Utilities"
                listOf("movie", "game", "music", "sport", "entertain").any { it in n } -> "Entertainment"
                listOf("medical", "health", "insurance").any { it in n } -> "Health"
                listOf("cloth", "household", "electronic", "furniture", "gift", "pets", "shopping").any { it in n } -> "Shopping"
                listOf("education", "tuition", "childcare").any { it in n } -> "Education"
                else -> "Other"
            }
        }
    }
}
