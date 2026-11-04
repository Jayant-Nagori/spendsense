package com.jayant.spendsense.ingest

import com.jayant.spendsense.data.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Made-up corpus (no real user data) of payment-app notifications and bank SMS: promos, cashback, reminders, failed
 * payments and OTPs must never become a debit (no pop-up); real debits must still be captured.
 */
class PromoCorpusTest {
    private enum class Want { DEBIT, CREDIT, CASHBACK, NOTHING }

    private data class Case(val src: String, val title: String?, val text: String, val want: Want, val sms: Boolean = false)

    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val bhim = "in.org.npci.upiapp"
    private val phonepe = "com.phonepe.app"
    private val paytm = "net.one97.paytm"
    private val cred = "com.dreamplug.androidapp"
    private val amazon = "in.amazon.mShop.android.shopping"

    private fun n(pkg: String, title: String?, text: String, want: Want) = Case(pkg, title, text, want)
    private fun s(sender: String, text: String, want: Want) = Case(sender, null, text, want, sms = true)

    private val corpus = listOf(
        // ---- the user's two reports -------------------------------------------------------------------------------
        n(bhim, "Congratulations!", "You have won ₹5 cashback on your last BHIM UPI transaction. Scratch now!", Want.NOTHING),
        n(bhim, "BHIM", "₹5 cashback is waiting for you. Pay with BHIM and claim your reward", Want.NOTHING),
        n(paytm, "Pay & get ₹15 back", "Get ₹15 back when you pay any merchant with UPI today. T&C apply", Want.NOTHING),
        n(phonepe, "Offer for you", "Get ₹15 back when you pay your electricity bill on PhonePe", Want.NOTHING),
        // ---- promos / offers across apps --------------------------------------------------------------------------
        n(gpay, "You won a reward!", "You've won ₹21 cashback. Tap to scratch your card", Want.NOTHING),
        n(gpay, "Recharge offer", "Flat ₹50 off on your next mobile recharge above ₹299. Valid till 31 Oct", Want.NOTHING),
        n(phonepe, "Hurry!", "Upto ₹100 cashback on your first bill payment. Pay now", Want.NOTHING),
        n(paytm, "Paytm", "Earn up to ₹500 cashback on movie tickets. Use code MOVIE500", Want.NOTHING),
        n(cred, "CRED", "Pay your credit card bill and win up to ₹1,000 in CRED coins. Assured rewards on every payment", Want.NOTHING),
        n(cred, "jackpot unlocked", "Unlock your reward: 10% off on Myntra vouchers worth ₹2,000", Want.NOTHING),
        n(amazon, "Amazon Pay", "Get 5% back on Amazon Pay ICICI card. Shop now", Want.NOTHING),
        n(amazon, "Amazon Pay", "₹75 cashback on your next bill payment of ₹500 or more. Offer valid till Sunday", Want.NOTHING),
        n(gpay, "Google Pay", "Send ₹1 to a friend and get a scratch card worth up to ₹100", Want.NOTHING),
        n(phonepe, "PhonePe", "Your coupon worth ₹40 expires today. Use it when you shop on Pincode", Want.NOTHING),
        // ---- reminders / statements / autopay ----------------------------------------------------------------------
        n(cred, "Payment due", "Your HDFC Bank credit card payment of ₹12,450 is due on 28 Oct. Pay now on CRED", Want.NOTHING),
        n(phonepe, "Bill reminder", "Your Airtel postpaid bill of ₹599 is due tomorrow", Want.NOTHING),
        s("AX-AXISBK", "Statement generated for Axis Bank Credit Card XX4321. Total amount due INR 18,240.00. Minimum due INR 912.00, due by 05-11-26.", Want.NOTHING),
        s("VM-HDFCBK", "Autopay scheduled: Rs 649.00 will be debited from HDFC Bank A/c XX1234 on 01-11 for NETFLIX.", Want.NOTHING),
        s("JD-ICICIB", "Payment of Rs 3,500 is due on your ICICI Bank Credit Card XX9001 by 30-Oct-26.", Want.NOTHING),
        s("VK-SBIINB", "Get pre-approved personal loan up to Rs 5,00,000 instantly. Apply now on YONO.", Want.NOTHING),
        s("AX-AXISBK", "Exclusive offer! Get flat Rs 500 off on Flipkart with your Axis Bank Credit Card. T&C apply.", Want.NOTHING),
        s("VM-HDFCBK", "Use your HDFC Bank Credit Card XX5566 at Amazon and earn 5X reward points. Offer valid till 31-Oct.", Want.NOTHING),
        // ---- failed / declined / OTP ------------------------------------------------------------------------------
        n(gpay, "Payment failed", "Your payment of ₹250 to Chai Point failed. Any amount debited will be refunded", Want.NOTHING),
        n(phonepe, "Transaction declined", "₹1,200 payment to Zomato was declined by your bank", Want.NOTHING),
        s("VM-HDFCBK", "Txn of Rs 899.00 on HDFC Bank Card XX5566 at MYNTRA declined due to incorrect PIN.", Want.NOTHING),
        s("JD-KOTAKB", "482913 is the OTP for your transaction of Rs 2,499 at Croma on Kotak Card XX7788. Do not share it with anyone.", Want.NOTHING),
        n(paytm, "OTP", "Your Paytm OTP is 552190. Never share it with anyone", Want.NOTHING),
        // ---- credited cashback = income, no pop-up ------------------------------------------------------------------
        n(bhim, "Cashback received", "₹5 cashback credited to your account XX1234", Want.CASHBACK),
        n(paytm, "Cashback", "Cashback of ₹25 has been added to your Paytm Wallet", Want.CASHBACK),
        n(gpay, "Google Pay", "You received ₹11 cashback from Google Pay rewards. It's in your bank account", Want.CASHBACK),
        s("VM-ICICIB", "Dear Customer, cashback of Rs 150.00 has been credited to your ICICI Bank Credit Card XX9001.", Want.CASHBACK),
        s("AD-AXISBK", "INR 75.00 cashback credited to your Axis Bank A/c XX4321 on 24-10-26.", Want.CASHBACK),
        n(phonepe, "Cashback", "Cashback will be credited to your PhonePe wallet within 48 hours", Want.NOTHING),
        // ---- genuine debits (must still pop up) ---------------------------------------------------------------------
        n(gpay, "₹250 paid to Chai Point", "Paid ₹250.00 to Chai Point. UPI Ref 412345678901", Want.DEBIT),
        n(gpay, "Google Pay", "You paid ₹500 to Ramesh Kumar. You've won a scratch card!", Want.DEBIT),
        n(bhim, "BHIM", "₹120 sent to sharma.store@okicici. UPI Ref No 512398765432", Want.DEBIT),
        n(phonepe, "Paid successfully", "₹1,499 paid to Myntra successfully. Earn 2 SuperCoins on your next order", Want.DEBIT),
        n(paytm, "Payment successful", "Rs 60 paid to Metro Card Recharge from Paytm. Txn ID 7731122345", Want.DEBIT),
        n(cred, "payment successful", "₹12,450 paid to HDFC Bank credit card XX5566 successfully", Want.DEBIT),
        n(amazon, "Amazon Pay", "Payment of ₹349 to Swiggy was successful. Ref no 998877665544", Want.DEBIT),
        s("AX-AXISBK", "INR 2,340.00 spent on Axis Bank Card XX4321 at AMAZON on 24-10-26. Avl Lmt INR 1,22,000. Not you? SMS BLOCK to 919951860002", Want.DEBIT),
        s("VM-HDFCBK", "Rs.450.00 debited from A/c XX1234 on 24-10-26 to VPA chai@okaxis (CHAI WALA). UPI Ref 431234567890. Not you? Call 18002586161", Want.DEBIT),
        s("VM-HDFCBK", "You earned 10 reward points on txn of Rs 999.00 at FLIPKART using HDFC Bank Card XX5566.", Want.DEBIT),
        s("JD-SBIUPI", "Dear UPI user A/C X9876 debited by 200.0 on date 24Oct26 trf to ZEPTO Refno 429812345678. If not u? call 1800111109. -SBI", Want.DEBIT),
        // ---- genuine credits --------------------------------------------------------------------------------------
        n(gpay, "₹800 received", "Rahul Verma sent you ₹800. UPI Ref 412398761234", Want.CREDIT),
        s("VM-HDFCBK", "Rs.60000.00 credited to HDFC Bank A/c XX1234 on 01-10-26 by NEFT from ACME CORP. Avl bal Rs 1,02,345.00", Want.CREDIT),
    )

