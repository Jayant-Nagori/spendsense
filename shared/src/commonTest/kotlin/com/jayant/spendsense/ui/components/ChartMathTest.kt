package com.jayant.spendsense.ui.components

import com.jayant.spendsense.data.ChartType
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.data.withPaidFor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test
import com.jayant.spendsense.time.toEpochMillis
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class ChartMathTest {
    private val zone = TimeZone.of("Asia/Kolkata")
    private fun ms(m: Int, d: Int, h: Int = 12) = LocalDateTime(2026, m, d, h, 0).toEpochMillis(zone)
    private fun tx(amount: Double, at: Long, type: TxnType = TxnType.DEBIT) = Transaction(
        amount = amount, type = type, counterparty = null, account = null, bank = null, upiRef = null,
        source = TxnSource.SMS, sourceApp = null, rawText = "", timestamp = at,
    )

    @Test fun dailySpendStopsAtTodayForTheCurrentMonth() {
        val d = dailySpend(listOf(tx(100.0, ms(10, 2)), tx(50.0, ms(10, 2, 20)), tx(30.0, ms(10, 5))), ms(10, 1, 0), ms(10, 6), zone)
        assertEquals(listOf(0.0, 150.0, 0.0, 0.0, 30.0, 0.0), d)
    }

    @Test fun dailySpendCoversWholePastMonthAndIgnoresCreditsAndOtherMonths() {
        val d = dailySpend(
            listOf(tx(100.0, ms(9, 30)), tx(999.0, ms(9, 3), TxnType.CREDIT), tx(7.0, ms(10, 1))), ms(9, 1, 0), ms(10, 24), zone,
        )
        assertEquals(30, d.size)
        assertEquals(100.0, d[29], 0.0)
        assertEquals(100.0, d.sum(), 0.0)
    }

    @Test fun dailySpendUsesMyShareSoPaidForOthersIsZero() {
        val d = dailySpend(listOf(tx(500.0, ms(10, 2)).withPaidFor("Rahul"), tx(80.0, ms(10, 2))), ms(10, 1, 0), ms(10, 3), zone)
        assertEquals(80.0, d[1], 0.0)
    }

    @Test fun donutHitQuadrantsAndBounds() {
        val v = listOf(25.0, 25.0, 25.0, 25.0)
        assertEquals(0, donutHit(50f, -50f, v, 10f, 100f))  // top right
        assertEquals(1, donutHit(50f, 50f, v, 10f, 100f))   // bottom right
        assertEquals(2, donutHit(-50f, 50f, v, 10f, 100f))  // bottom left
        assertEquals(3, donutHit(-50f, -50f, v, 10f, 100f)) // top left
        assertNull(donutHit(1f, 1f, v, 10f, 100f))           // in the hole
        assertNull(donutHit(200f, 0f, v, 10f, 100f))         // outside
        assertNull(donutHit(50f, 0f, emptyList(), 10f, 100f))
    }

    @Test fun slotAtClampsAndHandlesEmpty() {
        assertEquals(0, slotAt(-5f, 100f, 4))
        assertEquals(1, slotAt(30f, 100f, 4))
        assertEquals(3, slotAt(500f, 100f, 4))
        assertNull(slotAt(10f, 100f, 0))
    }

    @Test fun chartTypeParsingDefaultsToDonut() {
        assertEquals(ChartType.LINE, ChartType.fromName("LINE"))
        assertEquals(ChartType.DONUT, ChartType.fromName(null))
        assertEquals(ChartType.DONUT, ChartType.fromName("pie"))
        assertEquals(listOf("Donut", "Bar", "Line"), ChartType.entries.map { it.label })
    }
}
