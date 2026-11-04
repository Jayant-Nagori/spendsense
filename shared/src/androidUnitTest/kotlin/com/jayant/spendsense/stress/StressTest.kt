package com.jayant.spendsense.stress

import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.data.toValues
import com.jayant.spendsense.ingest.OtpClassifier
import com.jayant.spendsense.ingest.TransactionIngestor
import com.jayant.spendsense.ingest.TransactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Load tests on the real repository + parser + ingest path, against SQLite (same SQL the SQLCipher DB runs on
 * device). Prints timings only — never message contents.
 */
class StressTest {
    private val t0 = 1_761_150_000_000L
    private val minute = 60_000L

    private fun debitSms(i: Int) =
        "Rs ${100 + i}.00 debited from a/c **4321 on 22-10-26 to VPA shop$i@okicici (UPI Ref No ${529_500_000_000L + i}). Never share your OTP with anyone."

    private fun gpayTitle(i: Int) = "₹${100 + i}.00 paid to Shop $i"

    private fun otpSms(i: Int) = "${100_000 + i} is OTP for txn of Rs ${100 + i}.00 at SHOP$i on HDFC Bank card 4321. Do not share OTP with anyone."

    private fun sms(repo: TransactionRepository, sender: String, body: String, ts: Long): Transaction? {
        val p = TransactionParser.parseSms(sender, body, ts) ?: return null
        val t = TransactionIngestor.toTransaction(p, TxnSource.SMS, sender, body, live = true) ?: return null
        return repo.insertIfNew(t)
    }

    private fun notif(repo: TransactionRepository, pkg: String, title: String, text: String, ts: Long): Transaction? {
        val p = TransactionParser.parseNotification(pkg, title, text, ts) ?: return null
        val t = TransactionIngestor.toTransaction(p, TxnSource.NOTIFICATION, pkg, "$title — $text", live = true) ?: return null
        return repo.insertIfNew(t)
    }