    private fun classify(c: Case): Want {
        val p = if (c.sms) TransactionParser.parseSms(c.src, c.text, 1L) else TransactionParser.parseNotification(c.src, c.title, c.text, 1L)
        return when {
            p == null -> Want.NOTHING
            p.type == TxnType.DEBIT -> Want.DEBIT
            p.category == TransactionParser.CASHBACK_CATEGORY -> Want.CASHBACK
            else -> Want.CREDIT
        }
    }

    @Test
    fun corpusIsLargeEnoughAndCoversEveryApp() {
        assertTrue(corpus.size >= 40, "corpus has ${corpus.size}")
        for (pkg in listOf(gpay, bhim, phonepe, paytm, cred, amazon)) assertTrue(corpus.any { it.src == pkg }, pkg)
        assertTrue(corpus.count { it.sms } >= 10)
    }

    @Test
    fun everyCorpusMessageIsClassifiedAsExpected() {
        val wrong = corpus.mapIndexedNotNull { i, c -> classify(c).takeIf { it != c.want }?.let { "#$i want ${c.want} got $it: ${c.text}" } }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }

    @Test
    fun promoNeverReachesThePopup() {
        // The pop-up is only for live DEBITs; nothing promotional may come out as one.
        for (c in corpus.filter { it.want != Want.DEBIT }) assertTrue(classify(c) != Want.DEBIT, c.text)
    }

    @Test
    fun classifierPrimitives() {
        assertTrue(PromoClassifier.isPromo("Get ₹15 back when you pay"))
        assertTrue(!PromoClassifier.isPromo("Paid ₹500 to X. You earned a scratch card. UPI Ref 412345678901"))
        assertTrue(PromoClassifier.isCashbackCredit("₹5 cashback credited to your account"))
        assertTrue(!PromoClassifier.isCashbackCredit("cashback will be credited within 2 days"))
        assertEquals(false, PromoClassifier.isPromo("₹5 cashback credited to your wallet"))
        // A debit alert that mentions credited cashback is still the debit (R3-04).
        assertTrue(!PromoClassifier.isCashbackCredit("Rs 500 debited from A/c XX1234 to Swiggy. Cashback of Rs 5 credited to your account"))
        val p = TransactionParser.parseSms("VM-HDFCBK", "Rs 500.00 debited from A/c XX1234 to VPA swiggy@icici UPI Ref 431234567000. Cashback of Rs 5 credited to your account", 1L)!!
        assertEquals(TxnType.DEBIT, p.type); assertEquals(500.0, p.amount)
        // ...and real cashback mentioning the earlier payment is still cashback (R4-04).
        assertTrue(PromoClassifier.isCashbackCredit("₹5 cashback credited to your account for the payment sent to Chai Point"))
    }
}
