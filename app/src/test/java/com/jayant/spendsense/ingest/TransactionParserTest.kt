package com.jayant.spendsense.ingest

import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.TxnType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionParserTest {

    private val ts = 1_761_150_000_000L

    private fun sms(sender: String, body: String) = TransactionParser.parseSms(sender, body, ts)

    private fun assertTxn(
        sender: String, body: String, amount: Double, type: TxnType,
        counterparty: String? = null, mode: PaymentMode? = null, bank: String? = null, ref: String? = null, account: String? = null,
    ) {
        val p = sms(sender, body)
        assertNotNull("should parse: $body", p)
        p!!
        assertEquals("amount for: $body", amount, p.amount, 0.001)
        assertEquals("type for: $body", type, p.type)
        counterparty?.let { assertEquals("counterparty for: $body", it.lowercase(), p.counterparty?.lowercase()) }
        mode?.let { assertEquals("mode for: $body", it, p.paymentMode) }
        bank?.let { assertEquals("bank for: $body", it, p.bank) }
        ref?.let { assertEquals("ref for: $body", it, p.upiRef) }
        account?.let { assertEquals("account for: $body", it, p.account) }
    }

    // ---------------- UPI ----------------

    @Test fun hdfcUpiDebit() = assertTxn(
        "VM-HDFCBK", "Sent Rs.254.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 22/10/26\nRef 529512345678\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808",
        254.0, TxnType.DEBIT, "SWIGGY", PaymentMode.UPI, "HDFC Bank", "529512345678", "XX1234",
    )

    @Test fun hdfcUpiVpaDebit() = assertTxn(
        "AD-HDFCBK-S", "Rs.500.00 debited from a/c **5678 on 21-10-26 to VPA ramesh.k@okaxis(UPI Ref No 529412345671). Not you? Call on 18002586161 to report",
        500.0, TxnType.DEBIT, "ramesh.k@okaxis", PaymentMode.UPI, "HDFC Bank", "529412345671", "XX5678",
    )

    @Test fun sbiUpiDebitBareAmount() = assertTxn(
        "JD-SBIUPI-S", "Dear UPI user A/C X4321 debited by 120.0 on date 22Oct26 trf to ZEPTO MARKETPLACE Refno 529598765432. If not u? call 1800111109. -SBI",
        120.0, TxnType.DEBIT, "ZEPTO MARKETPLACE", PaymentMode.UPI, "SBI", "529598765432", "XX4321",
    )

    @Test fun sbiUpiCredit() = assertTxn(
        "VM-SBIUPI", "Dear SBI User, your A/c X4321-credited by Rs.1500 on 20Oct26 transfer from AMIT SHARMA Ref No 529311112222 -SBI",
        1500.0, TxnType.CREDIT, "AMIT SHARMA", null, "SBI", "529311112222",
    )

    @Test fun iciciUpiDebit() = assertTxn(
        "VK-ICICIT", "ICICI Bank Acct XX789 debited for Rs 349.00 on 22-Oct-26; BLINKIT credited. UPI:529612340000. Call 18002662 for dispute. SMS BLOCK 789 to 9215676766.",
        349.0, TxnType.DEBIT, null, PaymentMode.UPI, "ICICI Bank", null, "XX789",
    )

    @Test fun axisUpiDebit() = assertTxn(
        "AX-AXISBK", "INR 899.00 debited\nA/c no. XX2468\n22-10-26, 19:41:05\nUPI/P2M/529712345678/Uber India\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank",
        899.0, TxnType.DEBIT, null, PaymentMode.UPI, "Axis Bank", null, "XX2468",
    )

    @Test fun kotakUpiSent() = assertTxn(
        "BZ-KOTAKB", "Sent Rs.75.00 from Kotak Bank AC X1357 to chaiwala@ybl on 22-10-26.UPI Ref 529812349876. Not you, kotak.com/fraud",
        75.0, TxnType.DEBIT, "chaiwala@ybl", PaymentMode.UPI, "Kotak Bank", "529812349876", "XX1357",
    )

    @Test fun kotakUpiReceived() = assertTxn(
        "BZ-KOTAKB", "Received Rs.2000.00 in your Kotak Bank AC X1357 from priya.s@oksbi on 21-10-26.UPI Ref:529711110000.",
        2000.0, TxnType.CREDIT, "priya.s@oksbi", PaymentMode.UPI, "Kotak Bank", "529711110000",
    )

    @Test fun pnbDebit() = assertTxn(
        "VM-PNBSMS", "Ac XX5566 Debited with Rs 1,250.00 on 22-10-2026 19:02:11 UPI/529634567890/Ref. Aval Bal Rs 45,320.10 CR. Helpline 18001800-PNB",
        1250.0, TxnType.DEBIT, null, PaymentMode.UPI, "PNB",
    )

    @Test fun commaAndRupeeSymbol() = assertTxn(
        "VM-IDFCFB", "Your IDFC FIRST Bank A/C XX9988 is debited with ₹12,499.50 for UPI txn to croma@hdfcbank. RRN 529677778888. Avl bal ₹1,02,345.00",
        12499.5, TxnType.DEBIT, "croma@hdfcbank", PaymentMode.UPI, "IDFC First", "529677778888",
    )

    @Test fun balanceAmountIsNotPicked() {
        val p = sms("VM-HDFCBK", "Avl bal Rs.10,000.00 in A/c XX1234. Rs.300.00 debited for UPI to dosa@ybl Ref 529600001111")
        assertNotNull(p); assertEquals(300.0, p!!.amount, 0.001)
    }

    // ---------------- Cards / POS / offline ----------------

    @Test fun hdfcCreditCardSpend() = assertTxn(
        "VM-HDFCBK", "Spent Rs.1,250.00 On HDFC Bank Card 4321 At DMART On 2026-10-22:18:30:10.Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 4321 to 7308080808",
        1250.0, TxnType.DEBIT, "DMART", PaymentMode.CARD, "HDFC Bank", null, "XX4321",
    )

    @Test fun iciciCardSpend() = assertTxn(
        "VK-ICICIT", "INR 499.00 spent using ICICI Bank Card XX1234 on 22-Oct-26 on AMAZON PAY IN. Avl Limit: INR 1,45,000.00. If not you, call 1800 2662/SMS BLOCK 1234 to 9215676766",
        499.0, TxnType.DEBIT, "AMAZON PAY IN", PaymentMode.CARD, "ICICI Bank", null, "XX1234",
    )

    @Test fun sbiCardSpend() = assertTxn(
        "AD-SBICRD", "Rs.2,340.00 spent on your SBI Credit Card ending 5678 at RELIANCE SMART on 22/10/26. Trxn. not done by you? Report at https://sbicard.com/Dispute",
        2340.0, TxnType.DEBIT, "RELIANCE SMART", PaymentMode.CARD, "SBI", null, "XX5678",
    )

    @Test fun axisCardSpend() = assertTxn(
        "AX-AXISBK", "Spent INR 450 Axis Bank Card no. XX9876 22-10-26 13:12:45 at STARBUCKS COFFEE Avl Lmt INR 98000 SMS BLOCK 9876 to 919951860002",
        450.0, TxnType.DEBIT, "STARBUCKS COFFEE", PaymentMode.CARD, "Axis Bank", null, "XX9876",
    )

    @Test fun debitCardPos() = assertTxn(
        "VM-KOTAKB", "Your A/c XX1357 debited for Rs.300 at POS BIKANERVALA on 22-10-26 via Debit Card XX4444. Avl Bal Rs.8,200",
        300.0, TxnType.DEBIT, "POS BIKANERVALA", PaymentMode.CARD, "Kotak Bank",
    )

    @Test fun kotakCreditCard() = assertTxn(
        "BZ-KOTAKB", "Transaction of Rs.1,999.00 on Kotak Credit Card XX2222 at MYNTRA on 22/10/2026 was successful. Avl limit Rs 50,000",
        1999.0, TxnType.DEBIT, "MYNTRA", PaymentMode.CARD, "Kotak Bank",
    )

    @Test fun amexSpend() = assertTxn(
        "VM-AMEXIN", "Alert: You've spent INR 3,200.00 on your AMEX card ** 31007 at TAJ HOTELS on 22 October 2026 at 08:10 PM IST.",
        3200.0, TxnType.DEBIT, "TAJ HOTELS", PaymentMode.CARD, "American Express",
    )

    @Test fun oneCardSpend() = assertTxn(
        "VM-ONECRD", "Rs. 650.00 spent on your OneCard credit card ending 8899 at ZOMATO on 22 Oct. Avl limit Rs. 1,20,000",
        650.0, TxnType.DEBIT, "ZOMATO", PaymentMode.CARD, "OneCard",
    )

    @Test fun atmWithdrawal() = assertTxn(
        "VM-HDFCBK", "Rs.5000.00 withdrawn at ATM HDFC0001234 from A/c XX1234 on 22-10-26. Avl bal: Rs.20,000.00",
        5000.0, TxnType.DEBIT, "ATM", PaymentMode.CASH, "HDFC Bank",
    )

    @Test fun ccBillPaymentIsCredit() {
        val p = sms("VM-HDFCBK", "Payment of Rs 15,430.00 has been received on your HDFC Bank Credit Card XX4321 on 21-10-2026. Thank you")
        assertNotNull(p); p!!
        assertEquals(TxnType.CREDIT, p.type)
        assertEquals(15430.0, p.amount, 0.001)
        assertEquals("Transfers", CategoryGuesser.guess(p.counterparty, "payment received towards your credit card"))
    }

    @Test fun refundIsCredit() {
        val p = sms("VM-ICICIT", "Refund of Rs 499.00 from AMAZON has been credited to your ICICI Bank Card XX1234 on 22-Oct-26.")
        assertNotNull(p); assertEquals(TxnType.CREDIT, p!!.type); assertEquals(499.0, p.amount, 0.001)
    }

    // ---------------- NEFT/IMPS/salary ----------------

    @Test fun neftSalaryCredit() = assertTxn(
        "VM-HDFCBK", "Update! INR 85,000.00 deposited in HDFC Bank A/c XX1234 on 01-OCT-26 for NEFT Cr-CITI0000001-ACME TECH PVT LTD-SALARY. Avl bal INR 1,10,000.00",
        85000.0, TxnType.CREDIT, null, PaymentMode.NETBANKING, "HDFC Bank",
    )

    @Test fun impsDebit() = assertTxn(
        "VM-SBIINB", "Your a/c no. XXXXXXXX4321 is debited for Rs.10,000.00 on 22-10-2026 and credited to a/c no. XXXXXXXX9999 (IMPS Ref no 529611119999)",
        10000.0, TxnType.DEBIT, null, PaymentMode.NETBANKING, "SBI", "529611119999",
    )

    // ---------------- rejections ----------------

    @Test fun rejectsOtp() = assertNull(sms("VM-HDFCBK", "123456 is OTP for txn of Rs 2,500.00 at AMAZON on HDFC Bank card 4321. Do not share OTP with anyone."))
    @Test fun rejectsFailed() = assertNull(sms("VM-HDFCBK", "Your UPI transaction of Rs.300.00 to dosa@ybl has failed. Amount if debited will be refunded"))
    @Test fun rejectsDeclined() = assertNull(sms("VK-ICICIT", "Transaction of INR 2,000 on ICICI Bank Card XX1234 at FLIPKART declined due to insufficient limit."))
    @Test fun rejectsCollectRequest() = assertNull(sms("VM-SBIUPI", "Rahul has requested money Rs.500 from you on UPI. Pay via BHIM or ignore. -SBI"))
    @Test fun rejectsBillDue() = assertNull(sms("VM-HDFCBK", "Your HDFC Bank Credit Card XX4321 statement: Total amount due Rs.15,430.00, Minimum due Rs.780. Due date 05-11-26."))
    @Test fun rejectsAutopayReminder() = assertNull(sms("VM-HDFCBK", "Rs.649.00 will be debited from your A/c XX1234 on 25-10-26 towards NETFLIX mandate."))
    @Test fun rejectsLoanPromo() = assertNull(sms("VM-HDFCBK", "Pre-approved personal loan of Rs.5,00,000 for you! Apply now at hdfc.bank.in. T&C"))
    @Test fun rejectsBalanceOnly() = assertNull(sms("VM-SBIINB", "Your A/c XX4321 has a balance of Rs.12,345.00 as on 22-10-26."))
    @Test fun rejectsPersonalChat() = assertNull(sms("+919876543210", "bro send me 500 rs for dinner"))
    @Test fun rejectsRechargePromo() = assertNull(sms("JX-JIOINF", "Recharge now with Rs.299 plan and get 2GB/day. Pay now on MyJio."))

    // ---------------- notifications ----------------

    private fun notif(pkg: String, title: String?, text: String) = TransactionParser.parseNotification(pkg, title, text, ts)

    @Test fun gpayPaid() {
        val p = notif("com.google.android.apps.nbu.paisa.user", "Payment successful", "You paid ₹254.00 to Swiggy")
        assertNotNull(p); p!!
        assertEquals(254.0, p.amount, 0.001); assertEquals(TxnType.DEBIT, p.type); assertEquals("Swiggy", p.counterparty)
        assertEquals(PaymentMode.UPI, p.paymentMode)
    }

    @Test fun gpayReceived() {
        val p = notif("com.google.android.apps.nbu.paisa.user", "Amit Sharma", "Received ₹1,500 from Amit Sharma")
        assertNotNull(p); assertEquals(TxnType.CREDIT, p!!.type); assertEquals("Amit Sharma", p.counterparty)
    }

    @Test fun phonepePaid() {
        val p = notif("com.phonepe.app", "Paid ₹120 to Ramesh Kirana", "Transaction successful. UPI Ref 529600002222")
        assertNotNull(p); assertEquals(120.0, p!!.amount, 0.001); assertEquals("Ramesh Kirana", p.counterparty)
    }

    @Test fun paytmPaid() {
        val p = notif("net.one97.paytm", "Paid Rs.60 to Chai Point", "Paid successfully from Paytm UPI")
        assertNotNull(p); assertEquals(60.0, p!!.amount, 0.001); assertEquals(TxnType.DEBIT, p.type)
    }

    @Test fun googleWalletTapToPay() {
        val p = notif("com.google.android.apps.walletnfcrel", "Starbucks", "₹350.00 paid with HDFC Bank card ••4321")
        assertNotNull(p); p!!
        assertEquals(350.0, p.amount, 0.001); assertEquals(PaymentMode.CARD, p.paymentMode); assertEquals(TxnType.DEBIT, p.type)
    }

    @Test fun samsungWallet() {
        val p = notif("com.samsung.android.spay", "Payment complete", "Paid ₹899 at DECATHLON with Axis Bank card")
        assertNotNull(p); assertEquals(PaymentMode.CARD, p!!.paymentMode); assertEquals("Decathlon", p.counterparty)
    }

    @Test fun ignoresNonPaymentApp() = assertNull(notif("com.whatsapp", "Rahul", "paid ₹500 to me yesterday lol"))
    @Test fun ignoresGpayRequest() = assertNull(notif("com.google.android.apps.nbu.paisa.user", "Rahul", "Rahul is requesting ₹500"))
    @Test fun ignoresReward() = assertNull(notif("com.phonepe.app", "You won ₹25 cashback!", "Scratch card earned on your payment"))
    @Test fun ignoresPending() = assertNull(notif("com.phonepe.app", "Payment pending", "₹500 to Ramesh is processing"))

    // ---------------- categories ----------------

    @Test fun categories() {
        assertEquals("Food & Dining", CategoryGuesser.guess("SWIGGY", ""))
        assertEquals("Groceries", CategoryGuesser.guess("ZEPTO MARKETPLACE", ""))
        assertEquals("Groceries", CategoryGuesser.guess("DMART", ""))
        assertEquals("Shopping", CategoryGuesser.guess("AMAZON PAY IN", ""))
        assertEquals("Transport", CategoryGuesser.guess(null, "UPI/P2M/529712345678/Uber India"))
        assertEquals("Investment", CategoryGuesser.guess("ZERODHA BROKING", ""))
        assertEquals("Cash", CategoryGuesser.guess("ATM", "withdrawn at ATM"))
        assertEquals("Transfers", CategoryGuesser.guess("ramesh.k@okaxis", ""))
        assertEquals("Transfers", CategoryGuesser.guess("AMIT SHARMA", ""))
        assertEquals("Entertainment", CategoryGuesser.guess("NETFLIX", ""))
        assertEquals("Other", CategoryGuesser.guess("XYZ123", ""))
        assertTrue(CategoryGuesser.CATEGORIES.contains("Other"))
    }
}