    private inline fun <T> timed(label: String, block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - start) / 1_000_000
        println("[stress] $label: ${ms}ms")
        return r to ms
    }

    @Test fun paymentFloodOf500MessagesStoresEachPaymentOnce() {
        val db = JdbcSqlDb()
        val repo = TransactionRepository(db)
        val events = buildList {
            for (i in 0 until 200) add { sms(repo, "VM-HDFCBK", debitSms(i), t0 + i * minute) }                              // 200 payments
            for (i in 0 until 200) add { notif(repo, "com.google.android.apps.nbu.paisa.user", gpayTitle(i), "UPI transaction", t0 + i * minute + 5_000) } // same 200 via GPay
            for (i in 0 until 50) add { sms(repo, "VM-HDFCBK", debitSms(i), t0 + i * minute) }                               // 50 re-delivered SMS
            for (i in 0 until 50) add { sms(repo, "VM-HDFCBK", otpSms(i), t0 + i * minute) }                                 // 50 OTPs
        }.shuffled(Random(1))
        assertEquals(500, events.size)
        val (_, ms) = timed("ingest 500 (200 unique, 250 duplicates, 50 OTP)") { events.forEach { it() } }
        assertEquals(200, db.count())
        val all = repo.all(10_000)
        assertFalse(all.any { OtpClassifier.isOtp(it.rawText) })
        assertEquals(200, all.map { it.upiRef ?: it.amount.toString() }.toSet().size)
        assertTrue("flood too slow: ${ms}ms", ms < 20_000)
    }

    @Test fun concurrentIngestFromManyThreadsHasNoDuplicatesOrCrashes() {
        val db = JdbcSqlDb()
        val repo = TransactionRepository(db)
        val jobs = buildList {
            for (i in 0 until 300) {
                val ts = t0 + i * minute
                // Each payment: the bank SMS (delivered twice) and the paying app's notification (re-posted once).
                val pkg = if (i % 2 == 0) "com.google.android.apps.nbu.paisa.user" else "com.phonepe.app"
                add { sms(repo, "VM-HDFCBK", debitSms(i), ts) }
                add { sms(repo, "VM-HDFCBK", debitSms(i), ts) }
                add { notif(repo, pkg, gpayTitle(i), "UPI transaction", ts + 3_000) }
                add { notif(repo, pkg, gpayTitle(i), "UPI transaction", ts + 3_000) }
            }
        }.shuffled(Random(2))
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(jobs.size)
        jobs.forEach { job -> pool.execute { try { start.await(); job() } catch (t: Throwable) { errors += t } finally { done.countDown() } } }
        timed("concurrent ingest 1200 events on 8 threads") {
            start.countDown()
            assertTrue("timed out", done.await(120, TimeUnit.SECONDS))
        }
        pool.shutdown()
        assertTrue("errors: ${errors.map { it::class.simpleName }}", errors.isEmpty())
        assertEquals(300, db.count())
        val refs = repo.all(10_000).mapNotNull { it.upiRef }
        assertEquals(refs.size, refs.toSet().size)
    }

    @Test fun fiftyThousandTransactionsStayFast() {
        val db = JdbcSqlDb()
        val repo = TransactionRepository(db)
        val r = Random(3)
        val merchants = listOf("Swiggy", "Zomato", "Uber", "Amazon", "Blinkit", "Zepto", "DMart", "Ola", "IRCTC", "Netflix")
        val year = 365L * 24 * 60 * minute
        timed("seed 50k rows") {
            db.transaction {
                repeat(50_000) { i ->
                    val m = merchants[r.nextInt(merchants.size)]
                    val t = Transaction(
                        amount = (r.nextInt(1, 500_000) / 100.0), type = if (i % 10 == 0) TxnType.CREDIT else TxnType.DEBIT,
                        counterparty = m, account = "XX1234", bank = "HDFC Bank", upiRef = "R$i", source = TxnSource.SMS,
                        sourceApp = "VM-HDFCBK", rawText = "Rs debited to $m ref R$i", timestamp = t0 - (r.nextDouble() * year).toLong(),
                        category = "Food", reviewed = i % 50 != 1,
                    )
                    db.insert("txn", t.toValues())
                }
            }
        }
        assertEquals(50_000, db.count())
        val monthStart = t0 - 30 * 24 * 60 * minute
        val budgets = mutableListOf<Pair<String, Long>>()
        fun check(label: String, limitMs: Long, block: () -> Int) {
            val (n, ms) = timed(label) { block() }
            budgets += label to ms
            assertTrue("$label returned nothing", n > 0)
            assertTrue("$label took ${ms}ms (budget ${limitMs}ms)", ms < limitMs)
        }
        check("dashboard month (between)", 2_000) { repo.between(monthStart, t0 + minute).size }
        check("pending review", 2_000) { repo.pendingReview().size }
        check("activity list all(5000)", 3_000) { repo.all(5000).size }
        check("search text 'swiggy'", 3_000) { repo.search(text = "swiggy").size }
        check("search amount", 3_000) { repo.search(amount = 1234.0).size + 1 }
        check("100 deduped inserts into 50k", 5_000) {
            (0 until 100).count { i -> repo.insertIfNew(Transaction(amount = 77.0 + i, type = TxnType.DEBIT, counterparty = "New", account = null, bank = null,
                upiRef = "NEW$i", source = TxnSource.SMS, sourceApp = "X", rawText = "new $i", timestamp = t0 + i * minute)) != null }
        }
    }

    @Test fun searchIsNotInjectableAndTreatsWildcardsLiterally() {
        val db = JdbcSqlDb()
        val repo = TransactionRepository(db)
        fun add(cp: String, ref: String) = repo.insertIfNew(Transaction(amount = 10.0 + db.count(), type = TxnType.DEBIT, counterparty = cp, account = null,
            bank = null, upiRef = ref, source = TxnSource.SMS, sourceApp = "X", rawText = "paid to $cp", timestamp = t0 + db.count() * 3_600_000L))!!
        add("Robert'); DROP TABLE txn;--", "A1")
        add("100% Pure Juice", "A2")
        add("under_score cafe", "A3")
        add("Plain Cafe", "A4")
        listOf("'; DROP TABLE txn; --", "\" OR 1=1 --", "') OR ('1'='1", "%' OR '1'='1", "\\", "'", "%%", "1; DELETE FROM txn").forEach { evil ->
            repo.search(text = evil)
        }
        assertEquals(4, db.count())
        assertEquals(listOf("Robert'); DROP TABLE txn;--"), repo.search(text = "DROP TABLE").map { it.counterparty })
        assertEquals(listOf("100% Pure Juice"), repo.search(text = "%").map { it.counterparty })
        assertEquals(listOf("under_score cafe"), repo.search(text = "_").map { it.counterparty })
        assertEquals(2, repo.search(text = "cafe").size)
        assertEquals(0, repo.search(text = "' OR '1'='1").size)
        assertEquals(0, repo.search(category = "Other' OR '1'='1").size)
    }
}
