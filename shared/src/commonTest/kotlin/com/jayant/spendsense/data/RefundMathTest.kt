package com.jayant.spendsense.data

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class RefundMathTest {
    private fun debit(amount: Double) = Transaction(
        amount = amount, type = TxnType.DEBIT, counterparty = "Amazon", account = null, bank = null, upiRef = null,
        source = TxnSource.SMS, sourceApp = null, rawText = "", timestamp = 0,
    )

    @Test fun noRefund() = assertEquals(1000.0, debit(1000.0).effectiveAmount, 0.001)

    @Test fun expectedRefundDoesNotReduceSpendYet() =
        assertEquals(1000.0, debit(1000.0).copy(refundStatus = RefundStatus.EXPECTED, refundAmount = 1000.0).effectiveAmount, 0.001)

    @Test fun fullRefundReceived() =
        assertEquals(0.0, debit(1000.0).copy(refundStatus = RefundStatus.RECEIVED, refundAmount = 1000.0).effectiveAmount, 0.001)

    @Test fun partialRefundReceived() =
        assertEquals(600.0, debit(1000.0).copy(refundStatus = RefundStatus.RECEIVED, refundAmount = 400.0).effectiveAmount, 0.001)

    @Test fun refundOnSplitScalesMyShare() =
        assertEquals(250.0, debit(1000.0).copy(isSplit = true, myShare = 500.0, refundStatus = RefundStatus.RECEIVED, refundAmount = 500.0).effectiveAmount, 0.001)

    @Test fun refundCreditFlag() = assertTrue(
        debit(400.0).copy(type = TxnType.CREDIT, refundOfId = 7).isRefundCredit,
    )
}
