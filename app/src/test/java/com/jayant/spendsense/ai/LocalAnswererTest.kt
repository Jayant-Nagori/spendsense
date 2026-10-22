package com.jayant.spendsense.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun noMatchSaysSo() {
        val a = answerer.answer("where did I spend 9999 yesterday")
        assertTrue(a.txns.isEmpty())
        assertTrue(a.text.contains("couldn't find"))
    }

    @Test fun totalTodayUsesShare() {
        val a = answerer.answer("how much did I spend today")
        assertTrue(a.text, a.text.contains("₹800"))
    }

    @Test fun totalThisMonthUsesMyShareAndMentionsPaid() {
        // debits this month: 254 + 300(share of 1200) + 5000 + 800 + 450 = 6804 ; paid 7704
        val a = answerer.answer("how much did I spend this month")
        assertTrue(a.text, a.text.contains("₹6,804"))
        assertTrue(a.text, a.text.contains("₹7,704"))
    }

    @Test fun totalOnCategory() {
        // food this month: 254 + 300 + 450 = 1004
        val a = answerer.answer("how much did I spend on food this month")
        assertTrue(a.text, a.text.contains("₹1,004"))
        assertEquals(3, a.txns.size)
    }

    @Test fun biggestExpense() {
        val a = answerer.answer("what's my biggest expense this month")
        assertEquals(4L, a.txns.first().id)
        assertTrue(a.text.contains("₹5,000"))
    }

    @Test fun splitsOwed() {
        val a = answerer.answer("how much do people owe me from splits")
        assertTrue(a.text, a.text.contains("₹900"))
    }

    @Test fun investmentsByType() {
        // MF invested 150000, current 125000 + 50000 (unknown -> invested) = 175000
        val a = answerer.answer("what's my total invested in mutual funds")
        assertTrue(a.text, a.text.contains("₹1,50,000"))
        assertTrue(a.text, a.text.contains("₹1,75,000"))
    }

    @Test fun unknownGivesHelp() {
        val a = answerer.answer("hello")
        assertFalse(a.understood)
    }
}
