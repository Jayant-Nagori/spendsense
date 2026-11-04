package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class CsvStatementParserTest {

    @Test fun hdfc_csv_export() {
        val csv = """
            HDFC BANK Ltd.,,,,,,
            Account No :50100123456789,,,,,,
            Date,Narration,Chq./Ref.No.,Value Dt,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/04/24,UPI-SWIGGY-swiggy@icici-ICIC0000001-529512345678-PAYMENT FROM PHONE,0000529512345678,01/04/24,250.00,,49750.00
            03/04/24,"NEFT CR-HDFC0001234-ACME TECH PVT LTD-SALARY-N1",N1,03/04/24,,"75,000.00","1,24,750.00"
        """.trimIndent()
        val p = StatementImporter.parseCsvText(csv)
        assertEquals(2, p.entries.size)
        assertEquals("HDFC Bank", p.bank)
        assertEquals("XX6789", p.account)
        assertEquals(TxnType.DEBIT, p.entries[0].txn.type)
        assertEquals("Swiggy", p.entries[0].txn.counterparty)
        assertEquals("529512345678", p.entries[0].txn.upiRef)
        assertEquals(TxnType.CREDIT, p.entries[1].txn.type)
        assertEquals(75000.0, p.entries[1].txn.amount, 0.001)
        assertEquals(PaymentMode.NETBANKING, p.entries[1].txn.paymentMode)
    }

    @Test fun single_amount_with_drcr_column() {
        val csv = """
            Txn Date,Description,Amount,Dr/Cr,Balance
            01-04-2024,UPI/P2M/529512345678/ZEPTO/Pay,250.00,DR,9750.00
            02-04-2024,UPI/P2A/611122223333/Anita Sharma/HDFC,1000.00,CR,10750.00
        """.trimIndent()
        val p = StatementImporter.parseCsvText(csv)
        assertEquals(2, p.entries.size)
        assertEquals(TxnType.DEBIT, p.entries[0].txn.type)
        assertEquals(TxnType.CREDIT, p.entries[1].txn.type)
        assertEquals("Anita Sharma", p.entries[1].txn.counterparty)
    }

    @Test fun debit_credit_headers_semicolon_delimited() {
        val csv = "Date;Particulars;Debit;Credit;Balance\n01-Apr-2024;POS 411234XXXXXX1234 CROMA BANGALORE;4,999.00;;5,001.00\n02-Apr-2024;OPENING BALANCE;;;10,000.00"
        val p = StatementImporter.parseCsvText(csv)
        assertEquals(1, p.entries.size)
        assertEquals(4999.0, p.entries[0].txn.amount, 0.001)
        assertEquals(PaymentMode.CARD, p.entries[0].txn.paymentMode)
    }

    @Test fun csv_without_recognisable_header_is_empty() {
        val p = StatementImporter.parseCsvText("foo,bar\n1,2")
        assertTrue(p.entries.isEmpty())
    }

    @Test fun tokenizer_handles_quotes_and_bom() {
        val rows = CsvStatementParser.tokenize("﻿a,\"b,c\",\"d\"\"e\"\n1,2,3")
        assertEquals(listOf("a", "b,c", "d\"e"), rows[0])
        assertEquals(listOf("1", "2", "3"), rows[1])
    }
}
