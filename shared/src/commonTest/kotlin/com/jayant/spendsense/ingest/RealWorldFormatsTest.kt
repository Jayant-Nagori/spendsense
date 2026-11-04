package com.jayant.spendsense.ingest

import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.Test

class RealWorldFormatsTest {

    private val ts = 1_761_150_000_000L
    private val D = TxnType.DEBIT
    private val C = TxnType.CREDIT

    private fun sms(sender: String, body: String, amount: Double, type: TxnType, mode: PaymentMode? = null, cp: String? = null, bank: String? = null) {
        val p = TransactionParser.parseSms(sender, body, ts)
        assertNotNull(p, "should parse: $body")
        p!!
        assertEquals(amount, p.amount, 0.001, "amount for: $body")
        assertEquals(type, p.type, "type for: $body")
        mode?.let { assertEquals(it, p.paymentMode, "mode for: $body") }
        cp?.let { assertEquals(it.lowercase(), p.counterparty?.lowercase(), "counterparty for: $body") }
        bank?.let { assertEquals(it, p.bank, "bank for: $body") }
    }

    private fun smsNull(sender: String, body: String) =
        assertNull(TransactionParser.parseSms(sender, body, ts), "should reject: $body")

    private fun notif(pkg: String, title: String?, text: String, amount: Double, type: TxnType, mode: PaymentMode? = null, cp: String? = null) {
        val p = TransactionParser.parseNotification(pkg, title, text, ts)
        assertNotNull(p, "should parse: $title / $text")
        p!!
        assertEquals(amount, p.amount, 0.001, "amount for: $title / $text")
        assertEquals(type, p.type, "type for: $title / $text")
        mode?.let { assertEquals(it, p.paymentMode, "mode for: $title / $text") }
        cp?.let { assertEquals(it.lowercase(), p.counterparty?.lowercase(), "counterparty for: $title / $text") }
    }

    private fun notifNull(pkg: String, title: String?, text: String) =
        assertNull(TransactionParser.parseNotification(pkg, title, text, ts), "should reject: $title / $text")

    private val GPAY = "com.google.android.apps.nbu.paisa.user"
    private val PHONEPE = "com.phonepe.app"
    private val PAYTM = "net.one97.paytm"
    private val BHIM = "in.org.npci.upiapp"
    private val AMAZON = "in.amazon.mShop.android.shopping"
    private val CRED = "com.dreamplug.androidapp"
    private val SAMSUNG = "com.samsung.android.spay"
    private val GWALLET = "com.google.android.apps.walletnfcrel"

    // ---------------- Amount formats ----------------

    @Test fun amountRs1() = sms("VM-HDFCBK", "Sent Rs.1.00\nFrom HDFC Bank A/C *1234\nTo TEST SHOP\nOn 22/10/26\nRef 529512345001", 1.0, D, PaymentMode.UPI, "TEST SHOP")
    @Test fun amountRsNoDot() = sms("VM-HDFCBK", "Rs 1 debited from a/c XX1234 on 22-10-26 to VPA test@okhdfcbank (UPI Ref No 529512345002)", 1.0, D, PaymentMode.UPI, "test@okhdfcbank")
    @Test fun amountInr1() = sms("AX-AXISBK", "INR 1.00 debited\nA/c no. XX2468\n22-10-26, 10:01:05\nUPI/P2M/529712345003/Test Merchant\nNot you? SMS BLOCKUPI Cust ID to 919951860002\nAxis Bank", 1.0, D, PaymentMode.UPI)
    @Test fun amountRupeeSymbol1() = sms("VM-IDFCFB", "Your A/C XX9988 is debited with ₹1 for UPI txn to test@ybl. RRN 529677778899", 1.0, D, PaymentMode.UPI, "test@ybl")
    @Test fun amountLakhFormat() = sms("VM-HDFCBK", "Rs.1,23,456.78 debited from a/c **5678 on 21-10-26 to VPA builder@okicici (UPI Ref No 529412345004)", 123456.78, D, PaymentMode.UPI, "builder@okicici")
    @Test fun amountTiny() = sms("VM-SBIUPI", "Dear UPI user A/C X4321 debited by 0.50 on date 22Oct26 trf to TEST Refno 529598765433. If not u? call 1800111109. -SBI", 0.5, D, PaymentMode.UPI)
    @Test fun amountSpaceAfterRs() = sms("VM-HDFCBK", "Rs. 10 debited from a/c XX1234 on 22-10-26 to VPA x@ybl (UPI Ref No 529412345005)", 10.0, D, PaymentMode.UPI)

