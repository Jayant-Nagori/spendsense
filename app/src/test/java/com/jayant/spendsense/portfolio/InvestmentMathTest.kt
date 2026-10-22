package com.jayant.spendsense.portfolio

import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class InvestmentMathTest {
    private val zone = ZoneId.systemDefault()
    private fun ms(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun indianGrouping() {
        assertEquals("₹0", formatInr(0.0))
        assertEquals("₹999", formatInr(999.0))
        assertEquals("₹1,000", formatInr(1000.0))
        assertEquals("₹1,23,456", formatInr(123456.0))
        assertEquals("₹12,34,567", formatInr(1234567.0))
        assertEquals("₹1,23,45,678", formatInr(12345678.0))
        assertEquals("-₹1,234", formatInr(-1234.0))
        assertEquals("₹1,234.50", formatInr(1234.5, decimals = 2))
        assertEquals("1,00,000", formatInr(100000.0, symbol = false))
        assertEquals("₹0", formatInr(-0.2))
    }

    @Test fun compactFormat() {
        assertEquals("₹12,500", formatInrCompact(12500.0))
        assertEquals("₹1.25 L", formatInrCompact(125000.0))
        assertEquals("₹2 L", formatInrCompact(200000.0))
        assertEquals("₹3.4 Cr", formatInrCompact(34000000.0))
    }

    @Test fun parsing() {
        assertEquals(123456.5, parseAmount("1,23,456.50")!!, 0.0001)
        assertEquals(5000.0, parseAmount("₹ 5000")!!, 0.0001)
        assertNull(parseAmount(""))
        assertNull(parseAmount("abc"))
    }

    @Test fun pct() {
        assertEquals("+12.3%", formatPct(12.34))
        assertEquals("-4.0%", formatPct(-4.0))
        assertEquals("0.0%", formatPct(0.0))
    }

    @Test fun fdOneYearQuarterly() {
        // 1,00,000 @ 8% quarterly for 365 days = 1,00,000 * (1.02)^4
        val v = fdValue(100000.0, 8.0, ms(2025, 1, 1), ms(2026, 1, 1))
        assertEquals(108243.22, v, 0.5)
    }

    @Test fun fdNoGrowthBeforeStartOrZeroRate() {
        assertEquals(1000.0, fdValue(1000.0, 7.0, ms(2026, 1, 1), ms(2025, 1, 1)), 1e-9)
        assertEquals(1000.0, fdValue(1000.0, 0.0, ms(2025, 1, 1), ms(2026, 1, 1)), 1e-9)
    }

    @Test fun estimateStopsAtMaturity() {
        val start = ms(2024, 1, 1)
        val mat = ms(2025, 1, 1)
        val atMat = estimateCurrentValue(AssetType.FIXED_DEPOSIT, 100000.0, 7.0, start, mat, null, mat)!!
        val later = estimateCurrentValue(AssetType.FIXED_DEPOSIT, 100000.0, 7.0, start, mat, null, ms(2026, 6, 1))!!
        assertEquals(atMat, later, 1e-6)
        assertTrue(atMat > 100000.0)
    }

    @Test fun estimateNullWhenInsufficient() {
        assertNull(estimateCurrentValue(AssetType.FIXED_DEPOSIT, 1000.0, null, 1L, null, null, 5L))
        assertNull(estimateCurrentValue(AssetType.FIXED_DEPOSIT, 1000.0, 7.0, null, null, null, 5L))
        assertNull(estimateCurrentValue(AssetType.STOCK, 1000.0, 7.0, 1L, null, null, 5L))
    }

    @Test fun rdAccumulates() {
        val start = ms(2025, 1, 1)
        val now = ms(2025, 12, 31)
        val v = estimateCurrentValue(AssetType.RECURRING_DEPOSIT, 12000.0, 6.0, start, ms(2026, 1, 1), 1000.0, now)!!
        // 12 instalments of 1000 with a little interest on top
        assertTrue(v > 12000.0 && v < 12500.0)
    }

    @Test fun rdZeroRateEqualsDeposits() {
        val v = rdValue(500.0, 0.0, ms(2025, 1, 1), ms(2025, 6, 15), maxInstallments = null)
        assertEquals(500.0 * 6, v, 1e-6)
    }

    @Test fun bondSimpleInterest() {
        val v = estimateCurrentValue(AssetType.BOND, 100000.0, 9.0, ms(2025, 1, 1), null, null, ms(2026, 1, 1))!!
        assertEquals(109000.0, v, 1.0)
    }

    @Test fun maturityLabels() {
        assertEquals("Matures today", maturityLabel(0))
        assertEquals("Matures tomorrow", maturityLabel(1))
        assertEquals("Matures in 45 days", maturityLabel(45))
        assertEquals("Matures in 6 months", maturityLabel(183))
        assertEquals("Matured yesterday", maturityLabel(-1))
        assertEquals("Matured 10 days ago", maturityLabel(-10))
    }

    @Test fun daysUntilCalendar() {
        assertEquals(10, daysUntil(ms(2026, 3, 11), ms(2026, 3, 1)))
        assertEquals(-2, daysUntil(ms(2026, 2, 27), ms(2026, 3, 1)))
    }

    @Test fun pickerRoundTrip() {
        val local = ms(2026, 5, 17)
        assertEquals(local, utcPickerToLocalMillis(localMillisToUtcPicker(local)))
    }

    @Test fun totalsAndAllocation() {
        val list = listOf(
            Investment(1, "A", AssetType.STOCK, null, 1000.0, 1500.0),
            Investment(2, "B", AssetType.MUTUAL_FUND, null, 2000.0, null),
            Investment(3, "C", AssetType.STOCK, null, 500.0, 400.0),
        )
        val t = totals(list)
        assertEquals(3500.0, t.invested, 1e-9)
        assertEquals(3900.0, t.current, 1e-9)
        assertEquals(400.0, t.gain, 1e-9)
        assertEquals(400.0 / 3500 * 100, t.gainPct, 1e-9)
        val a = allocation(list)
        assertEquals(2, a.size)
        assertEquals(1.0f, a.sumOf { it.fraction.toDouble() }.toFloat(), 1e-4f)
        assertEquals(AssetType.MUTUAL_FUND, a[0].type) // 2000 vs stocks 1900
    }

    @Test fun allocationFoldsTail() {
        val list = AssetType.values().take(9).mapIndexed { i, t -> Investment(i + 1L, "x$i", t, null, 100.0 + i) }
        val a = allocation(list, maxSlices = 6)
        assertEquals(7, a.size)
        assertEquals("Others", a.last().label)
    }

    @Test fun emptyTotals() {
        val t = totals(emptyList())
        assertEquals(0.0, t.gainPct, 0.0)
        assertTrue(allocation(emptyList()).isEmpty())
    }

    @Test fun upcomingMaturitiesWindow() {
        val now = ms(2026, 1, 1)
        fun fd(id: Long, mat: Long?) = Investment(id, "fd$id", AssetType.FIXED_DEPOSIT, null, 1.0, maturityDate = mat)
        val r = upcomingMaturities(
            listOf(fd(1, ms(2026, 2, 1)), fd(2, ms(2027, 6, 1)), fd(3, ms(2025, 12, 20)), fd(4, ms(2025, 6, 1)), fd(5, null)),
            now,
        )
        assertEquals(listOf(3L, 1L), r.map { it.first.id })
    }

    @Test fun assetGuess() {
        assertEquals(AssetType.STOCK, guessAssetType("Zerodha Broking"))
        assertEquals(AssetType.MUTUAL_FUND, guessAssetType("GROWW"))
        assertEquals(AssetType.OTHER, guessAssetType(null))
    }

    @Test fun recurringAndTracked() {
        val a = PaymentInfo(1, "Groww", 5000.0, 1L)
        val b = PaymentInfo(2, "groww", 5000.0, 2L)
        val c = PaymentInfo(3, "Zerodha", 5000.0, 3L)
        assertTrue(looksRecurring(a, listOf(a, b, c)))
        assertFalse(looksRecurring(c, listOf(a, b, c)))

        val sipHolding = Investment(9, "Groww SIP", AssetType.MUTUAL_FUND, "Groww", 5000.0, isSip = true, sipAmount = 5000.0)
        assertTrue(isPaymentTracked(a, listOf(sipHolding)))
        assertFalse(isPaymentTracked(c, listOf(sipHolding)))
        val fromTxn = prefillFromPayment(c, false, "1 Jan 2026")
        assertTrue(isPaymentTracked(c, listOf(fromTxn)))
        assertFalse(isPaymentTracked(a, listOf(fromTxn)))
    }

    @Test fun sipTotalAndTenure() {
        val list = listOf(
            Investment(1, "a", AssetType.MUTUAL_FUND, null, 1.0, isSip = true, sipAmount = 5000.0),
            Investment(2, "b", AssetType.MUTUAL_FUND, null, 1.0, isSip = true, sipAmount = null),
            Investment(3, "c", AssetType.STOCK, null, 1.0, isSip = false, sipAmount = 999.0),
        )
        assertEquals(5000.0, monthlySipTotal(list), 0.0)
        assertEquals(0.5f, tenureProgress(0L, 100L, 50L)!!, 1e-6f)
        assertEquals(1f, tenureProgress(0L, 100L, 500L)!!, 0f)
        assertEquals(0f, tenureProgress(10L, 100L, 5L)!!, 0f)
        assertNull(tenureProgress(null, 100L, 5L))
        assertNull(tenureProgress(100L, 100L, 5L))
    }

    @Test fun maturityShortLabels() {
        assertEquals("Matured", maturityShort(-3))
        assertEquals("Today", maturityShort(0))
        assertEquals("45 days", maturityShort(45))
        assertEquals("6 months", maturityShort(183))
    }
}
