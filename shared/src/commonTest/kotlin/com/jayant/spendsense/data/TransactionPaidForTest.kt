package com.jayant.spendsense.data

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class TransactionPaidForTest {
    private fun t(amount: Double = 250.0, type: TxnType = TxnType.DEBIT) = Transaction(
        amount = amount, type = type, counterparty = "chai@okaxis", account = null, bank = null, upiRef = null,
        source = TxnSource.SMS, sourceApp = null, rawText = "", timestamp = 0L,
    )

    @Test fun paidForOthersCostsMeNothingAndTheyOweTheFullAmount() {
        val x = t().withPaidFor("  Rahul ")
        assertTrue(x.paidForOthers && x.isSplit && x.reviewed)
        assertEquals(0.0, x.myShare!!, 0.0)
        assertEquals(0.0, x.effectiveAmount, 0.0)
        assertEquals(250.0, x.othersOwe, 0.0)
        assertEquals("Rahul", x.paidForName)
    }

    @Test fun blankNameIsAllowed() {
        val x = t().withPaidFor("   ")
        assertTrue(x.paidForOthers)
        assertNull(x.paidFor)
        assertNull(x.paidForName)
        assertEquals(0.0, x.effectiveAmount, 0.0)
    }

    @Test fun nameIsCappedAt60() = assertEquals(60, t().withPaidFor("x".repeat(100)).paidFor!!.length)

    @Test fun ordinaryAndSplitPaymentsAreUnchanged() {
        assertEquals(250.0, t().effectiveAmount, 0.0)
        assertEquals(0.0, t().othersOwe, 0.0)
        val split = t().copy(isSplit = true, myShare = 100.0)
        assertEquals(100.0, split.effectiveAmount, 0.0)
        assertEquals(150.0, split.othersOwe, 0.0)
        assertNull(split.paidForName)
        assertFalse(split.paidForOthers)
    }

    @Test fun nameOnlyCountsWhenFlagged() = assertNull(t().copy(paidFor = "Rahul").paidForName)

    @Test fun creditsOweNothing() = assertEquals(0.0, t(type = TxnType.CREDIT).withPaidFor("A").othersOwe, 0.0)

    @Test fun refundDoesNotMakeItCostMoney() {
        val x = t().withPaidFor("A").copy(refundStatus = RefundStatus.RECEIVED, refundAmount = 100.0)
        assertEquals(0.0, x.effectiveAmount, 0.0)
    }
}
