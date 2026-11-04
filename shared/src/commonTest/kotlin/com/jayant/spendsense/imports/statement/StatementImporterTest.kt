package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import com.jayant.spendsense.time.localDateOf
import kotlinx.datetime.LocalDate

/** Synthetic statement lines modelled on the layouts PDFTextStripper produces for each bank. */
class StatementImporterTest {

    private fun day(ms: Long): LocalDate = localDateOf(ms, StatementDates.IST)

    // ---- HDFC savings: Date Narration Chq/Ref ValueDt Withdrawal Deposit Balance --------------------------

    private val hdfc = listOf(
        "HDFC BANK LIMITED",
        "Statement of account",
        "Account No : 50100123456789",
        "Date Narration Chq./Ref.No. Value Dt Withdrawal Amt. Deposit Amt. Closing Balance",
        "01/04/24 UPI-SWIGGY-swiggy@icici-ICIC00 0000529512345678 01/04/24 250.00 49,750.00",
        "00001-529512345678-PAYMENT FROM PHONE",
        "02/04/24 UPI-RAMESH KUMAR-9876543210@ybl-SBIN0001234-411122223333-UPI 0000411122223333 02/04/24 1,500.00 48,250.00",
        "03/04/24 NEFT CR-HDFC0001234-ACME TECH PVT LTD-SALARY-N123456789 N123456789 03/04/24 75,000.00 1,23,250.00",
        "05/04/24 ATM WDL 12345678 05/04/24 5,000.00 1,18,250.00",
        "Page 1 of 3",
    )

    @Test fun hdfc_wrapped_upi_narration_joins_and_extracts_rrn() {
        val p = StatementImporter.parseLines(hdfc)
        assertEquals(4, p.entries.size)
        val e = p.entries[0]
        assertEquals(250.0, e.txn.amount, 0.001)
        assertEquals(TxnType.DEBIT, e.txn.type)
        assertEquals("Swiggy", e.txn.counterparty)
        assertEquals("529512345678", e.txn.upiRef)
        assertEquals(PaymentMode.UPI, e.txn.paymentMode)
        assertEquals("HDFC Bank", e.txn.bank)
        assertEquals("XX6789", e.txn.account)
        assertEquals(LocalDate(2024, 4, 1), day(e.txn.timestamp))
    }

    @Test fun hdfc_person_upi_payment() {
        val e = StatementImporter.parseLines(hdfc).entries[1]
        assertEquals("Ramesh Kumar", e.txn.counterparty)
        assertEquals("411122223333", e.txn.upiRef)
        assertEquals(1500.0, e.txn.amount, 0.001)
    }

    @Test fun hdfc_credit_detected_from_balance_increase() {
        val e = StatementImporter.parseLines(hdfc).entries[2]
        assertEquals(TxnType.CREDIT, e.txn.type)
        assertEquals(75000.0, e.txn.amount, 0.001)
        assertEquals(PaymentMode.NETBANKING, e.txn.paymentMode)
        assertEquals("Acme Tech Pvt Ltd", e.txn.counterparty)
    }

    @Test fun hdfc_atm_withdrawal_is_cash() {
        val e = StatementImporter.parseLines(hdfc).entries[3]
        assertEquals(PaymentMode.CASH, e.txn.paymentMode)
        assertEquals("ATM", e.txn.counterparty)
        assertEquals(TxnType.DEBIT, e.txn.type)
    }

    @Test fun page_footer_is_not_appended_to_last_record() {
        val p = StatementImporter.parseLines(hdfc)
        assertFalse(p.entries.last().description.contains("Page"))
    }

    // ---- ICICI savings --------------------------------------------------------------------------------

    private val icici = listOf(
        "ICICI Bank Limited",
        "Statement of Transactions in Savings Account Number: 123401234567 for the period",
        "DATE MODE** PARTICULARS DEPOSITS WITHDRAWALS BALANCE",
        "01-04-2024 B/F 25,000.00",
        "02-04-2024 UPI/529512345678/ZEPTO/zepto@ybl/Payment from PhonePe 342.50 24,657.50",
        "05-04-2024 UPI/611122223333/Anita Sharma/anita@okaxis/UPI 2,000.00 26,657.50",
    )

