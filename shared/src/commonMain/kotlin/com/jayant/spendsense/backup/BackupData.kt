package com.jayant.spendsense.backup

import com.jayant.spendsense.data.DbSchema
import com.jayant.spendsense.data.Settings
import com.jayant.spendsense.data.SqlDb
import com.jayant.spendsense.data.SqlRow
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.json.JSONArray
import com.jayant.spendsense.json.JSONObject
import kotlin.math.abs

/**
 * What goes into a backup and how it is merged back. Contains transactions (with their splits, notes, refunds,
 * card/account and Splitwise links), order receipts, "not a payment" rules, account nicknames, Splitwise expense
 * links and an allow-list of plain settings. Never API keys, tokens, passwords or the database key: those live in
 * the Keystore / Keychain and are not read here at all.
 */
class BackupData(private val db: SqlDb) {

    data class Restored(val added: Int, val duplicates: Int, val orders: Int, val rules: Int)

    class BadBackup(message: String) : Exception(message)

    fun export(settings: Map<String, Any?>, appVersion: String, now: Long): String {
        val root = JSONObject()
        root.put("format", PAYLOAD_FORMAT)
        root.put("schema", DbSchema.VERSION)
        root.put("app", appVersion)
        root.put("created", now)
        for (t in TABLES) root.put(t, JSONArray().also { arr -> db.query("SELECT * FROM $t").forEach { arr.put(rowJson(it)) } })
        root.put("settings", JSONObject().also { o -> settings.forEach { (k, v) -> if (k in SETTINGS_ALLOWED) o.put(k, v) } })
        return root.toString()
    }

    /** Merges [payload] into the database; rows already present are skipped. Throws [BadBackup] on a malformed payload. */
    fun import(payload: String, applySettings: (Map<String, Any?>) -> Unit): Restored {
        if (payload.length > BackupFormat.MAX_FILE_BYTES) throw BadBackup("too large")
        // The parser is recursive: a deeply nested file would overflow the stack before any check runs.
        runCatching { com.jayant.spendsense.orders.checkJsonDepth(payload, 24) }.onFailure { throw BadBackup("nested too deeply") }
        val root = runCatching { JSONObject(payload) }.getOrElse { throw BadBackup("not a SpendSense backup") }
        if (root.optInt("format", 0) !in 1..PAYLOAD_FORMAT) throw BadBackup("unsupported backup format")
        val txns = root.optJSONArray("txn") ?: JSONArray()
        if (txns.length() > MAX_ROWS) throw BadBackup("too many rows")
        var added = 0
        var dup = 0
        var orders = 0
        var rules = 0
        db.transaction {
            val txnCols = columns("txn")
            // One pass over what's already stored, so a hostile file of rows sharing one timestamp stays linear (R3-02).
            val seenRefs = HashSet<String>()
            val seenKeys = HashSet<String>()
            db.query("SELECT type, amount, timestamp, raw_text, upi_ref FROM txn").forEach { r ->
                val type = r.string("type").orEmpty()
                r.string("upi_ref")?.takeIf { it.isNotBlank() }?.let { seenRefs += "$type|$it" }
                seenKeys += key(type, r.double("amount") ?: 0.0, r.long("timestamp") ?: 0L, r.string("raw_text").orEmpty())
            }
            val idMap = HashMap<Long, Long>()
            val refundLinks = ArrayList<Pair<Long, Long>>() // new credit id -> old purchase id
            for (i in 0 until txns.length()) {
                val o = txns.opt(i) as? JSONObject ?: continue
                val values = clean(o, txnCols, exclude = setOf("id")) ?: continue
                val amount = (values["amount"] as? Number)?.toDouble() ?: continue
                val type = values["type"] as? String ?: continue
                val ts = (values["timestamp"] as? Number)?.toLong() ?: continue
                if (type !in setOf("DEBIT", "CREDIT") || values["raw_text"] !is String) continue
                if (TxnSource.entries.none { it.name == values["source"] }) continue
                // Out-of-range amounts or dates would skew every total.
                if (!(amount > 0.0 && amount <= MAX_AMOUNT) || ts !in MIN_TS..MAX_TS) continue
                val ref = (values["upi_ref"] as? String)?.takeIf { it.isNotBlank() }
                val k = key(type, amount, ts, values["raw_text"] as String)
                val oldId = (o.opt("id") as? Number)?.toLong()
                if ((ref != null && "$type|$ref" in seenRefs) || k in seenKeys) {
                    dup++
                    if (oldId != null) findExisting(type, amount, ts, values["raw_text"] as String, ref)?.let { idMap[oldId] = it }
                    continue
                }
                seenKeys += k
                if (ref != null) seenRefs += "$type|$ref"
                val refundOf = (values.remove("refund_of_id") as? Number)?.toLong()
                val newId = db.insert("txn", values)
                added++
                if (oldId != null) idMap[oldId] = newId
                if (refundOf != null) refundLinks += newId to refundOf
            }
            for ((credit, oldPurchase) in refundLinks) {
                idMap[oldPurchase]?.let { db.update("txn", mapOf("refund_of_id" to it), "id = ?", listOf(credit)) }
            }
            orders = mergeTable(root, "order_details", unique = listOf("source", "order_id"), idMap)
            rules = mergeTable(root, "ignore_rule", unique = listOf("sender", "template_hash"), idMap)
            mergeTable(root, "account_pref", unique = listOf("account_key"), idMap)
            mergeTable(root, "splitwise_expense", unique = listOf("sw_id"), idMap)
        }
        root.optJSONObject("settings")?.let { s ->
            val m = LinkedHashMap<String, Any?>()
            for (k in s.keySet()) if (k in SETTINGS_ALLOWED) m[k] = s.opt(k)
            applySettings(m)
        }
        return Restored(added, dup, orders, rules)
    }