    // ---------------- HDFC ----------------

    @Test fun hdfcUpiCredit() = sms("VM-HDFCBK", "Credit Alert!\nRs.5,000.00 credited to HDFC Bank A/c XX1234 on 22-10-26 from VPA rohit.m@okicici (UPI 529512340011)", 5000.0, C, PaymentMode.UPI, "rohit.m@okicici", "HDFC Bank")
    @Test fun hdfcCardOnline() = sms("VM-HDFCBK", "Rs.1,999.00 spent on HDFC Bank Card x4321 at AMAZON on 22-10-26. Avl bal 45,000. Not you? Call 18002586161", 1999.0, D, PaymentMode.CARD, "AMAZON", "HDFC Bank")
    @Test fun hdfcNeftCredit() = sms("VM-HDFCBK", "Update! INR 25,000.00 deposited in HDFC Bank A/c XX1234 on 22-OCT-26 for NEFT Cr-SBIN0001234-RAJESH KUMAR-REF. Avl bal INR 55,000.00", 25000.0, C, PaymentMode.NETBANKING)
    @Test fun hdfcAutopay() = sms("VM-HDFCBK", "Rs.649.00 debited from a/c **1234 on 22-10-26 for NETFLIX via e-mandate. UPI Ref 529512340012", 649.0, D, PaymentMode.UPI)
    @Test fun hdfcEmandateNach() = sms("VM-HDFCBK", "Rs 2,500.00 has been debited from your HDFC Bank A/c XX1234 on 22-10-26 for NACH debit towards LIC OF INDIA. Ref ACH529512", 2500.0, D)

    // ---------------- ICICI ----------------

    @Test fun iciciUpiCredit() = sms("VK-ICICIT", "Dear Customer, Acct XX789 is credited with Rs 1,200.00 on 22-Oct-26 from ankit.p@okaxis. UPI:529612340013-ICICI Bank", 1200.0, C, PaymentMode.UPI, "ankit.p@okaxis", "ICICI Bank")
    @Test fun iciciAtm() = sms("VK-ICICIT", "ICICI Bank Acct XX789 debited for Rs 10,000.00 on 22-Oct-26 ATM WDL. Avl Bal Rs 22,000.50", 10000.0, D, PaymentMode.CASH, "ATM", "ICICI Bank")
    @Test fun iciciImps() = sms("VK-ICICIT", "ICICI Bank Acct XX789 debited with Rs 7,500.00 on 22-Oct-26 & Acct XX555 credited. IMPS: 529612340014.", 7500.0, D, PaymentMode.NETBANKING, null, "ICICI Bank")
    @Test fun iciciRefundCard() = sms("VK-ICICIT", "Your ICICI Bank Credit Card XX1234 has been credited with Rs 799.00 on 22-Oct-26 as refund for FLIPKART.", 799.0, C)
    @Test fun iciciCcBillPayment() = sms("VK-ICICIT", "Payment of Rs 8,450.00 has been received on your ICICI Bank Credit Card XX1234 on 22-Oct-26 through Bharat Bill Payment System. Thank you.", 8450.0, C)

    // ---------------- SBI ----------------

