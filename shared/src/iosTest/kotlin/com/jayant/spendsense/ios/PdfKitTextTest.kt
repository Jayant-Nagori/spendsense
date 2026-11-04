package com.jayant.spendsense.ios

import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.imports.statement.StatementImporter
import com.jayant.spendsense.imports.statement.StatementStore
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSString
import platform.Foundation.dataUsingEncoding
import platform.UIKit.NSFontAttributeName
import platform.UIKit.UIFont
import platform.UIKit.UIGraphicsPDFRenderer
import platform.UIKit.drawAtPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Renders a synthetic statement to a real PDF on the simulator, then reads it back with PDFKit → shared parser. */
@OptIn(ExperimentalForeignApi::class)
class PdfKitTextTest {
    private val lines = listOf(
        "HDFC BANK LIMITED",
        "Account No : 50100123456789",
        "Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance",
        "01/04/24 UPI-SWIGGY-swiggy@icici-ICIC00 0000529512345678 01/04/24 250.00 49,750.00",
        "02/04/24 UPI-RAMESH KUMAR-9876543210@ybl-SBIN0001234-411122223333-UPI 0000411122223333 02/04/24 1,500.00 48,250.00",
        "03/04/24 NEFT CR-HDFC0001234-ACME TECH PVT LTD-SALARY-N123456789 N123456789 03/04/24 75,000.00 1,23,250.00",
    )

    private fun pdf() = UIGraphicsPDFRenderer(bounds = CGRectMake(0.0, 0.0, 842.0, 595.0)).PDFDataWithActions { ctx ->
        ctx?.beginPage()
        lines.forEachIndexed { i, l ->
            @Suppress("CAST_NEVER_SUCCEEDS")
            (l as NSString).drawAtPoint(CGPointMake(24.0, 40.0 + i * 16.0), withAttributes = mapOf<Any?, Any?>(NSFontAttributeName to UIFont.systemFontOfSize(8.0)))
        }
    }

    @Test
    fun pdfKitExtractsLinesTheSharedParserReads() {
        val text = PdfKitText.read(pdf(), null)
        val p = StatementImporter.parseLines(StatementStore.splitLines(text))
        assertEquals(3, p.entries.size)
        assertEquals(250.0, p.entries[0].txn.amount)
        assertEquals("529512345678", p.entries[0].txn.upiRef)
        assertEquals(TxnType.CREDIT, p.entries[2].txn.type)
    }

    @Test
    fun garbageIsNotAPdf() {
        assertFailsWith<Throwable> {
            @Suppress("CAST_NEVER_SUCCEEDS")
            val data = ("not a pdf" as NSString).dataUsingEncoding(platform.Foundation.NSUTF8StringEncoding)!!
            if (PdfKitText.read(data, null).isBlank()) error("empty")
        }
    }
}
