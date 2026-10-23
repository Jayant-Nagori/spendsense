package com.jayant.spendsense.imports.email

import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.TxnType
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailTextTest {
    private val ts = 1_761_150_000_000L
    private val day = 24L * 60 * 60 * 1000

    // ---------- HTML -> text ----------

    @Test fun htmlStripsTagsAndStyle() {
        val t = HtmlText.toText("<html><head><style>.a{color:red}</style><title>x</title></head><body><p>Hello <b>world</b></p></body></html>")
        assertEquals("Hello world", t)
    }

    @Test fun htmlDecodesEntities() {
        assertEquals("Rs. 1,200 & more <ok> \"q\" ₹5 ₹6", HtmlText.toText("Rs.&nbsp;1,200 &amp; more &lt;ok&gt; &quot;q&quot; &#8377;5 &#x20B9;6"))
    }

    @Test fun htmlBreaksAndCellsKeepWordsApart() {
        val t = HtmlText.toText("<table><tr><td>Amount</td><td>Rs. 500</td></tr><tr><td>To</td><td>SWIGGY</td></tr></table>line1<br/>line2")
        assertTrue(t, "Amount Rs. 500" in t && "To SWIGGY" in t)
        assertTrue(t, "line1\nline2" in t)
    }

    @Test fun htmlDropsCommentsAndZeroWidth() {
        assertEquals("Hi there", HtmlText.toText("<!-- hidden --><div>Hi&zwnj;&#8203; there</div>"))
    }

    @Test fun unknownEntityIsLeftAlone() {
        assertEquals("a &foo; b", HtmlText.decodeEntities("a &foo; b"))
    }

    // ---------- sender mapping ----------

    @Test fun senderCodesForKnownBanks() {
        assertEquals("HDFCBK", EmailSenders.senderCode("HDFC Bank InstaAlerts <alerts@hdfcbank.net>"))
        assertEquals("AXISBK", EmailSenders.senderCode("alerts@axisbank.com"))
        assertEquals("ICICIB", EmailSenders.senderCode("ICICI Bank <credit_cards@icicibank.com>"))
        assertEquals("SBIINB", EmailSenders.senderCode("SBI <cbsalerts.sbi@alerts.sbi.co.in>"))
        assertEquals("SBICRD", EmailSenders.senderCode("SBI Card <Statements@sbicard.com>"))
        assertEquals("KOTAKB", EmailSenders.senderCode("bankalerts@kotak.com"))
    }

    @Test fun senderCodeFallbackIsBankStyleAndParserSafe() {
        assertEquals("EM-ZOMATO", EmailSenders.senderCode("Zomato <orders@mail.zomato.com>"))
        assertEquals("EM-GPAY", EmailSenders.senderCode("Google Pay <noreply-payments@google.com>"))
        assertEquals("EM-ABXX", EmailSenders.senderCode("x@ab.in"))
        assertTrue(EmailSenders.isKnown("alerts@hdfcbank.net"))
        assertFalse(EmailSenders.isKnown("friend@gmail.com"))
        assertEquals("hdfcbank.net", EmailSenders.domain("HDFC <Alerts@HDFCBank.net>"))
    }

    // ---------- realistic bank emails (HTML) ----------

    @Test fun hdfcUpiDebitEmail() {
        val html = """
            <html><body style="font-family:Arial"><table width="600"><tr><td>
            <p>Dear Customer,</p>
            <p>Rs.&nbsp;254.00 has been debited from account <b>**1234</b> to VPA <b>swiggy@icici</b> SWIGGY on 22-10-26.</p>
            <p>Your UPI transaction reference number is <b>529512345678</b>.</p>
            <p>If you did not authorize this transaction, please report it immediately.</p>
            <p>Warm Regards,<br>HDFC Bank</p></td></tr></table></body></html>
        """.trimIndent()
        val p = EmailAlertParser.parse("HDFC Bank InstaAlerts <alerts@hdfcbank.net>", "❗ You have done a UPI txn. Check details!", null, html, ts)
        assertNotNull(p); p!!
        assertEquals(254.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals("swiggy@icici", p.counterparty)
        assertEquals("529512345678", p.upiRef)
        assertEquals("HDFC Bank", p.bank)
        assertEquals(PaymentMode.UPI, p.paymentMode)
        assertEquals("XX1234", p.account)
        assertEquals(ts, p.timestamp)
    }

    @Test fun iciciCreditCardEmail() {
        val html = """
            <div><p>Dear Customer,</p>
            <p>Your ICICI Bank Credit Card XX4321 has been used for a transaction of INR 1,234.56 on Oct 22, 2026 at 10:15:00.
            Info: AMAZON PAY INDIA.</p>
            <p>The Available Credit Limit on your card is INR 98,765.00 and Total Credit Limit is INR 1,00,000.00.</p>
            <p>In case you have not done this transaction, please call 1800 1080.</p>
            <p>Sincerely,<br/>Team ICICI Bank</p></div>
        """.trimIndent()
        val p = EmailAlertParser.parse("ICICI Bank <credit_cards@icicibank.com>", "Transaction alert for your ICICI Bank Credit Card", null, html, ts)
        assertNotNull(p); p!!
        assertEquals(1234.56, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals(PaymentMode.CARD, p.paymentMode)
        assertEquals("ICICI Bank", p.bank)
        assertEquals("XX4321", p.account)
        assertEquals("Amazon Pay India", p.counterparty)
    }

    @Test fun sbiDebitEmail() {
        val html = "<p>Dear Customer,</p><p>Your A/c X4321 is debited by Rs.1,500.00 on 22Oct26 and A/c XX9988 credited (IMPS Ref no 529600001111).</p><p>Do not share OTP with anyone.</p>"
        val p = EmailAlertParser.parse("SBI <cbsalerts.sbi@alerts.sbi.co.in>", "Transaction alert", null, html, ts)
        assertNotNull(p); p!!
        assertEquals(1500.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals("SBI", p.bank)
        assertEquals("529600001111", p.upiRef)
    }

    @Test fun axisDebitEmail() {
        val html = """<html><body><p>Dear Customer,</p><p>Thank you for using your Axis Bank Credit Card no. XX7788 for INR 899.00 at ZOMATO on 22-10-2026 10:15:00 IST.</p>
            <p>Available limit: INR 55,000.00</p><p>Stay alert against fraud.</p></body></html>"""
        val p = EmailAlertParser.parse("alerts@axisbank.com", "Transaction alert on Axis Bank Credit Card no. XX7788", null, html, ts)
        assertNotNull(p); p!!
        assertEquals(899.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        assertEquals("Axis Bank", p.bank)
        assertEquals("Zomato", p.counterparty)
    }

    @Test fun hdfcCreditEmail() {
        val html = "<p>Dear Customer,</p><p>Rs. 5,000.00 is successfully credited to your account **1234 by VPA ramesh.k@okaxis ramesh on 22-10-26. Your UPI transaction reference number is 529412345671.</p>"
        val p = EmailAlertParser.parse("alerts@hdfcbank.net", "You have received a UPI credit", null, html, ts)
        assertNotNull(p); p!!
        assertEquals(5000.0, p.amount, 0.001)
        assertEquals(TxnType.CREDIT, p.type)
        assertEquals("ramesh.k@okaxis", p.counterparty)
    }

    @Test fun plainTextIsPreferredOverHtml() {
        val p = EmailAlertParser.parse(
            "alerts@hdfcbank.net", null,
            "Rs. 100.00 has been debited from account **1234 to VPA a@b A on 22-10-26. Your UPI transaction reference number is 123456789012.",
            "<p>Rs. 999.00 has been debited</p>", ts,
        )
        assertEquals(100.0, p!!.amount, 0.001)
    }

    // ---------- rejects ----------

    @Test fun otpEmailRejected() {
        assertNull(EmailAlertParser.parse("alerts@hdfcbank.net", "OTP for transaction", "<p>Your OTP for transaction of Rs. 500 at AMAZON is 123456. Do not share.</p>", null, ts))
    }

    @Test fun statementReminderRejected() {
        assertNull(EmailAlertParser.parse("credit_cards@icicibank.com", "Payment reminder", null, "<p>Total amount due Rs. 12,000.00 on your credit card XX4321. Pay now to avoid charges.</p>", ts))
    }

    @Test fun footerBoilerplateDoesNotRejectGenuineAlert() {
        val body = "Rs. 321.00 has been debited from account **1234 to VPA zepto@axis ZEPTO on 22-10-26. Your UPI transaction reference number is 529512340000. " +
            "Warm Regards, HDFC Bank. Do not share your OTP, CVV or PIN. Special offer valid till month end, apply now."
        assertNotNull(EmailAlertParser.parse("alerts@hdfcbank.net", "UPI txn", body, null, ts))
    }

    // ---------- since window ----------

    @Test fun sinceFirstSyncUsesRange() {
        assertEquals(ts - 90 * day, EmailAlertParser.computeSince(ts, 3, 0, 0))
    }

    @Test fun sinceResumesNearLastSync() {
        val last = ts - 1 * day
        assertEquals(last - 2 * day, EmailAlertParser.computeSince(ts, 6, last, ts - 200 * day))
    }

    @Test fun sinceWidenedRangeRereads() {
        assertEquals(ts - 360 * day, EmailAlertParser.computeSince(ts, 12, ts - day, ts - 90 * day))
    }

    // ---------- failure classification ----------

    @Test fun failureClassification() {
        assertEquals(EmailFailure.WRONG_PASSWORD, EmailFailure.classify(RuntimeException("[AUTHENTICATIONFAILED] Invalid credentials (Failure)")))
        assertEquals(EmailFailure.WRONG_PASSWORD, EmailFailure.classify(RuntimeException("[ALERT] Application-specific password required")))
        assertEquals(EmailFailure.IMAP_DISABLED, EmailFailure.classify(RuntimeException("[ALERT] Your account is not enabled for IMAP use.")))
        assertEquals(EmailFailure.NO_NETWORK, EmailFailure.classify(RuntimeException("wrap", UnknownHostException("imap.gmail.com"))))
        assertEquals(EmailFailure.NO_NETWORK, EmailFailure.classify(SocketTimeoutException("t")))
        assertEquals(EmailFailure.OTHER, EmailFailure.classify(IllegalStateException("boom")))
    }
}
