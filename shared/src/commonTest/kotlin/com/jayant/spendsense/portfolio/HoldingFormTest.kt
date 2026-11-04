package com.jayant.spendsense.portfolio

import com.jayant.spendsense.data.AssetType
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import com.jayant.spendsense.time.atStartOfDayMillis
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class HoldingFormTest {
    private fun ms(y: Int, m: Int, d: Int) =
        LocalDate(y, m, d).atStartOfDayMillis(TimeZone.currentSystemDefault())
    private val now = ms(2026, 6, 1)

    private fun invalid(f: HoldingForm) = f.validate(now) as FormResult.Invalid
    private fun valid(f: HoldingForm) = (f.validate(now) as FormResult.Valid).investment

    @Test fun requiresNameAndAmount() {
        val e = invalid(HoldingForm()).errors
        assertTrue(FormField.NAME in e)
        assertTrue(FormField.INVESTED in e)
    }

    @Test fun rejectsBadNumbers() {
        val e = invalid(HoldingForm(name = "x", invested = "-5", current = "abc", units = "0")).errors
        assertTrue(FormField.INVESTED in e && FormField.CURRENT in e && FormField.UNITS in e)
    }

    @Test fun sipNeedsAmount() {
        assertTrue(FormField.SIP in invalid(HoldingForm(name = "x", invested = "100", isSip = true)).errors)
        val inv = valid(HoldingForm(name = "x", invested = "100", isSip = true, sipAmount = "1,000"))
        assertEquals(1000.0, inv.sipAmount!!, 0.0)
    }

    @Test fun maturityMustFollowStart() {
        val f = HoldingForm(
            type = AssetType.FIXED_DEPOSIT, name = "FD", invested = "1000",
            startDate = ms(2026, 1, 1), maturityDate = ms(2025, 1, 1),
        )
        assertTrue(FormField.DATES in invalid(f).errors)
    }

    @Test fun rateRange() {
        val f = HoldingForm(type = AssetType.FIXED_DEPOSIT, name = "FD", invested = "1000", rate = "150")
        assertTrue(FormField.RATE in invalid(f).errors)
    }

    @Test fun fdAutoEstimatesWhenCurrentBlank() {
        val f = HoldingForm(
            type = AssetType.FIXED_DEPOSIT, name = "FD", invested = "1,00,000", rate = "8",
            startDate = ms(2025, 6, 1), maturityDate = ms(2027, 6, 1),
        )
        val inv = valid(f)
        assertEquals(108243.0, inv.currentValue!!, 60.0)
    }

    @Test fun explicitCurrentValueWins() {
        val f = HoldingForm(
            type = AssetType.FIXED_DEPOSIT, name = "FD", invested = "100000", current = "105000", rate = "8",
            startDate = ms(2025, 6, 1),
        )
        assertEquals(105000.0, valid(f).currentValue!!, 0.0)
    }

    @Test fun nonMaturityTypeDropsRateAndDates() {
        val f = HoldingForm(
            type = AssetType.STOCK, name = "RELIANCE", invested = "1000", rate = "9",
            maturityDate = ms(2030, 1, 1),
        )
        val inv = valid(f)
        assertNull(inv.interestRate)
        assertNull(inv.maturityDate)
        assertNull(inv.currentValue)
    }

    @Test fun roundTripFromInvestment() {
        val inv = valid(HoldingForm(name = "  Fund ", invested = "2500.5", units = "10.25", platform = "Groww", notes = "n"))
        val back = HoldingForm.from(inv)
        assertEquals("2500.5", back.invested)
        assertEquals("10.25", back.units)
        assertEquals("Fund", back.name)
    }

    @Test fun updateValueValidation() {
        assertNull(validateNewValue("").first)
        assertNull(validateNewValue("x").first)
        assertEquals(1234.0, validateNewValue("1,234").first!!, 0.0)
    }
}
