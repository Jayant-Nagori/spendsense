package com.jayant.spendsense.ios

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jayant.spendsense.backup.BackupData
import com.jayant.spendsense.backup.BackupFormat
import com.jayant.spendsense.data.Instrument
import com.jayant.spendsense.data.NativeDb
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.migration.V5Schema
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** iOS: a populated 1.2 (v5) database opened by the 1.3 app is upgraded in place, and backups round-trip. */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosMigrationTest {
    private fun path() = (NSTemporaryDirectory() + "mig-" + Random.nextLong().toULong() + ".db").also { NSFileManager.defaultManager.removeItemAtPath(it, null) }

    private fun v5At(path: String) {
        val c = BundledSQLiteDriver().open(path)
        try {
            (V5Schema.CREATE + V5Schema.SEED + "PRAGMA user_version = 5").forEach { sql -> c.prepare(sql).use { st -> while (st.step()) Unit } }
        } finally {
            c.close()
        }
    }

    @Test fun v5DatabaseUpgradesInPlaceWithEveryRow() {
        val p = path()
        v5At(p)
        val db = NativeDb(p)
        assertEquals(6, db.query("PRAGMA user_version").first().int("user_version"))
        assertEquals(6, db.query("SELECT COUNT(*) AS n FROM txn").first().int("n"))
        assertEquals(1, db.query("SELECT COUNT(*) AS n FROM order_details").first().int("n"))
        val repo = TransactionRepository(db)
        assertEquals(300.0, repo.get(2)!!.effectiveAmount)
        assertTrue(repo.backfillAccounts() >= 5)
        assertEquals(Instrument.CREDIT_CARD, repo.get(1)!!.instrument)
        assertTrue(repo.get(5)!!.isTransfer)
    }

    @Test fun backupMadeHereRestoresIntoAFreshDatabase() {
        val src = NativeDb(path().also { v5At(it) })
        val payload = BackupData(src).export(mapOf("ask_split" to true, "ai_api_key_enc" to "never"), "1.3", 1)
        assertTrue(!payload.contains("never"))
        val file = BackupFormat.seal(payload, "a long passphrase", 100_000)
        val opened = BackupFormat.open(file, "a long passphrase") as BackupFormat.Opened.Ok
        val dst = NativeDb(path())
        val r = BackupData(dst).import(opened.payload) {}
        assertEquals(6, r.added)
        assertEquals(0, BackupData(dst).import(opened.payload) {}.added)
    }
}
