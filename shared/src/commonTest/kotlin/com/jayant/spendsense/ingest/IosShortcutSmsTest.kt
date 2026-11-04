package com.jayant.spendsense.ingest

import com.jayant.spendsense.data.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Messages as a Shortcuts automation forwards them (sender may be blank on iOS). */
class IosShortcutSmsTest {
    private val ts = 1_761_300_000_000L

    @Test fun iciciDebitParsesWithAndWithoutSender() {
        val body = "ICICI Bank Acct XX789 debited for Rs 349.00 on 23-Oct-26; BLINKIT credited. UPI:529612340000. Call 18002662 for dispute."
        for (sender in listOf("VK-ICICIT", "")) {
            val p = assertNotNull(TransactionParser.parseSms(sender, body, ts), "sender=$sender")
            assertEquals(349.0, p.amount)
            assertEquals(TxnType.DEBIT, p.type)
        }
    }

    @Test fun axisDebitParses() {
        val body = "INR 899.00 debited A/c no. XX2468 23-10-26, 19:41:05 UPI/P2M/529712345678/Uber India Not you? Axis Bank"
        val p = assertNotNull(TransactionParser.parseSms("AX-AXISBK", body, ts))
        assertEquals(899.0, p.amount)
    }
}
