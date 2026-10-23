package com.jayant.spendsense.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupeTest {
    private val t0 = 1_761_200_000_000L

    private fun txn(
        source: TxnSource, raw: String, ts: Long = t0, amount: Double = 1.0, ref: String? = null, app: String? = null,
    ) = Transaction(
        amount = amount, type = TxnType.DEBIT, counterparty = "Ramesh", account = null, bank = null, upiRef = ref,
        source = source, sourceApp = app, rawText = raw, timestamp = ts,
    )

    @Test fun twoSmsForTwoPaymentsOfSameAmountAreBothKept() {
        val first = txn(TxnSource.SMS, "Rs.1 debited ... Ref 111 Avl bal 500.00")
        val second = txn(TxnSource.SMS, "Rs.1 debited ... Ref 222 Avl bal 499.00", ts = t0 + 60_000)
        assertFalse(Dedupe.isSamePayment(first, setOf("SMS"), second))
    }

    @Test fun differentReferenceNumbersAreNeverMerged() {
        val first = txn(TxnSource.SMS, "a", ref = "111")
        val other = txn(TxnSource.NOTIFICATION, "b", ref = "222", ts = t0 + 5_000)
        assertFalse(Dedupe.isSamePayment(first, setOf("SMS"), other))
    }

    @Test fun smsAndAppNotificationForOnePaymentAreMerged() {
        val sms = txn(TxnSource.SMS, "Rs.1 debited ... Ref 111", ref = "111")
        val notif = txn(TxnSource.NOTIFICATION, "Paid ₹1 to Ramesh", ts = t0 + 20_000, app = "com.google.android.apps.nbu.paisa.user")
        assertTrue(Dedupe.isSamePayment(sms, setOf("SMS"), notif))
    }

    @Test fun secondAppNotificationIsNotMergedIntoARowThatAlreadyHasOne() {
        val row = txn(TxnSource.SMS, "Rs.1 debited ... Ref 111")
        val notif2 = txn(TxnSource.NOTIFICATION, "Paid ₹1 to Ramesh", ts = t0 + 90_000)
        assertFalse(Dedupe.isSamePayment(row, setOf("SMS", "NOTIFICATION"), notif2))
    }

    @Test fun identicalNotificationTextForTwoPaymentsMinutesApartStaysTwoPayments() {
        val first = txn(TxnSource.NOTIFICATION, "Paid ₹1 to Ramesh")
        val second = txn(TxnSource.NOTIFICATION, "Paid ₹1 to Ramesh", ts = t0 + 60_000)
        assertFalse(Dedupe.isSamePayment(first, setOf("NOTIFICATION"), second))
    }

    @Test fun exactRedeliveryOfTheSameMessageIsMerged() {
        val first = txn(TxnSource.SMS, "Rs.1 debited ... Ref 111")
        assertTrue(Dedupe.isSamePayment(first, setOf("SMS"), first.copy(timestamp = t0 + 1_000)))
    }

    @Test fun backfilledCopyOfALiveSmsIsMergedEvenWithClockSkew() {
        val live = txn(TxnSource.SMS, "Rs.1 debited ... Avl bal 500.00")
        assertTrue(Dedupe.isSamePayment(live, setOf("SMS"), live.copy(timestamp = t0 + 90_000)))
    }

    @Test fun differentAmountsNeverMatch() {
        assertFalse(Dedupe.isSamePayment(txn(TxnSource.SMS, "a"), setOf("SMS"), txn(TxnSource.NOTIFICATION, "b", amount = 2.0)))
    }

    @Test fun importsAreKeyedByImporter() {
        assertEquals("email", Dedupe.sourceKey(txn(TxnSource.MANUAL, "x", app = "email:hdfcbank.net")))
        assertEquals("statement", Dedupe.sourceKey(txn(TxnSource.MANUAL, "x", app = "statement")))
        assertEquals("manual", Dedupe.sourceKey(txn(TxnSource.MANUAL, "")))
        assertEquals("SMS", Dedupe.sourceKey(txn(TxnSource.SMS, "x")))
    }

    @Test fun emailCopyOfAnSmsPaymentIsMerged() {
        val sms = txn(TxnSource.SMS, "Rs.1 debited")
        val email = txn(TxnSource.MANUAL, "Your a/c debited Rs 1", ts = t0 + 120_000, app = "email:hdfcbank.net")
        assertTrue(Dedupe.isSamePayment(sms, setOf("SMS", "NOTIFICATION"), email))
    }
}
