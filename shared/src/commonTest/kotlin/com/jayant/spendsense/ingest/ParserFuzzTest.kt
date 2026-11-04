package com.jayant.spendsense.ingest

import com.jayant.spendsense.imports.email.EmailAlertParser
import com.jayant.spendsense.imports.email.HtmlText
import com.jayant.spendsense.imports.statement.AiStatementParser
import com.jayant.spendsense.imports.statement.Narration
import com.jayant.spendsense.imports.statement.StatementImporter
import com.jayant.spendsense.json.JSONObject
import com.jayant.spendsense.orders.FakeOrderServer
import com.jayant.spendsense.orders.McpClient
import com.jayant.spendsense.orders.OrderParser
import com.jayant.spendsense.orders.OrderSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Robustness: random, malformed, huge and unicode input to every text parser must neither throw nor hang.
 * Deterministic seeds so a failure reproduces. Runs on the JVM and on the iOS simulator.
 */
/** Per-platform fuzz volume: the JVM runs more; the iOS debug test binary is unoptimised and much slower. */
expect val fuzzIterations: Int

/** Size factor for the huge-input cases (the unoptimised iOS test binary runs Kotlin/Native regex far slower). */
expect val hugeScale: Double

private fun scaled(n: Int): Int = (n * hugeScale).toInt().coerceAtLeast(1)

class ParserFuzzTest {

    private val samples = listOf(
        "Sent Rs.254.00\nFrom HDFC Bank A/C *1234\nTo SWIGGY\nOn 22/10/26\nRef 529512345678\nNot You?",
        "Rs 1,250.00 debited from a/c **4321 on 22-10-26 to VPA swiggy@icici (UPI Ref No 529512345678). Never share your OTP.",
        "INR 899.00 spent on ICICI Bank Card XX9876 on 22-Oct-26 at AMAZON. Avl Limit: INR 45,000.",
        "123456 is OTP for txn of Rs 2,500.00 at AMAZON on HDFC Bank card 4321. Do not share OTP with anyone.",
        "Refund of Rs 1250.00 from DMART credited to HDFC Bank Card XX4321",
        "Aapke khate XX1234 se Rs 500 debit hue, UPI Ref 529512345678. OTP kisi ke saath share na karein -SBI",
    )

    private val alphabet = (
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .,:;-/@*#()[]{}'\"\\\n\t₹" +
            "आपकाखातेसेडेबिट" + "€£¥" + "​‎‮﻿ ́" + "😀🏦💸" + "ارسالשלום"
        ).let { s -> buildList { var i = 0; while (i < s.length) { val c = s[i]; if (c.isHighSurrogate() && i + 1 < s.length) { add(s.substring(i, i + 2)); i += 2 } else { add(c.toString()); i++ } } } }

    private val words = listOf("Rs", "Rs.", "INR", "₹", "debited", "credited", "spent", "paid", "to", "from", "at", "on", "via", "UPI", "Ref",
        "a/c", "XX1234", "card", "OTP", "is", "your", "refund", "reversed", "1,23,456.78", "999999999999", "0.00", "-5", "1e308", "NaN")

    private fun randomText(r: Random, maxLen: Int): String {
        val n = r.nextInt(maxLen + 1)
        val sb = StringBuilder()
        while (sb.length < n) sb.append(if (r.nextInt(3) == 0) words.random(r) + " " else alphabet.random(r))
        return sb.toString()
    }

    private fun mutate(r: Random, s: String): String {
        val sb = StringBuilder(s)
        repeat(1 + r.nextInt(8)) {
            if (sb.isEmpty()) return@repeat
            val i = r.nextInt(sb.length)
            when (r.nextInt(4)) {
                0 -> sb.deleteAt(i)
                1 -> sb.insert(i, alphabet.random(r))
                2 -> sb[i] = alphabet.random(r).first()
                else -> sb.insert(i, words.random(r))
            }
        }
        return sb.toString()
    }

