package com.jayant.spendsense.ai

import com.jayant.spendsense.data.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.Test
import kotlinx.datetime.LocalDate
import com.jayant.spendsense.time.*

class QueryParserTest {
    private fun parse(q: String) = QueryParser.parse(q, NOW)
    private fun d(y: Int, m: Int, day: Int) = LocalDate(y, m, day)
    private fun assertDay(q: String, date: LocalDate) {
        val r = parse(q).range
        assertNotNull(r, "no range for '$q'")
        assertEquals(date, r!!.from)
        assertEquals(date.plusDays(1), r.toExclusive)
    }

    // ---- amounts ----
    @Test fun amountPlain() = assertEquals(254.0, parse("where did I spend 254").amount!!, 0.001)
    @Test fun amountRupeeSymbol() = assertEquals(254.0, parse("spent ₹254 somewhere").amount!!, 0.001)
    @Test fun amountRsPrefix() = assertEquals(254.0, parse("paid rs 254 yesterday").amount!!, 0.001)
    @Test fun amountRsDotPrefix() = assertEquals(254.0, parse("paid Rs. 254").amount!!, 0.001)
    @Test fun amountRsSuffix() = assertEquals(254.0, parse("where did I spend 254 rs").amount!!, 0.001)
    @Test fun amountCommasDecimals() = assertEquals(1250.5, parse("did I pay 1,250.50 anywhere").amount!!, 0.001)
    @Test fun amountIndianGrouping() = assertEquals(125000.0, parse("transfer of ₹1,25,000").amount!!, 0.001)
    @Test fun amountKSuffix() = assertEquals(5000.0, parse("payment of 5k").amount!!, 0.001)
    @Test fun amountLakh() = assertEquals(200000.0, parse("sent 2 lakh").amount!!, 0.001)
    @Test fun amountIgnoresTopN() = assertNull(parse("top 5 expenses this month").amount)
    @Test fun amountIgnoresLastNDays() = assertNull(parse("how much did I spend in the last 7 days").amount)
    @Test fun amountNotConfusedWithDate() {
        val q = parse("where did I spend 254 rs on 12th October")
        assertEquals(254.0, q.amount!!, 0.001)
        assertEquals(d(2026, 10, 12), q.range!!.from)
    }

    // ---- dates ----
    @Test fun dateDayMonthShort() = assertDay("spent on 12 oct", d(2026, 10, 12))
    @Test fun dateOrdinalFullMonth() = assertDay("12th October", d(2026, 10, 12))
    @Test fun dateMonthDay() = assertDay("on october 12th", d(2026, 10, 12))
    @Test fun dateSlash() = assertDay("on 5/10", d(2026, 10, 5))
    @Test fun dateSlashWithYear() = assertDay("on 05/10/2025", d(2025, 10, 5))
    @Test fun dateIso() = assertDay("on 2026-09-30", d(2026, 9, 30))
    @Test fun dateFutureDayMonthGoesToLastYear() = assertDay("25 dec", d(2025, 12, 25))
    @Test fun dateYesterday() = assertDay("what did I buy yesterday", d(2026, 10, 21))
    @Test fun dateToday() = assertDay("show today", d(2026, 10, 22))
    @Test fun dateDayBeforeYesterday() = assertDay("day before yesterday", d(2026, 10, 20))
    @Test fun dateLastMonday() = assertDay("last monday", d(2026, 10, 19))
    @Test fun dateLastThursdayIsAWeekAgo() = assertDay("last thursday", d(2026, 10, 15))
    @Test fun dateOnFridayIsMostRecent() = assertDay("on friday", d(2026, 10, 16))

    @Test fun rangeThisWeek() {
        val r = parse("how much this week").range!!
        assertEquals(d(2026, 10, 19), r.from); assertEquals(d(2026, 10, 23), r.toExclusive)
    }
    @Test fun rangeLastWeek() {
        val r = parse("food last week").range!!
        assertEquals(d(2026, 10, 12), r.from); assertEquals(d(2026, 10, 19), r.toExclusive)
    }
    @Test fun rangeThisMonth() {
        val r = parse("spend this month").range!!
        assertEquals(d(2026, 10, 1), r.from); assertEquals(d(2026, 10, 23), r.toExclusive)
    }
    @Test fun rangeLastMonth() {
        val r = parse("last month").range!!
        assertEquals(d(2026, 9, 1), r.from); assertEquals(d(2026, 10, 1), r.toExclusive)
    }
    @Test fun rangeLastNDays() {
        val r = parse("in the last 7 days").range!!
        assertEquals(d(2026, 10, 16), r.from); assertEquals(d(2026, 10, 23), r.toExclusive)
    }
    @Test fun rangeMonthOnly() {
        val r = parse("spending in september").range!!
        assertEquals(d(2026, 9, 1), r.from); assertEquals(d(2026, 10, 1), r.toExclusive)
    }
    @Test fun mayAsVerbIsNotAMonth() = assertNull(parse("what may I have spent").range)
    @Test fun noDate() = assertNull(parse("where did I spend 254").range)

    // ---- intent / category / type ----
    @Test fun intentTotal() = assertEquals(QueryIntent.TOTAL, parse("how much did I spend today").intent)
    @Test fun intentFindWithAmount() = assertEquals(QueryIntent.FIND, parse("where did I spend 254 rs on 12th October").intent)
    @Test fun intentBiggest() = assertEquals(QueryIntent.BIGGEST, parse("what's my biggest expense this month").intent)
    @Test fun intentSplits() = assertEquals(QueryIntent.SPLITS, parse("how much do people owe me from splits").intent)
    @Test fun intentInvestments() = assertEquals(QueryIntent.INVESTMENTS, parse("what's my total invested in mutual funds").intent)
    @Test fun intentUnknown() = assertEquals(QueryIntent.UNKNOWN, parse("hello there").intent)
    @Test fun categoryFood() = assertEquals("food", parse("how much did I spend on food last week").categoryKey)
    @Test fun categoryFromMerchantWord() = assertEquals("food", parse("how much on swiggy").categoryKey)
    @Test fun textTermFromOn() = assertEquals("rahul", parse("how much did I pay to rahul").textTerm)
    @Test fun typeCredit() = assertEquals(TxnType.CREDIT, parse("how much salary did I receive this month").type)

    // ---- paid for others ----
    @Test fun paidForOthersIntent() {
        for (q in listOf(
            "how much did I pay for others", "who owes me", "who owes me money", "paid for someone else this month",
            "kisko paise diye", "kiske liye kharcha kiya", "how much to collect from friends for them", "on behalf of rahul",
        )) assertEquals(QueryIntent.PAID_FOR, parse(q).intent, q)
    }
    @Test fun splitQuestionsStayAsSplits() = assertEquals(QueryIntent.SPLITS, parse("what are my splits").intent)
    @Test fun ordinarySpendingIsNotPaidFor() = assertEquals(QueryIntent.TOTAL, parse("how much did I spend on food this month").intent)
}
