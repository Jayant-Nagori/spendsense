package com.jayant.spendsense.migration

import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TransactionRepository
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.stress.JdbcSqlDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotAPaymentTest {
    private val db = JdbcSqlDb()
    private val repo = TransactionRepository(db)

    private fun t(raw: String, sender: String, source: TxnSource, amount: Double = 5.0, ts: Long = 1) =
        Transaction(amount = amount, type = TxnType.DEBIT, counterparty = "X", account = null, bank = null, upiRef = null, source = source,
            sourceApp = sender, rawText = raw, timestamp = ts)

    @Test fun notAPaymentRemovesTheRowAndIgnoresSimilarAlertsFromThatSender() {
        val row = repo.insertIfNew(t("You won ₹5 BHIM Rewards — claim ₹5 more", "in.org.npci.upiapp", TxnSource.NOTIFICATION))!!
        assertTrue(repo.markNotPayment(row.id))
        assertTrue(repo.all().isEmpty())
        // Same template, different amount: ignored. Same text from another sender: not ignored.
        assertTrue(repo.isIgnored(TxnSource.NOTIFICATION, "in.org.npci.upiapp", "You won ₹15 BHIM Rewards — claim ₹15 more"))
        assertFalse(repo.isIgnored(TxnSource.NOTIFICATION, "com.phonepe.app", "You won ₹15 BHIM Rewards — claim ₹15 more"))
        assertFalse(repo.isIgnored(TxnSource.NOTIFICATION, "in.org.npci.upiapp", "Paid ₹15 to Chai Point. UPI Ref 412345678901"))
        // SMS senders match across operator prefixes; only a fingerprint is stored, never the text.
        val sms = repo.insertIfNew(t("Rs 99 cashback offer on card XX12", "VM-HDFCBK-S", TxnSource.SMS, ts = 2))!!
        repo.markNotPayment(sms.id)
        assertTrue(repo.isIgnored(TxnSource.SMS, "AD-HDFCBK", "Rs 199 cashback offer on card XX34"))
        val stored = db.query("SELECT * FROM ignore_rule").flatMap { r -> r.columns().map { r.string(it).orEmpty() } }
        assertTrue(stored.none { it.contains("cashback") || it.contains("BHIM") })
        assertEquals(2, repo.ignoreRuleCount())
    }

    @Test fun aRealDebitIsDeletedButNeverBecomesARule() {
        // A spoofed debit in the bank's real format: tapping "Not a payment" must not silence every later real alert (R3-05).
        val row = repo.insertIfNew(t("Rs.450.00 debited from A/c XX1234 to VPA chai@okaxis UPI Ref 431234567890", "VM-HDFCBK", TxnSource.SMS))!!
        assertFalse(repo.markNotPayment(row.id))
        assertTrue(repo.all().isEmpty())
        assertFalse(repo.isIgnored(TxnSource.SMS, "VM-HDFCBK", "Rs.990.00 debited from A/c XX1234 to VPA x@okaxis UPI Ref 431234567891"))
    }

    @Test fun manualEntriesGetNoRule() {
        val row = repo.insertIfNew(t("", "manual", TxnSource.MANUAL))!!
        assertFalse(repo.markNotPayment(row.id))
        assertEquals(0, repo.ignoreRuleCount())
    }

    @Test fun aRuleNeverDropsAMessageThatProvesADebit() {
        // A rule restored from a backup (or a colliding template) must not swallow real alerts.
        val real = "Rs.990.00 debited from A/c XX1234 to VPA x@okaxis UPI Ref 431234567891"
        db.insert("ignore_rule", mapOf("sender" to "HDFCBK", "template_hash" to com.jayant.spendsense.data.IgnoreRules.hash(real), "created_at" to 1L))
        assertFalse(repo.isIgnored(TxnSource.SMS, "VM-HDFCBK", real))
    }

    @Test fun bankCreditSmsCorrectsANotificationReadAsADebit() {
        val wrong = repo.insertIfNew(t("AMITABH SHANKER paid ₹17,570.00. Tap to view.", "com.google.android.apps.nbu.paisa.user", TxnSource.NOTIFICATION, amount = 17570.0, ts = 1_000))!!
        val sms = Transaction(amount = 17570.0, type = TxnType.CREDIT, counterparty = "bh-1@okaxis", account = "XX4249", bank = "HDFC Bank",
            upiRef = "666755673046", source = TxnSource.SMS, sourceApp = "AD-HDFCBK-S", rawText = "Rs.17570.00 credited to HDFC Bank A/c XX4249", timestamp = 61_000)
        assertEquals(wrong.id, repo.correctFromBank(sms)?.id)
        val row = repo.all().single()
        assertEquals(TxnType.CREDIT, row.type)
        assertEquals("666755673046", row.upiRef)
        // The corrected row now carries the bank message, so a later contradicting report never flips it back.
        assertEquals(null, repo.correctFromBank(sms.copy(type = TxnType.DEBIT, upiRef = null)))
    }
}
