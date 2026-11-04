package com.jayant.spendsense.ios

import com.jayant.spendsense.data.NativeDb
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.data.toValues
import com.jayant.spendsense.ingest.OtpClassifier
import com.jayant.spendsense.ingest.TransactionIngestor
import com.jayant.spendsense.ingest.TransactionParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Same load scenarios as the JVM StressTest, on the iOS SQLite driver (simulator). */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class IosStressTest {
    private val t0 = 1_761_150_000_000L
    private val minute = 60_000L

    private fun freshDb(): NativeDb {
        val path = NSTemporaryDirectory() + "stress-" + Random.nextLong().toULong() + ".db"
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        return NativeDb(path)
    }

    private fun count(db: NativeDb) = db.query("SELECT COUNT(*) AS n FROM txn").first().int("n") ?: 0

    private fun debitSms(i: Int) =
        "Rs ${100 + i}.00 debited from a/c **4321 on 22-10-26 to VPA shop$i@okicici (UPI Ref No ${529_500_000_000L + i}). Never share your OTP with anyone."

    private fun sms(repo: TransactionRepository, body: String, ts: Long) {
        val p = TransactionParser.parseSms("VM-HDFCBK", body, ts) ?: return
        TransactionIngestor.toTransaction(p, TxnSource.SMS, "VM-HDFCBK", body, live = true)?.let { repo.insertIfNew(it) }
    }

    private fun notif(repo: TransactionRepository, i: Int, ts: Long) {
        val title = "₹${100 + i}.00 paid to Shop $i"
        val p = TransactionParser.parseNotification("com.google.android.apps.nbu.paisa.user", title, "UPI transaction", ts) ?: return
        TransactionIngestor.toTransaction(p, TxnSource.NOTIFICATION, "gpay", "$title — UPI transaction", live = true)?.let { repo.insertIfNew(it) }
    }

    @Test fun floodAndConcurrentIngest() = runBlocking {
        val db = freshDb()
        val repo = TransactionRepository(db)
        val jobs = buildList<() -> Unit> {
            for (i in 0 until 200) {
                val ts = t0 + i * minute
                add { sms(repo, debitSms(i), ts) }
                add { sms(repo, debitSms(i), ts) }
                add { notif(repo, i, ts + 3_000) }
                add { sms(repo, "${100_000 + i} is OTP for txn of Rs ${100 + i}.00 at SHOP$i. Do not share OTP with anyone.", ts) }
            }
        }.shuffled(Random(5))
        val mark = TimeSource.Monotonic.markNow()
        withContext(Dispatchers.Default) {
            jobs.chunked(jobs.size / 8 + 1).map { chunk -> async { chunk.forEach { it() } } }.awaitAll()
        }
        println("[ios-stress] 800 events on 8 workers: ${mark.elapsedNow()}")
        assertEquals(200, count(db))
        assertFalse(repo.all(10_000).any { OtpClassifier.isOtp(it.rawText) })
    }

    @Test fun fiftyThousandRows() {
        val db = freshDb()
        val repo = TransactionRepository(db)
        val r = Random(9)
        val year = 365L * 24 * 60 * minute
        db.transaction {
            repeat(50_000) { i ->
                db.insert("txn", Transaction(
                    amount = r.nextInt(1, 500_000) / 100.0, type = if (i % 10 == 0) TxnType.CREDIT else TxnType.DEBIT,
                    counterparty = listOf("Swiggy", "Uber", "Amazon", "Zepto")[i % 4], account = "XX1234", bank = "HDFC Bank",
                    upiRef = "R$i", source = TxnSource.SMS, sourceApp = "VM-HDFCBK", rawText = "debited ref R$i",
                    timestamp = t0 - (r.nextDouble() * year).toLong(), reviewed = i % 50 != 1,
                ).toValues())
            }
        }
        assertEquals(50_000, count(db))
        fun timed(label: String, budgetMs: Long, block: () -> Int) {
            val m = TimeSource.Monotonic.markNow()
            val n = block()
            val ms = m.elapsedNow().inWholeMilliseconds
            println("[ios-stress] $label: ${ms}ms")
            assertTrue(n > 0, "$label empty")
            assertTrue(ms < budgetMs, "$label took ${ms}ms")
        }
        timed("dashboard month", 3_000) { repo.between(t0 - 30 * 24 * 60 * minute, t0 + minute).size }
        timed("pending review", 3_000) { repo.pendingReview().size }
        timed("all(5000)", 5_000) { repo.all(5000).size }
        timed("search text", 5_000) { repo.search(text = "swiggy").size }
    }
}