    private inline fun bounded(limit: Duration, what: String, block: () -> Unit) {
        val mark = TimeSource.Monotonic.markNow()
        block()
        val took = mark.elapsedNow()
        assertTrue(took < limit, "$what took $took (limit $limit)")
    }

    private fun everyParser(text: String) {
        TransactionParser.parseSms("VM-HDFCBK", text, 1L)
        TransactionParser.parseSms(text.take(20), text, 1L)
        TransactionParser.parseNotification("com.phonepe.app", text.take(60), text, 1L)
        OtpClassifier.isOtp(text)
        CategoryGuesser.guess(text.take(40), text)
        Narration.counterparty(text)
        Narration.upiRef(text)
        EmailAlertParser.clean(text.take(80), text, null)
        EmailAlertParser.parse("alerts@hdfcbank.net", text.take(80), text, text, 1L)
        HtmlText.toText(text)
        StatementImporter.parseLines(text.lines())
        StatementImporter.parseCsvText(text)
        StatementImporter.detectBank(text)
        AiStatementParser.parseReply(text)
        OrderParser.parseText(OrderSource.SWIGGY_FOOD, text)
        OrderParser.parseToolResult(OrderSource.ZEPTO, FakeOrderServer.textResult(text))
        McpClient.parseSse(text)
    }

    @Test fun randomAndMutatedInputNeverThrows() {
        val r = Random(20261024)
        bounded(120.seconds, "${2 * fuzzIterations} random inputs") {
            repeat(fuzzIterations) { everyParser(randomText(r, 400)) }
            repeat(fuzzIterations) { everyParser(mutate(r, samples.random(r))) }
        }
    }

    @Test fun hugeInputsDoNotHang() {
        val huge = listOf(
            "Rs 1,00".repeat(scaled(30_000)),
            "debited ".repeat(scaled(30_000)) + "Rs 500",
            "Rs 500 debited to " + "A".repeat(scaled(200_000)),
            "at ".repeat(scaled(50_000)) + "SWIGGY",
            "otp is ".repeat(scaled(50_000)),
            "1234567 ".repeat(scaled(40_000)),
            ("Rs 254 debited from a/c XX1234 to SWIGGY Ref 529512345678\n").repeat(scaled(5_000)),
            "😀".repeat(scaled(100_000)),
            "́".repeat(scaled(200_000)),
            "(".repeat(scaled(100_000)) + ")".repeat(scaled(100_000)),
        )
        huge.forEachIndexed { i, text -> bounded(30.seconds, "huge #$i (${text.length} chars)") { everyParser(text) } }
    }

    @Test fun malformedMcpPayloadsAreSafe() {
        val r = Random(7)
        val payloads = buildList {
            add("""{"content":[{"type":"text","text":"${"[".repeat(3000)}"}]}""")
            add("""{"structuredContent":${"{\"a\":".repeat(500)}1${"}".repeat(500)}}""")
            add("""{"content":"not-an-array"}""")
            add("""{"content":[{"type":"text","text":null}]}""")
            add("""{"structuredContent":{"orders":[{"orderId":null,"amountPaise":"NaN","orderTime":"yesterday","items":"x"}]}}""")
            add("""{"structuredContent":{"orders":[{"orderId":"1","total":-1e308,"orderTime":9.9e99}]}}""")
            repeat(300) { add("{\"structuredContent\":{\"orders\":[{\"orderId\":\"${randomText(r, 30).replace("\"", "").replace("\\", "")}\",\"total\":${r.nextDouble() * 1e6}}]}}") }
        }
        bounded(30.seconds, "MCP payloads") {
            payloads.forEach { p ->
                val obj = runCatching { JSONObject(p) }.getOrNull() ?: return@forEach
                OrderParser.parseToolResult(OrderSource.SWIGGY_FOOD, obj)
                OrderParser.fromValue(OrderSource.ZOMATO, obj)
            }
        }
    }
}
