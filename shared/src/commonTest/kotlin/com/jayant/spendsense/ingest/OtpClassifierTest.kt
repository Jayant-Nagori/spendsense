package com.jayant.spendsense.ingest

import com.jayant.spendsense.ai.AssistantEngine
import com.jayant.spendsense.ai.AssistantTools
import com.jayant.spendsense.ai.FakeData
import com.jayant.spendsense.ai.LlmClient
import com.jayant.spendsense.ai.LlmMessage
import com.jayant.spendsense.ai.LlmTurn
import com.jayant.spendsense.ai.NOW
import com.jayant.spendsense.ai.ToolSpec
import com.jayant.spendsense.ai.millis
import com.jayant.spendsense.ai.txn
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.imports.statement.AiStatementParser
import com.jayant.spendsense.json.JSONObject
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OTP / verification-code messages are dropped everywhere; debit alerts that only *warn* about OTPs are kept. */
class OtpClassifierTest {

    private val otps = listOf(
        "123456 is OTP for txn of Rs 2,500.00 at AMAZON on HDFC Bank card 4321. Do not share OTP with anyone.",
        "123456 is the OTP to authorise a UPI payment of Rs.500.00 to Swiggy. Valid 5 mins. Do not share.",
        "OTP for transaction of INR 1,299.00 at MYNTRA on ICICI Bank Card XX1234 is 884512. Valid for 10 minutes.",
        "Dear Customer, 482910 is the One Time Password (OTP) for your txn of INR 2,000.00 at FLIPKART on card XX1234. Valid for 10 mins.",
        "Your OTP is 7731. Do not share it with anyone.",
        "OTP: 004512 for login to NetBanking. Never share this OTP with anyone.",
        "Your One Time Password (OTP) is 662190 for registering on SBI YONO.",
        "G-123456 is your Google verification code.",
        "Your verification code is 9921",
        "Use OTP 553201 to verify your mobile number on PhonePe.",
        "Your Swiggy login code is 4821",
        "Never share this OTP: 482913. HDFC Bank never asks for it.",
        "Your authentication code for Amazon Pay is 228811",
        "Your security code is 30491. It expires in 5 minutes.",
        "Use 482913 as your one-time password to complete the payment of Rs 499 on Zepto",
        // Hinglish
        "Aapka OTP 482913 hai. Ise kisi ke saath share na karein.",
        "482913 aapka SBI OTP hai, kisi ko na batayein.",
        "OTP: 482913. Yeh OTP kisi ko mat batana -Axis Bank",
        "Aapka verification code 7712 hai",
        "Rs 1500 ke bhugtan ke liye aapka OTP 662211 hai. Kisi ko na batayein.",
        // Code masked / not visible
        "Your OTP for the transaction of Rs 4,000 at Croma has been sent. Enter the OTP to complete the payment.",
        "Use the OTP sent to your registered number to authorise the payment. Valid for 3 minutes.",
        // Multiline
        "OTP\n583920\nfor UPI PIN reset\nDo not share",
    )

    private val notOtps = listOf(
        "Rs 1,250.00 debited from a/c **4321 on 22-10-26 to VPA swiggy@icici (UPI Ref No 529512345678). Never share your OTP with anyone.",
        "Sent Rs.254.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 22/10/26\nRef 529512345678\nNot You? Call 18002586161. Never share OTP/PIN with anyone.",
        "Your A/c XX1234 is debited for INR 2,000.00 on 22-Oct-26 towards UPI/Zomato. Avl Bal INR 12,345.50. Do not share OTP, PIN or CVV with anyone - Axis Bank",
        "INR 899.00 spent on ICICI Bank Card XX9876 on 22-Oct-26 at AMAZON. Avl Limit: INR 45,000. To dispute call 18001080. ICICI Bank never asks for OTP.",
        "Dear Customer, Rs 500.00 debited from A/c XX1234 on 22Oct26 trf to Ramesh Kumar Refno 529512345678. If not done by you, forward this SMS to 9223008333. Bank never asks for your OTP/PIN - SBI",
        "Aapke khate XX1234 se Rs 500 debit hue, UPI Ref 529512345678. OTP kisi ke saath share na karein -SBI",
        "Rs 240 paid to Blinkit via UPI. Never share your OTP or 4 digit PIN with anyone",
        "Credited Rs 5,000.00 to a/c XX4321 by NEFT from ACME CORP. Never share OTP with anyone avl bal is 15000",
        "Never share OTP with anyone. Call 1800 1234 for help.",
        "Your card ending 4321 was used for Rs 1299 at MYNTRA. Bank will never call you for OTP or PIN.",
        "Paid ₹180.00 to Chai Point",
        "Hello, your order #4821 is out for delivery",
        "",
    )

