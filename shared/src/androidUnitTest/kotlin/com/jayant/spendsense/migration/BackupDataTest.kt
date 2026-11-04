package com.jayant.spendsense.migration

import com.jayant.spendsense.backup.BackupData
import com.jayant.spendsense.backup.BackupFormat
import com.jayant.spendsense.data.DbSchema
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.json.JSONObject
import com.jayant.spendsense.stress.JdbcSqlDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class BackupDataTest {
    private fun populated(): JdbcSqlDb = JdbcSqlDb(schema = V5Schema.CREATE).also { db ->
        V5Schema.SEED.forEach { db.execute(it) }
        db.transaction { DbSchema.upgrade(5).forEach { db.execute(it) } }
        TransactionRepository(db).backfillAccounts()
        db.execute("INSERT INTO ignore_rule (sender, template_hash, created_at) VALUES ('in.org.npci.upiapp', 'abc123', 5)")
        db.execute("INSERT INTO account_pref (account_key, nickname, hidden) VALUES ('Axis Bank|CREDIT_CARD|4321', 'Axis Ace', 0)")
    }

    private val settings = linkedMapOf<String, Any?>(
        "ask_split" to true, "chart_type" to "BAR", "ai_model" to "claude-x",
        // Must never be exported even if a caller passes them.
        "ai_api_key_enc" to "sk-ant-SECRET-KEY-123", "splitwise_key" to "SW-SECRET", "email_password" to "app-pass",
    )

    private fun rows(db: JdbcSqlDb) = db.query("SELECT amount, type, counterparty, raw_text, my_share, note, refund_status, issuer, last4, is_transfer FROM txn ORDER BY timestamp")
        .map { r -> r.columns().associateWith { r.raw(it) } }

    @Test fun roundTripRestoresEverythingIntoAnEmptyDatabase() {
        val src = populated()
        val payload = BackupData(src).export(settings, "1.3", 42)
        val file = BackupFormat.seal(payload, "my long passphrase", 100_000)
        val opened = BackupFormat.open(file, "my long passphrase") as? BackupFormat.Opened.Ok ?: fail("did not open")
        val dst = JdbcSqlDb()
        var applied: Map<String, Any?> = emptyMap()
        val r = BackupData(dst).import(opened.payload) { applied = it }
        assertEquals(6, r.added); assertEquals(0, r.duplicates); assertEquals(1, r.orders); assertEquals(1, r.rules)
        assertEquals(rows(src), rows(dst))
        // Refund link and order receipt point at the restored rows.
        val repo = TransactionRepository(dst)
        val credit = repo.all().first { it.type.name == "CREDIT" }
        assertEquals("Myntra", repo.get(credit.refundOfId!!)!!.counterparty)
        val zomato = repo.all().first { it.counterparty == "Zomato" }
        assertEquals(zomato.id, dst.query("SELECT txn_id FROM order_details").first().long("txn_id"))
        assertEquals("Axis Ace", dst.query("SELECT nickname FROM account_pref").first().string("nickname"))
        assertEquals(mapOf("ask_split" to true, "chart_type" to "BAR"), applied) // AI / lock settings never travel (R3-01)
    }

    @Test fun backupNeverContainsKeysOrTokens() {
        val payload = BackupData(populated()).export(settings, "1.3", 42)
        for (secret in listOf("sk-ant-SECRET-KEY-123", "SW-SECRET", "app-pass", "ai_api_key_enc", "splitwise_key", "email_password")) {
            assertFalse(payload.contains(secret), secret)
        }
        assertTrue(BackupData.SETTINGS_ALLOWED.none { it.contains("key") || it.contains("token") || it.contains("password") })
    }

    @Test fun restoringTwiceMergesWithoutDuplicates() {
        val src = populated()
        val payload = BackupData(src).export(emptyMap(), "1.3", 1)
        val r = BackupData(src).import(payload) {}
        assertEquals(0, r.added); assertEquals(6, r.duplicates); assertEquals(0, r.orders)
        assertEquals(6, src.query("SELECT COUNT(*) AS n FROM txn").first().int("n"))
        assertEquals(1, src.query("SELECT COUNT(*) AS n FROM order_details").first().int("n"))
    }

    @Test fun mergeKeepsLocalRowsAndAddsMissingOnes() {
        val src = populated()
        val payload = BackupData(src).export(emptyMap(), "1.3", 1)
        val dst = JdbcSqlDb()
        dst.execute("INSERT INTO txn (amount, type, source, raw_text, timestamp) VALUES (77.0, 'DEBIT', 'MANUAL', '', 99)")
        dst.execute(V5Schema.SEED[0].replace("(1, 2340.0", "(50, 2340.0")) // same payment already on this phone
        val r = BackupData(dst).import(payload) {}
        assertEquals(5, r.added); assertEquals(1, r.duplicates)
        assertEquals(7, dst.query("SELECT COUNT(*) AS n FROM txn").first().int("n"))
    }

    @Test fun olderBackupVersionStillRestores() {
        // A payload written by a v5-era build: no card/account columns, no rules/accounts/Splitwise tables.
        val old = JSONObject(mapOf("format" to 1, "schema" to 5, "app" to "1.2", "created" to 1))
        val arr = com.jayant.spendsense.json.JSONArray()
        arr.put(JSONObject(mapOf("id" to 9, "amount" to 450.0, "type" to "DEBIT", "counterparty" to "Chai", "account" to "XX1234", "bank" to "HDFC Bank",
            "source" to "SMS", "source_app" to "VM-HDFCBK", "raw_text" to "Rs.450 debited from A/c XX1234", "timestamp" to 1761300000000L,
            "category" to "Food & Dining", "reviewed" to 1, "payment_mode" to "UPI", "unknown_future_column" to "ignored")))
        old.put("txn", arr)
        val dst = JdbcSqlDb()
        val r = BackupData(dst).import(old.toString()) {}
        assertEquals(1, r.added)
        val repo = TransactionRepository(dst)
        assertEquals(1, repo.backfillAccounts())
        assertEquals("1234", repo.all().single().last4)
    }

    @Test fun malformedPayloadsAreRejectedOrSkipped() {
        val dst = JdbcSqlDb()
        runCatching { BackupData(dst).import("not json") {} }.onSuccess { fail("accepted junk") }
        runCatching { BackupData(dst).import("{\"format\":99}") {} }.onSuccess { fail("accepted unknown format") }
        val junkRows = "{\"format\":1,\"txn\":[{\"amount\":\"x\"},{\"amount\":1,\"type\":\"DROP TABLE\",\"source\":\"SMS\",\"raw_text\":\"\",\"timestamp\":1},{\"amount\":5,\"type\":\"DEBIT\",\"source\":\"SMS\",\"raw_text\":{\"nested\":1},\"timestamp\":1}]}"
        assertEquals(0, BackupData(dst).import(junkRows) {}.added)
        assertEquals(0, dst.query("SELECT COUNT(*) AS n FROM txn").first().int("n"))
    }

    @Test fun hostileRowsAreSkippedAndCannotBreakTheDatabase() {
        val dst = JdbcSqlDb()
        val ts = 1_761_500_000_000L
        fun row(amount: String, source: String, time: Long) =
            "{\"amount\":$amount,\"type\":\"DEBIT\",\"source\":\"$source\",\"raw_text\":\"r$amount$source$time\",\"timestamp\":$time,\"category\":\"Other\"}"
        val payload = "{\"format\":1,\"txn\":[" + listOf(
            row("10", "X", ts), row("-5", "SMS", ts), row("1e300", "SMS", ts), row("20", "SMS", 99_999_999_999_999L), row("30", "SMS", ts),
        ).joinToString(",") + "],\"ignore_rule\":[{\"sender\":\"HDFCBK\",\"template_hash\":\"not-hex!\"},{\"sender\":\"HDFCBK\",\"template_hash\":\"a1b2c3\",\"created_at\":1}]}"
        val r = BackupData(dst).import(payload) {}
        assertEquals(1, r.added)
        assertEquals(1, r.rules)
        assertEquals(30.0, TransactionRepository(dst).all().single().amount)
        val deep = "{\"format\":1,\"txn\":" + "[".repeat(5000) + "]".repeat(5000) + "}"
        runCatching { BackupData(dst).import(deep) {} }.onSuccess { fail("accepted deeply nested JSON") }
    }
}