    @Test fun sbiAtm() = sms("VM-SBIINB", "Dear Customer, Rs 4000.00 withdrawn from A/c XX4321 at ATM on 22Oct26. Avl Bal Rs 12,000.00. -SBI", 4000.0, D, PaymentMode.CASH, "ATM", "SBI")
    @Test fun sbiNeftCredit() = sms("VM-SBIINB", "Dear Customer, your A/c XX4321 is credited by Rs.30,000.00 on 22Oct26 by NEFT UTR no HDFCN52026102212345. -SBI", 30000.0, C, PaymentMode.NETBANKING)
    @Test fun sbiCardPos() = sms("AD-SBICRD", "Rs.1.00 spent on your SBI Credit Card ending 5678 at TEST STORE on 22/10/26.", 1.0, D, PaymentMode.CARD, "TEST STORE", "SBI")
    @Test fun sbiDebitCardPos() = sms("VM-SBIINB", "Dear Customer, transaction number 1234 for Rs.560.00 by SBI Debit Card X4321 at SPENCERS on 22Oct26 debited to A/c. Avl Bal Rs 9,000.", 560.0, D, PaymentMode.CARD)

    // ---------------- Axis ----------------

    @Test fun axisUpiCredit() = sms("AX-AXISBK", "INR 3,000.00 credited\nA/c no. XX2468\n22-10-26, 12:11:00\nUPI/P2A/529712345015/Suresh Babu\nAxis Bank", 3000.0, C, PaymentMode.UPI, null, "Axis Bank")
    @Test fun axisNeftDebit() = sms("AX-AXISBK", "Dear Customer, INR 20,000.00 debited from A/c no. XX2468 on 22-10-26 for NEFT transfer to RENT ACCOUNT. Axis Bank", 20000.0, D, PaymentMode.NETBANKING)
    @Test fun axisAutopay() = sms("AX-AXISBK", "INR 199.00 debited from A/c no. XX2468 on 22-10-26 for e-mandate SPOTIFY INDIA. Axis Bank", 199.0, D)

    // ---------------- Kotak ----------------

    @Test fun kotakImps() = sms("BZ-KOTAKB", "Rs.5,000.00 debited from Kotak Bank AC X1357 on 22-10-26 via IMPS to Rahul Verma. Ref 529812340016", 5000.0, D, PaymentMode.NETBANKING)
    @Test fun kotakRefund() = sms("BZ-KOTAKB", "Refund of Rs.1,099.00 for your txn at MYNTRA has been credited to your Kotak Credit Card XX2222 on 22-10-26", 1099.0, C)

    // ---------------- PNB ----------------

    @Test fun pnbCredit() = sms("VM-PNBSMS", "A/c XX5566 Credited with Rs 8,000.00 on 22-10-2026 UPI/529634567891/Ref. Avl Bal Rs 53,320.10 CR. Helpline 18001800-PNB", 8000.0, C, PaymentMode.UPI, null, "PNB")
    @Test fun pnbAtm() = sms("VM-PNBSMS", "Rs 3,000.00 withdrawn from A/c XX5566 at ATM on 22-10-2026. Avl Bal Rs 42,320.10", 3000.0, D, PaymentMode.CASH, "ATM", "PNB")

    // ---------------- Bank of Baroda ----------------

    @Test fun bobUpiDebit() = sms("VM-BOBTXN", "Rs.350.00 Dr. from A/C XXXXXX1234 and Cr. to zomato@icici. Ref:529812345017. AvlBal:Rs9100.25(2026:10:22 20:11:15) -Bank of Baroda", 350.0, D, PaymentMode.UPI, "zomato@icici", "Bank of Baroda")
    @Test fun bobUpiCredit() = sms("VM-BOBTXN", "Rs.1000.00 Cr. to A/C XXXXXX1234 on 22-10-26 from vijay.k@okaxis. UPI Ref:529812345018 -Bank of Baroda", 1000.0, C, PaymentMode.UPI, "vijay.k@okaxis")

    // ---------------- Canara ----------------

    @Test fun canaraDebit() = sms("VM-CANBNK", "An amount of INR 450.00 has been DEBITED to your account XXX1234 on 22/10/2026 towards UPI/DR/529812345019/ZOMATO. Total Avail.bal INR 6,500.00 - Canara Bank", 450.0, D, PaymentMode.UPI, null, "Canara Bank")
    @Test fun canaraCredit() = sms("VM-CANBNK", "An amount of INR 2,000.00 has been CREDITED to your account XXX1234 on 22/10/2026 towards UPI/CR/529812345020/SUNIL. Total Avail.bal INR 8,500.00 - Canara Bank", 2000.0, C, PaymentMode.UPI)