    @Test fun icici_slash_upi_with_rrn_first() {
        val p = StatementImporter.parseLines(icici)
        assertEquals(2, p.entries.size) // B/F row is skipped
        val z = p.entries[0]
        assertEquals("Zepto", z.txn.counterparty)
        assertEquals("529512345678", z.txn.upiRef)
        assertEquals(TxnType.DEBIT, z.txn.type)
        assertEquals(342.5, z.txn.amount, 0.001)
        assertEquals("ICICI Bank", z.txn.bank)
    }

    @Test fun icici_credit_from_balance_delta_and_opening_balance() {
        val a = StatementImporter.parseLines(icici).entries[1]
        assertEquals(TxnType.CREDIT, a.txn.type)
        assertEquals("Anita Sharma", a.txn.counterparty)
    }

    // ---- SBI ------------------------------------------------------------------------------------------

    private val sbi = listOf(
        "State Bank of India",
        "Account Number : 00000012345678901",
        "Txn Date Value Date Description Ref No./Cheque No. Debit Credit Balance",
        "1 Apr 2024 1 Apr 2024 UPI/DR/529512345678/ZEPTO/YESB/zepto@ybl/Pay TRANSFER TO 4897693162093 250.00 - 10,750.00",
        "2 Apr 2024 2 Apr 2024 UPI/CR/611122223333/Rohit Verma/HDFC/rohit@oksbi/UPI TRANSFER FROM 4897693162094 - 3,000.00 13,750.00",
        "3 Apr 2024 3 Apr 2024 ATM WDL ATM CASH 01234567 2,000.00 - 11,750.00",
    )

    @Test fun sbi_positional_debit_credit_placeholders() {
        val p = StatementImporter.parseLines(sbi)
        assertEquals(3, p.entries.size)
        assertEquals(TxnType.DEBIT, p.entries[0].txn.type)
        assertEquals(250.0, p.entries[0].txn.amount, 0.001)
        assertEquals("Zepto", p.entries[0].txn.counterparty)
        assertEquals("529512345678", p.entries[0].txn.upiRef)
        assertEquals(TxnType.CREDIT, p.entries[1].txn.type)
        assertEquals(3000.0, p.entries[1].txn.amount, 0.001)
        assertEquals("Rohit Verma", p.entries[1].txn.counterparty)
        assertEquals("SBI", p.entries[0].txn.bank)
        assertEquals(PaymentMode.CASH, p.entries[2].txn.paymentMode)
    }

    @Test fun sbi_textual_dates_parse() {
        val p = StatementImporter.parseLines(sbi)
        assertEquals(LocalDate(2024, 4, 1), day(p.entries[0].txn.timestamp))
        assertEquals(LocalDate(2024, 4, 3), day(p.entries[2].txn.timestamp))
    }

    // ---- Axis -----------------------------------------------------------------------------------------

    private val axis = listOf(
        "AXIS BANK",
        "Statement of Axis Account No :- 917010012345678",
        "SRL NO TRAN DATE CHQNO PARTICULARS DR CR BAL",
        "01-04-2024 UPI/P2M/529512345678/ZEPTO/Payment 250.00 12,345.00",
        "02-04-2024 UPI/P2A/611122223333/Ramesh Kumar/HDFC Bank 1,000.00 11,345.00",
        "03-04-2024 IMPS/P2A/412345678901/Priya Nair/HDFC 5,000.00 16,345.00",
    )

    @Test fun axis_p2m_and_p2a_markers_skipped() {
        val p = StatementImporter.parseLines(axis)
        assertEquals("Zepto", p.entries[0].txn.counterparty)
        assertEquals("Ramesh Kumar", p.entries[1].txn.counterparty)
        assertEquals("611122223333", p.entries[1].txn.upiRef)
        assertEquals("Axis Bank", p.entries[0].txn.bank)
    }

    @Test fun axis_imps_has_rrn_and_netbanking_mode_and_credit_by_balance() {
        val e = StatementImporter.parseLines(axis).entries[2]
        assertEquals(PaymentMode.NETBANKING, e.txn.paymentMode)
        assertEquals("412345678901", e.txn.upiRef)
        assertEquals(TxnType.CREDIT, e.txn.type)
        assertEquals("Priya Nair", e.txn.counterparty)
    }