    @Test fun recognisesOtpMessages() {
        otps.forEach { assertTrue(OtpClassifier.isOtp(it), "should be OTP: $it") }
    }

    @Test fun keepsDebitAlertsThatOnlyWarnAboutOtps() {
        notOtps.forEach { assertFalse(OtpClassifier.isOtp(it), "should NOT be OTP: $it") }
    }

    @Test fun nullAndHugeInputAreSafe() {
        assertFalse(OtpClassifier.isOtp(null))
        assertFalse(OtpClassifier.isOtp("x".repeat(2_000_000)))
        // Must return promptly (input is capped), whatever the verdict.
        OtpClassifier.isOtp("otp is ".repeat(200_000) + "1234")
    }

    @Test fun parserStillCapturesDebitsWithOtpWarnings() {
        val p = TransactionParser.parseSms("VM-HDFCBK", notOtps[0], 1_761_150_000_000L)
        assertNotNull(p)
        assertEquals(1250.0, p.amount, 0.001)
        assertEquals(TxnType.DEBIT, p.type)
        val axis = TransactionParser.parseSms("AX-AXISBK", notOtps[2], 1_761_150_000_000L)
        assertNotNull(axis)
        assertEquals(2000.0, axis.amount, 0.001)
        val sbi = TransactionParser.parseSms("AD-SBIINB", notOtps[4], 1_761_150_000_000L)
        assertNotNull(sbi)
        assertEquals(500.0, sbi.amount, 0.001)
    }

    @Test fun parserDropsEveryOtpFromSmsAndNotifications() {
        otps.forEach { body ->
            assertNull(TransactionParser.parseSms("VM-HDFCBK", body, 1L), "sms: $body")
            assertNull(TransactionParser.parseNotification("net.one97.paytm", "Paytm", body, 1L), "notification: $body")
            assertNull(TransactionParser.parseNotification("com.axis.mobile", body, "Payment of Rs 500 successful", 1L), "title: $body")
        }
    }

    // ---- the AI assistant never receives OTP text ----

    private class RecordingLlm : LlmClient {
        val calls = mutableListOf<List<LlmMessage>>()
        override suspend fun complete(system: String, messages: List<LlmMessage>, tools: List<ToolSpec>, allowTools: Boolean, maxTokens: Int): LlmTurn {
            calls += messages
            return LlmTurn("ok", emptyList(), com.jayant.spendsense.ai.StopReason.END)
        }
    }

    @Test fun assistantNeverForwardsAPastedOtp() = runTest {
        val llm = RecordingLlm()
        val engine = AssistantEngine(llm, AssistantTools(FakeData()), { NOW })
        val r = engine.ask("Bank sent me this: 482913 is your OTP for txn of Rs 5000 at Croma. What is it?")
        assertEquals(AssistantEngine.OTP_REPLY, r.text)
        assertTrue(llm.calls.isEmpty())
        engine.ask("how much did I spend on food?")
        assertEquals(1, llm.calls.size)
    }

    @Test fun assistantToolsOmitOtpRawText() {
        val otpRow = txn(1, 2500.0, "Amazon", millis(2026, 10, 20)).copy(rawText = otps[0])
        val okRow = txn(2, 254.0, "Swiggy", millis(2026, 10, 21))
        val tools = AssistantTools(FakeData(listOf(otpRow, okRow)))
        val rows = JSONObject(tools.execute("search_transactions", JSONObject("""{"include_raw":true}""")).json).getJSONArray("rows")
        val byId = (0 until rows.length()).associate { rows.getJSONObject(it).getLong("id") to rows.getJSONObject(it) }
        assertFalse(byId.getValue(1L).has("raw"))
        assertTrue(byId.getValue(2L).has("raw"))
    }

    @Test fun statementLinesWithOtpsAreNotSentToTheModel() {
        val parser = AiStatementParser(RecordingLlm())
        val chunks = parser.chunk(listOf("22/10/26 UPI/SWIGGY 254.00 DR", otps[4], "23/10/26 NEFT ACME 60000.00 CR"))
        val all = chunks.joinToString("\n")
        assertFalse(all.contains("7731"))
        assertTrue(all.contains("SWIGGY"))
    }
}