    // ---------------- Union ----------------

    @Test fun unionDebit() = sms("VM-UNIONB", "A/c *1234 Debited for Rs:250.00 on 22-10-2026 12:10:11 by UPI Ref 529812345021 (Credited to RAMESH@ybl). Bal:Rs 3,000.00 -Union Bank of India", 250.0, D, PaymentMode.UPI, null, "Union Bank")
    @Test fun unionCredit() = sms("VM-UNIONB", "A/c *1234 Credited for Rs:5,000.00 on 22-10-2026 12:10:11 by UPI Ref 529812345022. Bal:Rs 8,000.00 -Union Bank of India", 5000.0, C, PaymentMode.UPI)

    // ---------------- IDFC First ----------------

    @Test fun idfcCredit() = sms("VM-IDFCFB", "Your A/C XX9988 has been credited with INR 15,000.00 on 22/10/2026 from UPI/529677778800/priya@okicici. Avl bal INR 17,000", 15000.0, C, PaymentMode.UPI, null, "IDFC First")
    @Test fun idfcCard() = sms("VM-IDFCFB", "INR 1,250.00 spent on your IDFC FIRST Bank Credit Card ending 7766 at BIGBASKET on 22-OCT-26. Avl limit INR 90,000", 1250.0, D, PaymentMode.CARD, "BIGBASKET")

    // ---------------- Yes ----------------

    @Test fun yesUpiDebit() = sms("VM-YESBNK", "Rs.425.00 debited from YES BANK a/c XX3344 on 22-10-26 to VPA cafe@ybl. UPI Ref No 529812345023", 425.0, D, PaymentMode.UPI, "cafe@ybl", "Yes Bank")
    @Test fun yesCardSpend() = sms("VM-YESBNK", "Rs.2,000.00 spent on YES BANK Credit Card XX5566 at BOOKMYSHOW on 22-10-2026. Avl limit Rs 75,000", 2000.0, D, PaymentMode.CARD, "BOOKMYSHOW", "Yes Bank")

    // ---------------- IndusInd ----------------

    @Test fun indusDebit() = sms("VM-INDUSB", "INR 640.00 debited from IndusInd Bank A/c XX7788 on 22-10-26 to VPA grocer@ybl. UPI Ref 529812345024", 640.0, D, PaymentMode.UPI, "grocer@ybl", "IndusInd Bank")
    @Test fun indusCredit() = sms("VM-INDUSB", "INR 3,200.00 credited to IndusInd Bank A/c XX7788 on 22-10-26 from VPA mom@oksbi. UPI Ref 529812345025", 3200.0, C, PaymentMode.UPI, "mom@oksbi")

    // ---------------- AU ----------------

    @Test fun auDebit() = sms("VM-AUBANK", "Rs.1,100.00 debited from your AU Bank a/c XX4455 on 22-10-26 to VPA petrol@ybl. UPI Ref 529812345026", 1100.0, D, PaymentMode.UPI, "petrol@ybl", "AU Bank")
    @Test fun auCredit() = sms("VM-AUSFBK", "Rs.9,000.00 credited to your AU Bank a/c XX4455 on 22-10-26 from VPA client@okicici. UPI Ref 529812345027", 9000.0, C, PaymentMode.UPI)

    // ---------------- Federal ----------------

    @Test fun federalDebit() = sms("VM-FEDBNK", "Rs 780.00 debited from your Federal Bank A/c XX6677 on 22-10-26 to VPA chai@ybl via UPI. Ref 529812345028", 780.0, D, PaymentMode.UPI, "chai@ybl", "Federal Bank")
    @Test fun federalCredit() = sms("VM-FEDBNK", "Rs 4,500.00 credited to your Federal Bank A/c XX6677 on 22-10-26 from VPA sibling@okaxis via UPI. Ref 529812345029", 4500.0, C, PaymentMode.UPI)

    // ---------------- IDBI ----------------

