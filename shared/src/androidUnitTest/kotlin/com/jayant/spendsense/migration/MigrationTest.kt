package com.jayant.spendsense.migration

import com.jayant.spendsense.data.DbSchema
import com.jayant.spendsense.data.Instrument
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TransferKind
import com.jayant.spendsense.stress.JdbcSqlDb
import com.jayant.spendsense.orders.OrderRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** v5 (1.2) → v6 (1.3) on a populated database: nothing lost, new columns default sanely, backfill fills them. */
class MigrationTest {
    private fun populatedV5(): JdbcSqlDb = JdbcSqlDb(schema = V5Schema.CREATE).also { db -> V5Schema.SEED.forEach { db.execute(it) } }

    private fun upgrade(db: JdbcSqlDb, from: Int) = db.transaction { DbSchema.upgrade(from).forEach { db.execute(it) } }

    @Test fun v5ToV6KeepsEveryRowAndColumn() {
        val db = populatedV5()
        val before = db.query("SELECT * FROM txn ORDER BY id").map { r -> r.columns().associateWith { r.raw(it) } }
        upgrade(db, 5)
        val after = db.query("SELECT * FROM txn ORDER BY id").map { r -> r.columns().associateWith { r.raw(it) } }
        assertEquals(before.size, after.size)
        before.zip(after).forEach { (b, a) -> b.forEach { (k, v) -> assertEquals(v, a[k], "column $k") } }
        // Additive only: new columns exist with defaults.
        after.forEach { assertEquals(0L, it["is_transfer"]); assertTrue("issuer" in it && it["issuer"] == null) }
        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM order_details").first().int("n"))
        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM investment").first().int("n"))
        for (t in listOf("ignore_rule", "account_pref", "splitwise_expense", "splitwise_friend")) {
            assertEquals(0, db.query("SELECT COUNT(*) AS n FROM $t").first().int("n"), t)
        }
    }

    @Test fun upgradedSchemaMatchesAFreshInstall() {
        val db = populatedV5().also { upgrade(it, 5) }
        val fresh = JdbcSqlDb()
        for (t in listOf("txn", "order_details", "investment", "ignore_rule", "account_pref", "splitwise_expense", "splitwise_friend")) {
            val a = db.query("PRAGMA table_info($t)").map { it.string("name") to it.string("type") }.toSet()
            val b = fresh.query("PRAGMA table_info($t)").map { it.string("name") to it.string("type") }.toSet()
            assertEquals(b, a, t)
        }
    }

    @Test fun upgradeIsIdempotentAcrossVersionChecks() {
        // Only v<6 statements run for a v5 database; a v6 database runs nothing.
        assertTrue(DbSchema.upgrade(6).isEmpty())
        assertEquals(DbSchema.V6, DbSchema.upgrade(5))
    }

    @Test fun backfillFillsCardAndAccountFromStoredFieldsOnly() {
        val db = populatedV5().also { upgrade(it, 5) }
        val repo = TransactionRepository(db)
        val rawBefore = db.query("SELECT id, raw_text FROM txn ORDER BY id").map { it.string("raw_text") }
        assertTrue(repo.backfillAccounts() >= 5)
        val axis = repo.get(1)!!
        assertEquals("Axis Bank", axis.issuer); assertEquals(Instrument.CREDIT_CARD, axis.instrument); assertEquals("4321", axis.last4)
        val upi = repo.get(2)!!
        assertEquals("HDFC Bank", upi.issuer); assertEquals(Instrument.UPI, upi.instrument); assertEquals("1234", upi.last4)
        assertEquals(300.0, upi.effectiveAmount) // split survives
        val ccBill = repo.get(5)!!
        assertTrue(ccBill.isTransfer); assertEquals(TransferKind.CARD_BILL, ccBill.transferKind); assertEquals(0.0, ccBill.effectiveAmount)
        assertEquals(Instrument.CASH, repo.get(6)!!.instrument)
        assertNotNull(repo.get(4)!!.refundOfId)
        // No new raw text stored, and a second run changes nothing.
        assertEquals(rawBefore, db.query("SELECT id, raw_text FROM txn ORDER BY id").map { it.string("raw_text") })
        assertEquals(0, repo.backfillAccounts())
    }

    @Test fun olderChainsStillUpgrade() {
        // A v1 database (pre-refunds) goes all the way to v6.
        val v1 = JdbcSqlDb(schema = listOf(
            "CREATE TABLE txn (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL NOT NULL, type TEXT NOT NULL, counterparty TEXT, account TEXT, bank TEXT, upi_ref TEXT, source TEXT NOT NULL, source_app TEXT, raw_text TEXT NOT NULL, timestamp INTEGER NOT NULL, category TEXT NOT NULL DEFAULT 'Other', is_split INTEGER NOT NULL DEFAULT 0, my_share REAL, reviewed INTEGER NOT NULL DEFAULT 0, note TEXT, payment_mode TEXT)",
            "CREATE TABLE investment (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, asset_type TEXT NOT NULL, platform TEXT, invested_amount REAL NOT NULL, current_value REAL, units REAL, start_date INTEGER, maturity_date INTEGER, interest_rate REAL, is_sip INTEGER NOT NULL DEFAULT 0, sip_amount REAL, notes TEXT, updated_at INTEGER NOT NULL)",
        ))
        v1.execute("INSERT INTO txn (amount, type, source, raw_text, timestamp) VALUES (50.0, 'DEBIT', 'SMS', 'x', 1)")
        upgrade(v1, 1)
        val t = TransactionRepository(v1).all()
        assertEquals(1, t.size); assertFalse(t[0].isTransfer)
        OrderRepository(v1) // order tables exist
    }
}
