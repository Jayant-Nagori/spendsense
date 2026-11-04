package com.jayant.spendsense.splitwise

import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TransferKind
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.stress.JdbcSqlDb
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Fake Splitwise server → API → import into a real SQLite database with the app's schema. */
class SplitwiseImportTest {
    private val db = JdbcSqlDb()
    private val repo = TransactionRepository(db)
    private val store = SplitwiseStore(db)
    private val fake = FakeSplitwiseServer()
    private val api = SplitwiseApi(fake.client, fake.key)
    private val me = 100L

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilliseconds()

    private fun bankDebit(amount: Double, iso: String, ref: String, to: String = "Biryani House") = repo.insertIfNew(
        Transaction(amount = amount, type = TxnType.DEBIT, counterparty = to, account = "XX1234", bank = "HDFC Bank", upiRef = ref,
            source = TxnSource.SMS, sourceApp = "VM-HDFCBK", rawText = "Rs.$amount debited from A/c XX1234 UPI Ref $ref", timestamp = ms(iso)),
    )!!

    private suspend fun sync() = store.apply(api.expenses(), me)

    @Test fun someoneElsePaidImportsMyShareOnceAndFollowsEditsAndDeletes() = runTest {
        fake.expense(11, "Dinner at Toit", 1200.0, "2026-10-20T15:00:00Z", listOf(fake.user(me, "Jay", 0.0, 400.0), fake.user(7, "Rahul", 1200.0, 800.0)))
        assertEquals(1, sync().owed)
        var t = repo.all().single()
        assertEquals(400.0, t.amount); assertEquals(TxnType.DEBIT, t.type); assertFalse(t.reviewed) // checked by the user (R3-06)
        assertEquals("Paid by Rahul · Dinner at Toit (Splitwise)", t.note)
        assertEquals("Food & Dining", t.category); assertEquals("11", t.splitwiseId)
        // Same data again: no duplicate.
        sync(); assertEquals(1, repo.all().size)
        // Edited on Splitwise: amount follows, user's category edit is kept, and it needs another look (R4-01).
        repo.update(t.copy(category = "Entertainment", reviewed = true))
        fake.expense(11, "Dinner at Toit", 1500.0, "2026-10-20T15:00:00Z", listOf(fake.user(me, "Jay", 0.0, 500.0), fake.user(7, "Rahul", 1500.0, 1000.0)), updated = "2026-10-21T09:00:00Z")
        sync()
        t = repo.all().single()
        assertEquals(500.0, t.amount); assertEquals("Entertainment", t.category); assertFalse(t.reviewed)
        // Deleted on Splitwise: gone.
        fake.expense(11, "Dinner at Toit", 1500.0, "2026-10-20T15:00:00Z", listOf(fake.user(me, "Jay", 0.0, 500.0), fake.user(7, "Rahul", 1500.0, 1000.0)),
            updated = "2026-10-22T09:00:00Z", deleted = "2026-10-22T09:00:00Z")
        assertEquals(1, sync().removed)
        assertTrue(repo.all().isEmpty())
    }

    @Test fun iPaidLinksTheMatchingBankDebitOnceAndSetsMyShare() = runTest {
        val a = bankDebit(1200.0, "2026-10-20T19:30:00Z", "431111111111")
        val b = bankDebit(1200.0, "2026-10-25T19:30:00Z", "431111111112") // 5 days later: outside ±1 day
        fake.expense(21, "Groceries", 1200.0, "2026-10-21T00:00:00Z", listOf(fake.user(me, "Jay", 1200.0, 300.0), fake.user(7, "Rahul", 0.0, 900.0)))
        fake.expense(22, "Groceries again", 1200.0, "2026-10-21T00:00:00Z", listOf(fake.user(me, "Jay", 1200.0, 600.0), fake.user(8, "Priya", 0.0, 600.0)))
        val s = sync()
        assertEquals(1, s.linked); assertEquals(1, s.unmatched)
        val linked = repo.get(a.id)!!
        assertEquals("21", linked.splitwiseId); assertTrue(linked.isSplit); assertEquals(300.0, linked.myShare); assertEquals(300.0, linked.effectiveAmount)
        assertNull(repo.get(b.id)!!.splitwiseId) // never matched twice, never outside the window
        assertEquals(2, repo.all().size) // no match creates nothing
        // The breakdown is stored for the detail screen.
        val rec = store.forTransaction(a.id)!!
        assertEquals(SwRole.PAID, rec.role); assertEquals(2, rec.shares.size)
        // Deleted on Splitwise: link undone, the bank row stays.
        fake.expense(21, "Groceries", 1200.0, "2026-10-21T00:00:00Z", listOf(fake.user(me, "Jay", 1200.0, 300.0), fake.user(7, "Rahul", 0.0, 900.0)),
            updated = "2026-10-23T00:00:00Z", deleted = "2026-10-23T00:00:00Z")
        sync()
        val restored = repo.get(a.id)!!
        assertNull(restored.splitwiseId); assertFalse(restored.isSplit); assertEquals(1200.0, restored.effectiveAmount)
    }