    @Test fun idbiDebit() = sms("VM-IDBIBK", "Your IDBI Bank A/c XX8899 is debited by Rs 600.00 on 22-10-26 trf to VPA kirana@ybl. UPI Ref 529812345030", 600.0, D, PaymentMode.UPI, "kirana@ybl", "IDBI Bank")
    @Test fun idbiCredit() = sms("VM-IDBIBK", "Your IDBI Bank A/c XX8899 is credited by Rs 1,500.00 on 22-10-26 from VPA friend@okaxis. UPI Ref 529812345031", 1500.0, C, PaymentMode.UPI)

    // ---------------- RBL ----------------

    @Test fun rblCard() = sms("VM-RBLCRD", "Rs.3,499.00 spent on your RBL Bank Credit Card XX1122 at NYKAA on 22-10-26. Avl limit Rs 55,000", 3499.0, D, PaymentMode.CARD, "NYKAA", "RBL Bank")
    @Test fun rblUpiDebit() = sms("VM-RBLBNK", "Rs.220.00 debited from RBL Bank a/c XX3322 on 22-10-26 to VPA vendor@ybl. UPI Ref 529812345032", 220.0, D, PaymentMode.UPI, "vendor@ybl", "RBL Bank")

    // ---------------- Misc SMS ----------------

    @Test fun slashDateUpiCreditInr() = sms("VM-HDFCBK", "INR 1.00 credited to a/c XX1234 on 22/10/26 by UPI from VPA a.b@okhdfcbank. Ref 529512340099", 1.0, C, PaymentMode.UPI)
    @Test fun netbankingBillPay() = sms("VM-HDFCBK", "Rs.1,500.00 debited from HDFC Bank A/c XX1234 on 22-10-26 for NetBanking bill payment to BESCOM. Ref 529512340098", 1500.0, D)

    // ---------------- SMS rejects ----------------

    @Test fun rejOtpUpi() = smsNull("VM-HDFCBK", "123456 is the OTP to authorise a UPI payment of Rs.500.00 to Swiggy. Valid 5 mins. Do not share.")
    @Test fun rejOtpLogin() = smsNull("VK-ICICIT", "OTP for transaction of INR 1,299.00 at MYNTRA on ICICI Bank Card XX1234 is 884512. Valid for 10 minutes.")
    @Test fun rejPaymentFailed() = smsNull("VM-SBIUPI", "UPI payment of Rs.500.00 to ramesh@ybl failed. If debited, amount will be refunded within 3 working days.")
    @Test fun rejDeclined() = smsNull("AX-AXISBK", "Your Axis Bank Card XX9876 transaction of INR 5,000 at FLIPKART was declined.")
    @Test fun rejCollect() = smsNull("VM-HDFCBK", "Rahul has requested Rs.1,000.00 from you via UPI. Approve using your UPI app.")
    @Test fun rejCollectRequestWord() = smsNull("VM-SBIUPI", "Collect request of Rs 2,000.00 from ABC TRADERS pending on your UPI. Pay via BHIM or ignore.")
    @Test fun rejWillBeDebited() = smsNull("VM-HDFCBK", "Rs.999.00 will be debited from your A/c XX1234 on 25-10-26 for AMAZON PRIME mandate.")
    @Test fun rejAutoDebitReminder() = smsNull("VM-ICICIT", "Reminder: Rs 1,499.00 will be auto-debited from your ICICI Bank a/c XX789 on 25-Oct-26 towards HOTSTAR.")
    @Test fun rejBillDue() = smsNull("VM-HDFCBK", "Your HDFC Bank Credit Card bill of Rs 22,310.00 is due on 05-11-26. Pay now to avoid late fee.")
    @Test fun rejBillDueDate() = smsNull("VM-ICICIT", "ICICI Bank Credit Card XX1234 statement: Total Amount Due Rs 12,000.00, Minimum Amount Due Rs 600.00, Payment Due Date 05-Nov-26.")
    @Test fun rejCashbackPromo() = smsNull("VM-HDFCBK", "Get up to Rs.500 cashback on UPI payments on Diwali! Use HDFC Bank card. Offer valid till 31 Oct. T&C apply.")
    @Test fun rejCashbackPromo2() = smsNull("VK-ICICIT", "Win Rs.1,000 cashback on your next ICICI Bank Card spend of Rs.5,000. Offer valid till 31-Oct-26.")
    @Test fun rejBalanceEnquiry() = smsNull("VM-HDFCBK", "Avl bal in HDFC Bank A/c XX1234 is Rs.45,000.00 as on 22-10-26 10:00.")
    @Test fun rejMiniStatementBal() = smsNull("AX-AXISBK", "Your Axis Bank A/c no. XX2468 balance is INR 12,345.00 as of 22-10-26.")
    @Test fun rejFutureRefund() = smsNull("VM-HDFCBK", "Your txn of Rs.500.00 on HDFC Bank Card XX4321 is pending. If debited, the amount will be refunded in 5-7 days.")
    @Test fun rejRequestMoney() = smsNull("VM-SBIUPI", "You have a pending request for money of Rs.300.00 from Priya on UPI. -SBI")
    @Test fun rejLimitIncrease() = smsNull("VM-HDFCBK", "You are eligible for credit limit increase up to Rs.1,00,000 on your HDFC Bank Credit Card. Apply now.")
    @Test fun rejNonBankNumber() = smsNull("+919812345678", "Hey I paid Rs.500 to Ramesh for the tickets")
    @Test fun rejMandateCreated() = smsNull("VM-HDFCBK", "UPI mandate has been created for Rs 649.00 towards NETFLIX on your HDFC Bank a/c XX1234. Mandate registered.")

