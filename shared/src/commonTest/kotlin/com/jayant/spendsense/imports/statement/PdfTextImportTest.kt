package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Synthetic text in the shape PDFKit's PDFPage.string returns for an SBI-style statement (CR/LF and U+2028 line
 * breaks, non-breaking spaces, blank lines) feeding the shared parser — the iOS PDF import path.
 */
class PdfTextImportTest {
    private val pdfKitText = "STATE BANK OF INDIA\r\nAccount Number : 00000012345678901 \n" +
        "Txn Date Value Date Description Ref No./Cheque No. Debit Credit Balance\n" +
        "\n" +
        "1 Apr 2024 1 Apr 2024 TO TRANSFER-UPI/DR/409112345678/ZEPTO/YESB/zepto@ybl/Payment- 409112345678 349.00 10,651.00\r\n" +
        "3 Apr 2024 3 Apr 2024 BY TRANSFER-NEFT*ICIC0000001*ACME TECH PVT LTD- 52,000.00 62,651.00\n" +
        "Page 1 of 2\n"

    @Test
    fun pdfKitTextFeedsTheSharedParser() {
        val lines = StatementStore.splitLines(pdfKitText)
        assertEquals("Page 1 of 2", lines.last())
        val p = StatementImporter.parseLines(lines)
        assertEquals(2, p.entries.size)
        val zepto = p.entries[0].txn
        assertEquals(349.0, zepto.amount, 0.001)
        assertEquals(TxnType.DEBIT, zepto.type)
        assertEquals(PaymentMode.UPI, zepto.paymentMode)
        assertEquals("409112345678", zepto.upiRef)
        val salary = p.entries[1].txn
        assertEquals(TxnType.CREDIT, salary.type)
        assertEquals(52000.0, salary.amount, 0.001)
    }
}