    @Test fun settleUpsBecomeTransfersBothWays() = runTest {
        val out = bankDebit(900.0, "2026-10-22T10:00:00Z", "432222222222", to = "Rahul")
        val inc = repo.insertIfNew(Transaction(amount = 600.0, type = TxnType.CREDIT, counterparty = "Priya", account = "XX1234", bank = "HDFC Bank",
            upiRef = "433333333333", source = TxnSource.SMS, sourceApp = "VM-HDFCBK", rawText = "Rs 600 credited", timestamp = ms("2026-10-22T12:00:00Z")))!!
        fake.expense(31, "Payment", 900.0, "2026-10-22T10:05:00Z", listOf(fake.user(me, "Jay", 900.0, 0.0), fake.user(7, "Rahul", 0.0, 900.0)), payment = true)
        fake.expense(32, "Payment", 600.0, "2026-10-22T12:05:00Z", listOf(fake.user(8, "Priya", 600.0, 0.0), fake.user(me, "Jay", 0.0, 600.0)), payment = true)
        assertEquals(2, sync().settled)
        val o = repo.get(out.id)!!; val i = repo.get(inc.id)!!
        assertTrue(o.isTransfer); assertEquals(TransferKind.SPLITWISE_SETTLE, o.transferKind); assertEquals(0.0, o.effectiveAmount)
        assertTrue(i.isTransfer); assertEquals(TransferKind.SPLITWISE_SETTLE, i.transferKind)
    }

    @Test fun otherCurrenciesAndExpensesWithoutMeAreSkipped() = runTest {
        fake.expense(41, "Trip", 100.0, "2026-10-20T10:00:00Z", listOf(fake.user(me, "Jay", 0.0, 50.0), fake.user(7, "R", 100.0, 50.0)), currency = "USD")
        fake.expense(42, "Not mine", 100.0, "2026-10-20T10:00:00Z", listOf(fake.user(7, "R", 100.0, 50.0), fake.user(8, "P", 0.0, 50.0)))
        sync()
        assertTrue(repo.all().isEmpty())
    }

    @Test fun disconnectRemovingEntriesRestoresEverything() = runTest {
        val a = bankDebit(500.0, "2026-10-20T10:00:00Z", "434444444444")
        fake.expense(51, "Cab", 500.0, "2026-10-20T10:10:00Z", listOf(fake.user(me, "Jay", 500.0, 250.0), fake.user(7, "R", 0.0, 250.0)))
        fake.expense(52, "Lunch", 300.0, "2026-10-20T13:00:00Z", listOf(fake.user(me, "Jay", 0.0, 150.0), fake.user(7, "R", 300.0, 150.0)))
        fake.friend(7, "Rahul", 250.0 - 150.0)
        sync(); store.replaceFriends(api.friends())
        assertEquals(1, store.balances().size)
        assertEquals(2, repo.all().size)
        store.removeAll()
        assertEquals(listOf(a.id), repo.all().map { it.id })
        assertFalse(repo.get(a.id)!!.isSplit)
        assertTrue(store.balances().isEmpty())
    }

    @Test fun importedRowsNeverMergeIntoBankDebitsOrPopUp() = runTest {
        // An owed-share import of the same amount at the same time as a bank debit stays separate (not deduped into it).
        val a = bankDebit(400.0, "2026-10-20T15:00:00Z", "435555555555")
        fake.expense(61, "Dinner", 1200.0, "2026-10-20T15:00:00Z", listOf(fake.user(me, "Jay", 0.0, 400.0), fake.user(7, "Rahul", 1200.0, 800.0)))
        sync()
        assertEquals(2, repo.all().size)
        assertNull(repo.get(a.id)!!.splitwiseId)
    }

    @Test fun negativeSharesAreIgnored() = runTest {
        val a = bankDebit(300.0, "2026-10-20T10:00:00Z", "437777777771")
        fake.expense(81, "Odd", 300.0, "2026-10-20T10:05:00Z", listOf(fake.user(me, "Jay", 300.0, -50.0), fake.user(7, "R", 0.0, 350.0)))
        sync()
        assertNull(repo.get(a.id)!!.splitwiseId); assertEquals(300.0, repo.get(a.id)!!.effectiveAmount)
    }

    @Test fun hostileSharesAreCappedAndAmbiguousMatchesAreNotLinked() = runTest {
        fake.expense(71, "Huge", 100.0, "2026-10-20T15:00:00Z", listOf(fake.user(me, "Jay", 0.0, 9_000_000_000.0), fake.user(7, "R", 100.0, 0.0)))
        val a = bankDebit(500.0, "2026-10-21T10:00:00Z", "436666666661")
        val b = bankDebit(500.0, "2026-10-21T18:00:00Z", "436666666662")
        fake.expense(72, "Settle", 500.0, "2026-10-21T12:00:00Z", listOf(fake.user(me, "Jay", 500.0, 0.0), fake.user(7, "R", 0.0, 500.0)), payment = true)
        val s = sync()
        assertEquals(100.0, repo.all().first { it.splitwiseId == "71" }.amount)
        assertEquals(1, s.unmatched)
        assertFalse(repo.get(a.id)!!.isTransfer); assertFalse(repo.get(b.id)!!.isTransfer)
    }
}