    // ---------------- Notifications: Google Pay ----------------

    @Test fun gpayPaidTo() = notif(GPAY, "Payment successful", "₹1 paid to Ramesh", 1.0, D, PaymentMode.UPI, "Ramesh")
    @Test fun gpayYouPaid() = notif(GPAY, "You paid ₹250 to Swiggy", "Payment successful", 250.0, D, PaymentMode.UPI, "Swiggy")
    @Test fun gpayReceivedFrom() = notif(GPAY, "Received ₹500 from Amit Sharma", "Tap to view details", 500.0, C, PaymentMode.UPI, "Amit Sharma")
    @Test fun gpaySentTitleOnly() = notif(GPAY, "Sent ₹1", "To Ramesh Kumar", 1.0, D, PaymentMode.UPI, "Ramesh Kumar")
    @Test fun gpayPaidMerchantInr() = notif(GPAY, "Paid ₹1,23,456.78", "to Croma Electronics", 123456.78, D, PaymentMode.UPI, "Croma Electronics")
    @Test fun gpayRejRequest() = notifNull(GPAY, "Rahul requested ₹500", "Tap to pay")
    @Test fun gpayRejFailed() = notifNull(GPAY, "Payment failed", "₹500 to Ramesh could not be sent")
    @Test fun gpayRejCashbackOffer() = notifNull(GPAY, "Earn up to ₹100 cashback", "Pay your electricity bill with Google Pay")

    // ---------------- PhonePe ----------------

    @Test fun phonepeSent() = notif(PHONEPE, "Sent ₹1 to Ramesh", "Transaction successful", 1.0, D, PaymentMode.UPI, "Ramesh")
    @Test fun phonepePaymentSuccessful() = notif(PHONEPE, "Payment Successful", "Paid ₹350 to Domino's Pizza", 350.0, D, PaymentMode.UPI, "Domino's Pizza")
    @Test fun phonepeReceived() = notif(PHONEPE, "Money received", "You received ₹2,000 from Priya Singh", 2000.0, C, PaymentMode.UPI, "Priya Singh")
    @Test fun phonepeRejFailed() = notifNull(PHONEPE, "Payment Failed", "Your payment of ₹500 to Ramesh failed. Money debited will be refunded.")
    @Test fun phonepeRejRequest() = notifNull(PHONEPE, "Money request", "Amit has requested ₹300 from you")
    @Test fun phonepeRejOffer() = notifNull(PHONEPE, "Flat ₹50 off on recharge", "Offer valid today. Recharge now")