    // ---- Kotak: single amount with (Dr)/(Cr) markers ---------------------------------------------------

    private val kotak = listOf(
        "Kotak Mahindra Bank",
        "Date Narration Chq/Ref No. Withdrawal(Dr)/Deposit(Cr) Balance",
        "01 Apr 2024 UPI/ZEPTO/529512345678/UPI-529512345678 250.00(Dr) 12,345.00(Cr)",
        "04 Apr 2024 UPI/Meena Iyer/611122223333/Sent via UPI 900.00(Cr) 13,245.00(Cr)",
        "06 Apr 2024 NEFT-HDFCN52024040612345-Sunrise Traders-REF 4,500.00(Dr) 8,745.00(Cr)",
    )

    @Test fun kotak_markers_decide_direction() {
        val p = StatementImporter.parseLines(kotak)
        assertEquals(3, p.entries.size)
        assertEquals(TxnType.DEBIT, p.entries[0].txn.type)
        assertEquals(TxnType.CREDIT, p.entries[1].txn.type)
        assertEquals(900.0, p.entries[1].txn.amount, 0.001)
        assertEquals("Kotak Bank", p.entries[0].txn.bank)
        assertEquals("Zepto", p.entries[0].txn.counterparty)
        assertEquals("Meena Iyer", p.entries[1].txn.counterparty)
    }

    @Test fun kotak_neft_counterparty_skips_utr() {
        val e = StatementImporter.parseLines(kotak).entries[2]
        assertEquals("Sunrise Traders", e.txn.counterparty)
        assertEquals(PaymentMode.NETBANKING, e.txn.paymentMode)
        assertNull(e.txn.upiRef)
    }

    // ---- IDFC First / Yes Bank ---------------------------------------------------------------------------

    @Test fun idfc_dash_month_dates_and_two_date_columns() {
        val p = StatementImporter.parseLines(
            listOf(
                "IDFC FIRST Bank",
                "Transaction Date Value Date Particulars Cheque No. Debit Credit Balance",
                "01-Apr-2024 01-Apr-2024 UPI/DR/529512345678/BLINKIT/YESB/blinkit@ybl/Gr 480.00 - 9,520.00",
                "02-Apr-2024 02-Apr-2024 UPI/CR/611122223333/Karan Mehta/ICIC/karan@icici/UPI - 1,250.00 10,770.00",
            ),
        )
        assertEquals(2, p.entries.size)
        assertEquals("Blinkit", p.entries[0].txn.counterparty)
        assertEquals(TxnType.CREDIT, p.entries[1].txn.type)
        assertEquals("IDFC First", p.entries[0].txn.bank)
        assertEquals(LocalDate(2024, 4, 1), day(p.entries[0].txn.timestamp))
    }

    @Test fun yes_bank_dd_mm_yyyy_with_three_amount_columns() {
        val p = StatementImporter.parseLines(
            listOf(
                "YES BANK",
                "Transaction Date Value Date Description Cheque No Withdrawals Deposits Running Balance",
                "01-04-2024 01-04-2024 UPI/529512345678/Uber India/uber@axl/Trip 312.00 0.00 20,688.00",
                "07-04-2024 07-04-2024 ACH D- ZERODHA BROKING LTD-1234567 0.00 5,000.00 25,688.00",
            ),
        )
        assertEquals(2, p.entries.size)
        assertEquals(TxnType.DEBIT, p.entries[0].txn.type)
        assertEquals(312.0, p.entries[0].txn.amount, 0.001)
        assertEquals("Yes Bank", p.entries[0].txn.bank)
        assertEquals(TxnType.CREDIT, p.entries[1].txn.type)
        assertEquals(PaymentMode.OTHER, p.entries[1].txn.paymentMode)
    }

    // ---- Credit cards -----------------------------------------------------------------------------------

