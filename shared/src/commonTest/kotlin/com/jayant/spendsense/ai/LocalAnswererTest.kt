package com.jayant.spendsense.ai

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class LocalAnswererTest {
    private val answerer = LocalAnswerer(sampleData()) { NOW }

    @Test fun findsByAmountAndDate() {
        val a = answerer.answer("where did I spend 254 rs on 12th October")
        assertEquals(listOf(1L), a.txns.map { it.id })
        assertTrue(a.text.startsWith("Found 1 transaction"))
    }

    @Test fun amountWithoutDateFindsAll() {
        val a = answerer.answer("where did I spend ₹254")
        assertEquals(setOf(1L, 2L), a.txns.map { it.id }.toSet())
    }

    @Test fun amountMatchesSplitShare() {
        val a = answerer.answer("did I pay 300 somewhere")
        assertEquals(listOf(3L), a.txns.map { it.id })
    }

    @Test fun noMatchSaysSoAndShowsWhatWasRecorded() {
        val a = answerer.answer("where did I spend 9999 yesterday")
        assertTrue(a.text.startsWith("No ₹9,999 payment yesterday"), a.text)
        assertEquals(listOf(3L), a.txns.map { it.id }) // Zomato was the only payment yesterday
    }

    // ---- smarter offline answers ----

    private fun with(vararg extra: com.jayant.spendsense.data.Transaction) =
        LocalAnswerer(FakeData(txns = extra.toList())) { NOW }

    @Test fun twoPaymentsThatAddUp() {
        val a = with(
            txn(10, 120.0, "Chaayos", millis(2026, 10, 22, 9)),
            txn(11, 90.0, "Blinkit", millis(2026, 10, 22, 11)),
            txn(12, 500.0, "Croma", millis(2026, 10, 22, 12)),
        ).answer("where did i spent 210 today")
        assertTrue(a.text.contains("add up to ₹210"), a.text)
        assertEquals(setOf(10L, 11L), a.txns.map { it.id }.toSet())
    }

    @Test fun sameAmountOnANearbyDay() {
        val a = with(txn(10, 210.0, "Chai Point", millis(2026, 10, 21, 18))).answer("where did I spend 210 today")
        assertTrue(a.text.contains("close by") && a.text.contains("Chai Point"), a.text)
    }

    @Test fun roundingDifference() {
        val a = with(txn(10, 209.0, "Swiggy", millis(2026, 10, 22, 13))).answer("where did I spend 210 today")
        assertTrue(a.text.contains("Did you mean ₹209"), a.text)
    }

    @Test fun nothingCapturedTodayExplainsWhy() {
        val a = with(txn(10, 99.0, "Old", millis(2026, 9, 1))).answer("where did I spend 210 today")
        assertTrue(a.text.contains("no payments recorded today") && a.text.contains("+ ADD"), a.text)
    }

    @Test fun hinglishAndTypos() {
        val l = with(txn(10, 210.0, "Chai Point", millis(2026, 10, 22, 9)))
        assertEquals(listOf(10L), l.answer("aaj 210 kaha kharcha kiya").txns.map { it.id })
        assertEquals(listOf(10L), l.answer("where did i spnt 210 tday").txns.map { it.id })
        assertTrue(l.answer("aaj kitna kharcha hua").text.contains("₹210"))
    }

    @Test fun payeeWordWithoutPreposition() {
        val a = with(
            txn(10, 40.0, "Chai Wala", millis(2026, 10, 21, 9)),
            txn(11, 30.0, "Chai Wala", millis(2026, 10, 22, 9)),
            txn(12, 900.0, "Croma", millis(2026, 10, 22, 12)),
        ).answer("chai this week")
        assertEquals(setOf(10L, 11L), a.txns.map { it.id }.toSet())
    }

    @Test fun lastPayment() {
        val a = answerer.answer("what was my last payment")
        assertEquals(5L, a.txns.first().id)
    }

    @Test fun howManyTimes() {
        val a = with(
            txn(10, 300.0, "Swiggy", millis(2026, 10, 3), category = "Food"),
            txn(11, 250.0, "Swiggy", millis(2026, 10, 9), category = "Food"),
        ).answer("how many times did I order swiggy this month")
        assertTrue(a.text.startsWith("2 times"), a.text)
    }

    @Test fun breakdown() {
        val a = answerer.answer("where did my money go this month")
        assertTrue(a.text.contains("Shopping: ₹5,000"), a.text)
    }

    @Test fun compareWithLastMonth() {
        val a = answerer.answer("this month vs last month")
        assertTrue(a.text.startsWith("You've spent ₹6,804 this month"), a.text)
    }

    @Test fun dailyAverage() {
        val a = answerer.answer("average daily spend this month")
        assertTrue(a.text.contains("a day"), a.text)
    }

    @Test fun totalTodayUsesShare() {
        val a = answerer.answer("how much did I spend today")
        assertTrue(a.text.contains("₹800"), a.text)
    }

    @Test fun totalThisMonthUsesMyShareAndMentionsPaid() {
        // debits this month: 254 + 300(share of 1200) + 5000 + 800 + 450 = 6804 ; paid 7704
        val a = answerer.answer("how much did I spend this month")
        assertTrue(a.text.contains("₹6,804"), a.text)
        assertTrue(a.text.contains("₹7,704"), a.text)
    }

    @Test fun totalOnCategory() {
        // food this month: 254 + 300 + 450 = 1004
        val a = answerer.answer("how much did I spend on food this month")
        assertTrue(a.text.contains("₹1,004"), a.text)
        assertEquals(3, a.txns.size)
    }

    @Test fun biggestExpense() {
        val a = answerer.answer("what's my biggest expense this month")
        assertEquals(4L, a.txns.first().id)
        assertTrue(a.text.contains("₹5,000"))
    }

    @Test fun splitsOwed() {
        val a = answerer.answer("how much do people owe me from splits")
        assertTrue(a.text.contains("₹900"), a.text)
    }

    @Test fun investmentsByType() {
        // MF invested 150000, current 125000 + 50000 (unknown -> invested) = 175000
        val a = answerer.answer("what's my total invested in mutual funds")
        assertTrue(a.text.contains("₹1,50,000"), a.text)
        assertTrue(a.text.contains("₹1,75,000"), a.text)
    }

    @Test fun unknownGivesHelp() {
        val a = answerer.answer("hello")
        assertFalse(a.understood)
    }

    // ---- paid for others ----
    private fun forOthers() = LocalAnswerer(
        FakeData(txns = listOf(
            txn(20, 250.0, "chai@okaxis", millis(2026, 10, 20, 9), paidForOthers = true, paidFor = "Rahul"),
            txn(21, 400.0, "Dominos", millis(2026, 10, 18, 21), paidForOthers = true, paidFor = "Rahul"),
            txn(22, 1000.0, "Amazon", millis(2026, 9, 5, 12), paidForOthers = true, paidFor = "Priya"),
            txn(23, 90.0, "Auto", millis(2026, 10, 21, 8), paidForOthers = true),
            txn(24, 500.0, "Swiggy", millis(2026, 10, 21, 9)),
        )),
    ) { NOW }

    @Test fun howMuchDidIPayForOthers() {
        val a = forOthers().answer("how much did I pay for others")
        assertTrue(a.text.contains("₹1,740"), a.text)
        assertTrue(a.text.contains("Rahul owes ₹650"), a.text)
        assertTrue(a.text.contains("Priya owes ₹1,000"), a.text)
        assertEquals(setOf(20L, 21L, 22L, 23L), a.txns.map { it.id }.toSet())
    }

    @Test fun whoOwesMeAndHinglish() {
        for (q in listOf("who owes me", "kisko paise diye")) {
            val a = forOthers().answer(q)
            assertTrue(a.text.contains("owes"), q + ": " + a.text)
            assertEquals(4, a.txns.size)
        }
    }

    @Test fun paidForOthersNarrowsByPersonAndDate() {
        val rahul = forOthers().answer("how much did I pay for rahul")
        assertTrue(rahul.text.contains("₹650") && rahul.text.contains("Rahul owes you"), rahul.text)
        val thisMonth = forOthers().answer("who owes me this month")
        assertTrue(thisMonth.text.contains("₹740") && !thisMonth.text.contains("Priya"), thisMonth.text)
    }

    @Test fun paidForOthersCountsZeroInSpendTotals() {
        val a = forOthers().answer("how much did I spend this month")
        assertTrue(a.text.contains("₹500") && a.text.contains("you paid ₹1,240 in total"), a.text)
    }

    @Test fun nothingPaidForOthersSaysSo() {
        val a = with(txn(30, 100.0, "Swiggy", millis(2026, 10, 21))).answer("kisko paise diye")
        assertTrue(a.text.contains("haven't marked"), a.text)
    }
}