    // ---------------- Paytm ----------------

    @Test fun paytmPaid() = notif(PAYTM, "Paid ₹1.00 to Tea Stall", "Payment successful", 1.0, D, PaymentMode.UPI, "Tea Stall")
    @Test fun paytmReceived() = notif(PAYTM, "Received ₹750", "from Neha Gupta in your Paytm Wallet", 750.0, C, PaymentMode.UPI, "Neha Gupta")
    @Test fun paytmMoneySent() = notif(PAYTM, "Rs.500 sent to Ramesh", "Payment successful via UPI", 500.0, D, PaymentMode.UPI, "Ramesh")
    @Test fun paytmRejCashback() = notifNull(PAYTM, "Cashback offer", "Get ₹100 cashback on your next payment! Pay now")
    @Test fun paytmRejBillDue() = notifNull(PAYTM, "Electricity bill due", "Your bill of ₹1,820 is due on 28 Oct. Pay now")

    // ---------------- BHIM / Amazon Pay / CRED ----------------

    @Test fun bhimPaid() = notif(BHIM, "Payment successful", "₹120 paid to Kirana Store", 120.0, D, PaymentMode.UPI, "Kirana Store")
    @Test fun bhimReceived() = notif(BHIM, "Money received", "Received ₹300 from Rakesh", 300.0, C, PaymentMode.UPI, "Rakesh")
    @Test fun amazonPayPaid() = notif(AMAZON, "Payment successful", "You paid ₹499 to Big Bazaar using Amazon Pay UPI", 499.0, D, PaymentMode.UPI)
    @Test fun amazonRefund() = notif(AMAZON, "Refund processed", "Refund of ₹799 has been credited to your Amazon Pay balance", 799.0, C)
    @Test fun amazonRejOrder() = notifNull(AMAZON, "Your order has shipped", "Arriving tomorrow. Pay now ₹499 on delivery")
    @Test fun credPaid() = notif(CRED, "Payment successful", "₹15,430 paid to HDFC Bank credit card", 15430.0, D)
    @Test fun credRejReward() = notifNull(CRED, "You won ₹500 cashback", "Scratch card earned on your bill payment")
    @Test fun credRejDue() = notifNull(CRED, "Bill reminder", "Your ICICI card bill of ₹8,200 is due in 3 days")

    // ---------------- Wallets and bank apps ----------------

    @Test fun gwalletTap() = notif(GWALLET, "Tap to pay", "₹180.00 paid at Cafe Coffee Day with Axis Bank Visa ••9876", 180.0, D, PaymentMode.CARD)
    @Test fun gwalletTapTitleMerchant() = notif(GWALLET, "Cafe Coffee Day", "₹180.00 with Visa ••9876", 180.0, D, PaymentMode.CARD)
    @Test fun samsungPayment() = notif(SAMSUNG, "Samsung Wallet", "₹1,299 paid at Reliance Digital with HDFC Bank card", 1299.0, D, PaymentMode.CARD)
    @Test fun samsungSpent() = notif(SAMSUNG, "Payment complete", "You spent ₹75 at Chai Sutta Bar", 75.0, D, PaymentMode.CARD)
    @Test fun hdfcAppDebit() = notif("com.snapwork.hdfc", "HDFC Bank", "Rs.1,250.00 debited from a/c XX1234 on 22-10-26 to VPA shop@ybl", 1250.0, D)
    @Test fun sbiAppCredit() = notif("com.sbi.upi", "SBI UPI", "Rs 500.00 credited to your a/c XX4321 from VPA friend@oksbi", 500.0, C)
    @Test fun iciciAppCardSpend() = notif("com.csam.icici.bank.imobile", "iMobile Pay", "Rs 899.00 spent on your Credit Card XX1234 at AMAZON", 899.0, D, PaymentMode.CARD)
    @Test fun bankAppRejOtp() = notifNull("com.axis.mobile", "Axis Mobile", "123456 is the OTP for your transaction of INR 5,000. Do not share.")
}
