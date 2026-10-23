package com.jayant.spendsense.ingest

import com.jayant.spendsense.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Cases found by device QA. */
class QaFollowUpTest {
    @Test fun upiRefundToAccountIsACredit() {
        val p = TransactionParser.parseSms("VM-HDFCBK", "Rs.299.00 refunded to A/c XX4321 on 24-10-26 from AMAZON. UPI Ref 900000000001 -HDFC Bank", 0L)
        assertNotNull(p); assertEquals(TxnType.CREDIT, p!!.type); assertEquals(299.0, p.amount, 0.001)
    }

    @Test fun nameInBracketsAfterVpaIsPreferred() {
        val p = TransactionParser.parseSms(
            "VM-HDFCBK",
            "Rs.10.00 debited from A/c XX4321 on 24-10-26 to VPA chai@okaxis (CHAI WALA). UPI Ref 512345678909. Not you? Call 18002586161 -HDFC Bank",
            0L,
        )
        assertEquals("Chai Wala", p!!.counterparty)
    }
}