    @Test fun credit_card_lines_with_cr_suffix() {
        val p = StatementImporter.parseLines(
            listOf(
                "HDFC Bank Credit Card Statement",
                "Card No: 4012 88XX XXXX 4321",
                "Date Transaction Description Amount (Rs.)",
                "03/04/2024 SWIGGY BANGALORE IN 649.00",
                "05/04/2024 AMAZON PAY INDIA MUMBAI 1,299.00",
                "09/04/2024 PAYMENT RECEIVED - THANK YOU 12,000.00 Cr",
                "11/04/2024 REFUND FLIPKART 799.00 Cr",
            ),
        )
        assertEquals(4, p.entries.size)
        assertEquals(TxnType.DEBIT, p.entries[0].txn.type)
        assertEquals(PaymentMode.CARD, p.entries[0].txn.paymentMode)
        assertEquals("XX4321", p.entries[0].txn.account)
        assertEquals("Swiggy Bangalore", p.entries[0].txn.counterparty)
        assertEquals(TxnType.CREDIT, p.entries[2].txn.type)
        assertTrue(p.entries[2].suggestSkip)
        assertEquals(TxnType.CREDIT, p.entries[3].txn.type)
        assertFalse(p.entries[3].suggestSkip)
    }

    @Test fun credit_card_with_time_after_date_and_c_prefix() {
        val p = StatementImporter.parseLines(
            listOf(
                "ICICI Bank Credit Card Statement",
                "04/04/2024| 14:32 ZOMATO GURGAON 410.00",
                "06/04/2024| 09:05 REVERSAL OF CHARGES C 59.00",
            ),
        )
        assertEquals(2, p.entries.size)
        assertEquals(410.0, p.entries[0].txn.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.entries[0].txn.type)
        assertEquals(TxnType.CREDIT, p.entries[1].txn.type)
    }

    @Test fun credit_card_ref_number_after_date_is_ignored_in_description() {
        val p = StatementImporter.parseLines(
            listOf(
                "ICICI Bank Credit Card Statement",
                "08/04/2024 12345678901 NETFLIX COM MUMBAI IN 649.00",
            ),
        )
        assertEquals(1, p.entries.size)
        assertEquals("Netflix Com Mumbai", p.entries[0].txn.counterparty)
        assertNull(p.entries[0].txn.upiRef)
    }

    // ---- Skipping / robustness --------------------------------------------------------------------------

    @Test fun opening_and_closing_balance_and_summary_lines_are_skipped() {
        val p = StatementImporter.parseLines(
            listOf(
                "HDFC BANK LIMITED",
                "Opening Balance Debits Credits Closing Bal",
                "50,000.00 12 5 49,000.00",
                "01/04/24 OPENING BALANCE 50,000.00",
                "02/04/24 UPI-CHAI POINT-chaipoint@ybl-YESB0000001-529500000001-UPI 0000529500000001 02/04/24 40.00 49,960.00",
                "30/04/24 CLOSING BALANCE 49,960.00",
                "Total 40.00 0.00",
            ),
        )
        assertEquals(1, p.entries.size)
        assertEquals(40.0, p.entries[0].txn.amount, 0.001)
        assertEquals("Chai Point", p.entries[0].txn.counterparty)
    }

    @Test fun empty_input_reports_no_text() {
        val p = StatementImporter.parseLines(emptyList())
        assertFalse(p.hadText)
        assertTrue(p.entries.isEmpty())
    }

    @Test fun junk_text_yields_no_entries_but_hadText() {
        val p = StatementImporter.parseLines(listOf("Welcome to our bank", "Your relationship manager is Anil"))
        assertTrue(p.hadText)
        assertTrue(p.entries.isEmpty())
    }

    @Test fun first_row_without_prior_balance_uses_narration_keywords() {
        val p = StatementImporter.parseLines(
            listOf(
                "Axis Bank",
                "01-04-2024 SALARY CREDIT ACME LTD 80,000.00 90,000.00",
                "02-04-2024 UPI/P2M/529512345678/BIGBASKET/Pay 1,000.00 89,000.00",
            ),
        )
        assertEquals(TxnType.CREDIT, p.entries[0].txn.type)
        assertEquals(TxnType.DEBIT, p.entries[1].txn.type)
    }