    private fun key(type: String, amount: Double, ts: Long, raw: String) =
        "$type|${kotlin.math.round(amount * 100).toLong()}|$ts|${com.jayant.spendsense.data.Dedupe.fingerprint(raw)}"

    private fun findExisting(type: String, amount: Double, ts: Long, raw: String, ref: String?): Long? {
        if (!ref.isNullOrBlank()) {
            db.query("SELECT id FROM txn WHERE upi_ref = ? AND type = ? LIMIT 1", listOf(ref, type)).firstOrNull()?.long("id")?.let { return it }
        }
        return db.query("SELECT id, amount, raw_text FROM txn WHERE type = ? AND timestamp = ? LIMIT 50", listOf(type, ts))
            .firstOrNull { abs((it.double("amount") ?: -1.0) - amount) < 0.005 && it.string("raw_text") == raw }?.long("id")
    }

    /** Inserts rows of [table] whose [unique] columns aren't present yet; `txn_id` is remapped. Returns rows added. */
    private fun mergeTable(root: JSONObject, table: String, unique: List<String>, idMap: Map<Long, Long>): Int {
        val arr = root.optJSONArray(table) ?: return 0
        if (arr.length() > MAX_ROWS) throw BadBackup("too many rows")
        val cols = columns(table)
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.opt(i) as? JSONObject ?: continue
            val values = clean(o, cols, exclude = setOf("id")) ?: continue
            if (unique.any { values[it] == null }) continue
            if (table == "ignore_rule" && !validRule(values)) continue
            val where = unique.joinToString(" AND ") { "$it = ?" }
            if (db.query("SELECT 1 AS x FROM $table WHERE $where LIMIT 1", unique.map { values[it] }).isNotEmpty()) continue
            if ("txn_id" in values) {
                val old = (values["txn_id"] as? Number)?.toLong()
                val mapped = old?.let { idMap[it] }
                // A receipt whose payment didn't come back stays unmatched; it must never attach to another payment.
                values["txn_id"] = mapped
                if (mapped != null && table == "order_details" &&
                    db.query("SELECT 1 AS x FROM order_details WHERE txn_id = ? LIMIT 1", listOf(mapped)).isNotEmpty()
                ) values["txn_id"] = null
            }
            runCatching { db.insert(table, values) }.onSuccess { n++ }
        }
        return n
    }

    /** A restored "not a payment" rule must look like one the app wrote: short sender, 64-bit hex template hash. */
    private fun validRule(values: Map<String, Any?>): Boolean {
        val sender = values["sender"] as? String ?: return false
        val hash = values["template_hash"] as? String ?: return false
        return sender.length in 1..120 && hash.length in 1..16 && hash.all { it in '0'..'9' || it in 'a'..'f' }
    }

    private fun columns(table: String): Set<String> =
        db.query("PRAGMA table_info($table)").mapNotNull { it.string("name") }.toSet()

    /** Only known columns, only scalar values, strings bounded. Null when the object holds something unexpected. */
    private fun clean(o: JSONObject, cols: Set<String>, exclude: Set<String>): MutableMap<String, Any?>? {
        val m = LinkedHashMap<String, Any?>()
        for (k in o.keySet()) {
            if (k !in cols || k in exclude) continue
            m[k] = when (val v = o.opt(k)) {
                null, JSONObject.NULL -> null
                is String -> if (v.length > MAX_STRING) return null else v
                is Int -> v.toLong()
                is Long -> v
                is Double -> if (v.isNaN() || v.isInfinite()) return null else v
                is Float -> v.toDouble()
                is Number -> v.toDouble()
                is Boolean -> if (v) 1L else 0L
                else -> return null
            }
        }
        return m
    }

    private fun rowJson(r: SqlRow): JSONObject = JSONObject().also { o ->
        r.columns().forEach { c ->
            o.put(c, when {
                r.isNull(c) -> JSONObject.NULL
                r.raw(c) is ByteArray -> r.string(c)
                else -> r.raw(c)
            })
        }
    }

    companion object {
        const val PAYLOAD_FORMAT = 1

        /** Cheap structural check, done before the recovery screen sets the unreadable database aside (R3-03). */
        fun looksValid(payload: String): Boolean = runCatching {
            val root = JSONObject(payload)
            root.optInt("format", 0) in 1..PAYLOAD_FORMAT && (root.optJSONArray("txn")?.length() ?: 0) <= MAX_ROWS
        }.getOrDefault(false)
        private const val MAX_ROWS = 200_000
        private const val MAX_STRING = 20_000
        private const val MAX_AMOUNT = 1_000_000_000.0
        private const val MIN_TS = 946_684_800_000L // 2000-01-01
        private const val MAX_TS = 4_102_444_800_000L // 2100-01-01
        private val TABLES = listOf("txn", "order_details", "ignore_rule", "account_pref", "splitwise_expense")

        /** Plain preferences that are safe to carry over. Keys, tokens and passwords are never in this list. */
        /**
         * Plain preferences that are safe to carry over. Keys, tokens and passwords are never in this list, and neither
         * is anything that weakens protection or redirects a secret (app lock, AI provider / base URL), because a
         * backup file can come from someone else (R3-01).
         */
        val SETTINGS_ALLOWED = setOf("ask_split", "payment_popup", "chart_type", "splitwise_push")
    }
}

/** Settings ⇄ backup map (allow-listed keys only). */
fun Settings.backupSnapshot(): Map<String, Any?> = linkedMapOf(
    "ask_split" to askSplitAfterPayment, "payment_popup" to paymentPopup, "chart_type" to chartType.name, "splitwise_push" to splitwisePush,
)

fun Settings.restoreSnapshot(m: Map<String, Any?>) {
    (m["ask_split"] as? Boolean)?.let { askSplitAfterPayment = it }
    (m["payment_popup"] as? Boolean)?.let { paymentPopup = it }
    (m["chart_type"] as? String)?.let { chartType = com.jayant.spendsense.data.ChartType.fromName(it) }
    (m["splitwise_push"] as? Boolean)?.let { splitwisePush = it }
}
