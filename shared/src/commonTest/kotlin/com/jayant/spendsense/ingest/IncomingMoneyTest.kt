package com.jayant.spendsense.ingest

import com.jayant.spendsense.data.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Money someone else paid you must never be read as your payment (user report, 28 Oct). */
class IncomingMoneyTest {
    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val phonepe = "com.phonepe.app"

    private fun type(pkg: String, title: String?, text: String) = TransactionParser.parseNotification(pkg, title, text, 1L)?.type

    @Test fun someoneElsePaidIsMoneyIn() {
        assertEquals(TxnType.CREDIT, type(gpay, "AMITABH SHANKER paid ₹17,570.00", "Tap to view."))
        assertEquals(TxnType.CREDIT, type(gpay, "AMITABH SHANKER paid you ₹17,570.00", "Tap to view."))
        assertEquals(TxnType.CREDIT, type(phonepe, "Rahul Sharma sent ₹800", "Tap to view details"))
        assertEquals(TxnType.CREDIT, type(gpay, "Priya has paid ₹250 to you", "Tap to view"))
    }

    @Test fun yourOwnPaymentsStayDebits() {
        assertEquals(TxnType.DEBIT, type(gpay, "₹17,570.00 paid to AMITABH SHANKER", "Tap to view."))
        assertEquals(TxnType.DEBIT, type(gpay, "You paid ₹500 to Rahul", "Tap to view"))
        assertEquals(TxnType.DEBIT, type(phonepe, "Paid ₹300 to Swiggy", "UPI Ref 612345678901"))
        assertEquals(TxnType.DEBIT, type(phonepe, "Payment successful", "₹120 paid to Chai Point"))
        assertEquals(TxnType.DEBIT, type(gpay, "Rahul paid to Swiggy ₹300", "Tap to view"))
    }

    @Test fun tapToViewIsNeverThePayee() {
        val p = TransactionParser.parseNotification(gpay, "₹99 paid", "Tap to view.", 1L)
        assertNotEquals("View", p?.counterparty)
        assertNotEquals("view", p?.counterparty)
    }

    @Test fun hdfcCreditSmsIsMoneyIn() {
        val p = TransactionParser.parseSms("AD-HDFCBK-S", "Credit Alert!\nRs.17570.00 credited to HDFC Bank A/c XX4249 on 28-10-26 from VPA *bh-1@okaxis (UPI 666755673046)", 1L)
        assertEquals(TxnType.CREDIT, p?.type)
        assertEquals(17570.0, p?.amount)
    }
}