    @Test fun repeated_identical_rows_are_all_kept() {
        val p = StatementImporter.parseLines(
            listOf(
                "SBI",
                "1 Apr 2024 1 Apr 2024 UPI/DR/529500000001/CHAI/YESB/c@ybl/Pay 20.00 - 980.00",
                "1 Apr 2024 1 Apr 2024 UPI/DR/529500000002/CHAI/YESB/c@ybl/Pay 20.00 - 960.00",
            ),
        )
        assertEquals(2, p.entries.size)
        assertEquals(setOf("529500000001", "529500000002"), p.entries.map { it.txn.upiRef }.toSet())
    }

    @Test fun rupee_prefixed_amounts_parse() {
        val p = StatementImporter.parseLines(listOf("Federal Bank Credit Card", "12/04/2024 UBER INDIA ₹ 215.00", "13/04/2024 CASHBACK Rs. 10.00 Cr"))
        assertEquals(215.0, p.entries[0].txn.amount, 0.001)
        assertEquals(TxnType.CREDIT, p.entries[1].txn.type)
    }

    // ---- Date + narration helpers -----------------------------------------------------------------------

    @Test fun date_formats() {
        assertEquals(LocalDate(2024, 4, 1), StatementDates.parse("01/04/24"))
        assertEquals(LocalDate(2024, 4, 1), StatementDates.parse("01-04-2024"))
        assertEquals(LocalDate(2024, 4, 1), StatementDates.parse("01 Apr 2024"))
        assertEquals(LocalDate(2024, 4, 1), StatementDates.parse("01-Apr-24"))
        assertEquals(LocalDate(2024, 4, 1), StatementDates.parse("2024-04-01"))
        assertEquals(LocalDate(2024, 9, 5), StatementDates.parse("5 Sept 2024"))
        assertNull(StatementDates.parse("31/02/2024"))
        assertNull(StatementDates.parse("Narration"))
    }

    @Test fun narration_vpa_only_counterparty() {
        assertEquals("9876543210@ybl", Narration.counterparty("UPI-9876543210@ybl-SBIN0001234-411122223333-UPI"))
    }

    @Test fun narration_hdfc_ref_column_normalisation() {
        assertEquals("529512345678", Narration.normalizeRef("0000529512345678"))
        assertEquals("529512345678", Narration.normalizeRef("529512345678"))
        assertNull(Narration.normalizeRef("N123456789"))
        assertNull(Narration.normalizeRef("000123"))
    }

    @Test fun pos_card_narration_extracts_merchant() {
        assertEquals(PaymentMode.CARD, Narration.mode("POS 411234XXXXXX1234 STARBUCKS MUMBAI", false))
        assertEquals("Starbucks Mumbai", Narration.counterparty("POS 411234XXXXXX1234 STARBUCKS MUMBAI"))
    }

    @Test fun interest_credit_counterparty() {
        assertEquals("Interest", Narration.counterparty("INT.PD:01-01-2024 TO 31-03-2024"))
    }

    @Test fun untimed_rows_of_one_day_are_spread_so_repeats_survive_repo_dedupe() {
        val p = StatementImporter.parseLines(
            listOf(
                "SBI",
                "1 Apr 2024 1 Apr 2024 UPI/DR/529500000001/CHAI/YESB/c@ybl/Pay 20.00 - 980.00",
                "1 Apr 2024 1 Apr 2024 UPI/DR/529500000002/CHAI/YESB/c@ybl/Pay 20.00 - 960.00",
                "2 Apr 2024 2 Apr 2024 UPI/DR/529500000003/CHAI/YESB/c@ybl/Pay 20.00 - 940.00",
            ),
        )
        val spread = StatementImporter.spreadUntimed(p.entries)
        val a = spread[0].txn.timestamp
        val b = spread[1].txn.timestamp
        assertEquals(11 * 60_000L, b - a)
        assertEquals(LocalDate(2024, 4, 1), day(b))
        assertEquals(LocalDate(2024, 4, 2), day(spread[2].txn.timestamp))
    }

    @Test fun timed_rows_keep_their_printed_time() {
        val p = StatementImporter.parseLines(listOf("ICICI Bank Credit Card Statement", "04/04/2024| 14:32 ZOMATO GURGAON 410.00"))
        val spread = StatementImporter.spreadUntimed(p.entries)
        assertEquals(p.entries[0].txn.timestamp, spread[0].txn.timestamp)
    }
}
