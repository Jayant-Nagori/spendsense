package com.jayant.spendsense.data

import com.jayant.spendsense.ai.FakeData
import com.jayant.spendsense.ai.LocalAnswerer
import com.jayant.spendsense.ai.NOW
import com.jayant.spendsense.ai.QueryIntent
import com.jayant.spendsense.ai.QueryParser
import com.jayant.spendsense.ai.millis
import com.jayant.spendsense.ingest.TransactionIngestor
import com.jayant.spendsense.ingest.TransactionParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountsTest {
    private fun sms(sender: String, body: String): Transaction {
        val p = TransactionParser.parseSms(sender, body, millis(2026, 10, 20))!!
        return Accounts.derive(TransactionIngestor.toTransaction(p, TxnSource.SMS, sender, body, live = true)!!)
    }

    private fun notif(pkg: String, title: String, text: String): Transaction {
        val p = TransactionParser.parseNotification(pkg, title, text, millis(2026, 10, 20))!!
        return Accounts.derive(TransactionIngestor.toTransaction(p, TxnSource.NOTIFICATION, pkg, "$title — $text", live = true)!!)
    }

    @Test fun recordsIssuerInstrumentAndLast4() {
        val axis = sms("AX-AXISBK", "INR 2,340.00 spent on Axis Bank Credit Card XX4321 at AMAZON on 24-10-26. Avl Lmt INR 1,22,000")
        assertEquals("Axis Bank", axis.issuer); assertEquals(Instrument.CREDIT_CARD, axis.instrument); assertEquals("4321", axis.last4)
        assertEquals("Axis Bank Credit Card ••4321", Accounts.labelForKey(Accounts.key(axis)))
        val upi = sms("VM-HDFCBK", "Rs.450.00 debited from A/c XX1234 on 24-10-26 to VPA chai@okaxis (CHAI WALA). UPI Ref 431234567890")
        assertEquals("HDFC Bank", upi.issuer); assertEquals(Instrument.UPI, upi.instrument); assertEquals("1234", upi.last4)
        val debit = sms("JD-ICICIB", "Rs 1,999 spent using ICICI Bank Debit Card XX7788 at DECATHLON. Avl Bal Rs 45,000")
        assertEquals(Instrument.DEBIT_CARD, debit.instrument); assertEquals("ICICI Bank", debit.issuer)
        val kotak = sms("JD-KOTAKB", "Sent Rs.300.00 from Kotak Bank AC X5566 to rapido@ybl on 24-10-26. UPI Ref 512345678912")
        assertEquals("Kotak", kotak.issuer); assertEquals("5566", kotak.last4)
        val sbiAtm = sms("JD-SBIINB", "Rs 2000 withdrawn at ATM from A/c XX9876 on 24-10-26. Avl Bal Rs 10,000")
        assertEquals(Instrument.CASH, sbiAtm.instrument)
        val onecard = sms("VM-ONECRD", "Rs 799 spent on your OneCard card ending 3300 at SWIGGY")
        assertEquals("OneCard", onecard.issuer); assertEquals(Instrument.CREDIT_CARD, onecard.instrument)
        val gpay = notif("com.google.android.apps.nbu.paisa.user", "₹250 paid to Chai Point", "Paid ₹250.00 to Chai Point. UPI Ref 412345678901")
        assertNull(gpay.issuer); assertEquals(Instrument.UPI, gpay.instrument)
    }

    @Test fun issuerAliasesAreCanonical() {
        assertEquals("Kotak", Accounts.canonicalIssuer("Kotak Bank"))
        assertEquals("IDFC FIRST", Accounts.canonicalIssuer("IDFC First"))
        assertEquals("Amex", Accounts.canonicalIssuer("American Express"))
        assertEquals("Bank of Baroda", Accounts.canonicalIssuer("Bank of Baroda"))
        assertEquals("AU Bank", Accounts.canonicalIssuer("AU Bank"))
        assertEquals("IndusInd", Accounts.canonicalIssuer("IndusInd Bank"))
        assertEquals("Some Coop Bank", Accounts.canonicalIssuer("Some Coop Bank"))
        assertEquals("AX", Accounts.monogram("Axis Bank|CREDIT_CARD|4321"))
        assertEquals("SBI", Accounts.monogram("SBI|UPI|9876"))
    }

    @Test fun cardBillPaymentsAndSelfTransfersAreTransfers() {
        val bill = notif("com.dreamplug.androidapp", "payment successful", "₹12,450 paid to HDFC Bank credit card XX5566 successfully")
        assertTrue(bill.isTransfer); assertEquals(TransferKind.CARD_BILL, bill.transferKind); assertTrue(bill.reviewed)
        assertEquals(0.0, bill.effectiveAmount)
        val self = sms("VM-HDFCBK", "Rs.5000.00 debited from A/c XX1234 for self transfer to A/c XX9999. IMPS Ref 512345678912")
        assertEquals(TransferKind.SELF, self.transferKind)
        val received = sms("VM-HDFCBK", "Payment of Rs 12,450.00 has been received on your HDFC Bank Credit Card XX5566. Thank you")
        assertTrue(received.isTransfer)
        val normal = sms("VM-HDFCBK", "Rs.450.00 debited from A/c XX1234 to VPA chai@okaxis UPI Ref 431234567890")
        assertTrue(!normal.isTransfer)
    }

    @Test fun mergedNotificationTakesBankAndCardFromTheSms() {
        val gpay = notif("com.google.android.apps.nbu.paisa.user", "₹250 paid to Chai Point", "Paid ₹250.00 to Chai Point")
        val sms = sms("AX-AXISBK", "INR 250.00 spent on Axis Bank Credit Card XX4321 via UPI at CHAI POINT. Avl Lmt INR 50,000")
        val fill = Dedupe.accountFill(gpay, sms)
        assertEquals("Axis Bank", fill["issuer"]); assertEquals("4321", fill["last4"]); assertEquals("CREDIT_CARD", fill["instrument"])
        assertEquals("Axis Bank", fill["bank"])
    }

    @Test fun splitFollowsMergesHidesAndTransfers() {
        val t = listOf(
            Transaction(1, 1000.0, TxnType.DEBIT, "A", "XX4321", "Axis Bank", null, TxnSource.SMS, null, "", 1, issuer = "Axis Bank", instrument = Instrument.CREDIT_CARD, last4 = "4321"),
            Transaction(2, 500.0, TxnType.DEBIT, "B", null, null, null, TxnSource.NOTIFICATION, null, "", 2, issuer = null, instrument = Instrument.CREDIT_CARD, last4 = "4321"),
            Transaction(3, 300.0, TxnType.DEBIT, "C", "XX1234", "HDFC Bank", null, TxnSource.SMS, null, "", 3, issuer = "HDFC Bank", instrument = Instrument.UPI, last4 = "1234"),
            Transaction(4, 9000.0, TxnType.DEBIT, "Card bill", null, null, null, TxnSource.SMS, null, "", 4, isTransfer = true),
            Transaction(5, 99.0, TxnType.CREDIT, "D", null, null, null, TxnSource.SMS, null, "", 5),
        )
        val prefs = mapOf("|CREDIT_CARD|4321" to AccountPref("|CREDIT_CARD|4321", mergedInto = "Axis Bank|CREDIT_CARD|4321"),
            "Axis Bank|CREDIT_CARD|4321" to AccountPref("Axis Bank|CREDIT_CARD|4321", nickname = "Axis Ace"))
        val rows = AccountRepository.split(t, prefs)
        assertEquals(2, rows.size)
        assertEquals("Axis Ace", rows[0].label); assertEquals(1500.0, rows[0].amount); assertEquals(2, rows[0].count)
        assertEquals(1500.0 / 1800.0, rows[0].share, 1e-9)
        val hidden = AccountRepository.split(t, prefs + ("HDFC Bank|UPI|1234" to AccountPref("HDFC Bank|UPI|1234", hidden = true)))
        assertEquals(1, hidden.size)
        assertTrue(AccountRepository.belongsTo(t[1], "Axis Bank|CREDIT_CARD|4321", prefs))
        // Merge loops can't hang.
        val loop = mapOf("a" to AccountPref("a", mergedInto = "b"), "b" to AccountPref("b", mergedInto = "a"))
        AccountRepository.resolve("a", loop)
    }

    @Test fun offlineAssistantAnswersCardQuestions() {
        val data = FakeData(listOf(
            Transaction(1, 2340.0, TxnType.DEBIT, "Amazon", "XX4321", "Axis Bank", null, TxnSource.SMS, null, "INR 2,340 spent on Axis Bank Credit Card XX4321", millis(2026, 10, 12), paymentMode = PaymentMode.CARD),
            Transaction(2, 660.0, TxnType.DEBIT, "Swiggy", "XX4321", "Axis Bank", null, TxnSource.SMS, null, "INR 660 spent on Axis Bank Credit Card XX4321", millis(2026, 10, 15), paymentMode = PaymentMode.CARD),
            Transaction(3, 800.0, TxnType.DEBIT, "Blinkit", "XX5566", "HDFC Bank", null, TxnSource.SMS, null, "Rs 800 spent on HDFC Bank Credit Card XX5566", millis(2026, 10, 18), paymentMode = PaymentMode.CARD),
            Transaction(4, 999.0, TxnType.DEBIT, "Old", "XX4321", "Axis Bank", null, TxnSource.SMS, null, "INR 999 spent on Axis Bank Credit Card XX4321", millis(2026, 9, 18), paymentMode = PaymentMode.CARD),
        ))
        val a = LocalAnswerer(data) { NOW }
        assertEquals(QueryIntent.ACCOUNT, QueryParser.parse("how much on my Axis card this month", NOW).intent)
        val axis = a.answer("how much on my Axis card this month").text
        assertTrue(axis.contains("₹3,000") && axis.contains("Axis Bank"), axis)
        val hdfc = a.answer("hdfc card se kitna kharcha").text
        assertTrue(hdfc.contains("₹800") && hdfc.contains("HDFC"), hdfc)
        val split = a.answer("card wise spending this month").text
        assertTrue(split.contains("Axis Bank Credit Card ••4321") && split.contains("HDFC Bank Credit Card ••5566"), split)
        val last = a.answer("how much on axis credit card last month").text
        assertTrue(last.contains("₹999"), last)
    }
}
