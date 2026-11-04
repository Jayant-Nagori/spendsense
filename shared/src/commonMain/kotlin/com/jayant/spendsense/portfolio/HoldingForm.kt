package com.jayant.spendsense.portfolio

import kotlin.math.roundToLong

import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment

// Pure Kotlin form model + validation for the add/edit holding sheet.

enum class FormField { NAME, INVESTED, CURRENT, UNITS, RATE, DATES, SIP }

sealed interface FormResult {
    data class Valid(val investment: Investment) : FormResult
    data class Invalid(val errors: Map<FormField, String>) : FormResult
}

data class HoldingForm(
    val id: Long = 0,
    val type: AssetType = AssetType.MUTUAL_FUND,
    val name: String = "",
    val platform: String = "",
    val invested: String = "",
    val current: String = "",
    val units: String = "",
    val startDate: Long? = null,
    val maturityDate: Long? = null,
    val rate: String = "",
    val isSip: Boolean = false,
    val sipAmount: String = "",
    val notes: String = "",
) {
    val showsMaturity: Boolean get() = type in MATURITY_TYPES

    /** Estimated current value for FD/RD/Bond given the entered fields, or null. */
    fun estimate(nowMs: Long): Double? {
        val inv = parseAmount(invested) ?: return null
        return estimateCurrentValue(
            type = type,
            invested = inv,
            ratePct = parseAmount(rate),
            startMs = startDate,
            maturityMs = maturityDate,
            sipAmount = parseAmount(sipAmount),
            nowMs = nowMs,
        )
    }

    fun validate(nowMs: Long): FormResult {
        val errors = linkedMapOf<FormField, String>()
        if (name.isBlank()) errors[FormField.NAME] = "Give this holding a name"

        val inv = parseAmount(invested)
        if (invested.isBlank()) errors[FormField.INVESTED] = "Enter the amount invested"
        else if (inv == null || inv <= 0.0) errors[FormField.INVESTED] = "Enter a valid amount greater than 0"

        var cur: Double? = null
        if (current.isNotBlank()) {
            cur = parseAmount(current)
            if (cur == null || cur < 0.0) errors[FormField.CURRENT] = "Enter a valid current value"
        }

        var unitsVal: Double? = null
        if (units.isNotBlank()) {
            unitsVal = parseAmount(units)
            if (unitsVal == null || unitsVal <= 0.0) errors[FormField.UNITS] = "Units must be greater than 0"
        }

        var rateVal: Double? = null
        if (showsMaturity && rate.isNotBlank()) {
            rateVal = parseAmount(rate)
            if (rateVal == null || rateVal < 0.0 || rateVal > 100.0) errors[FormField.RATE] = "Rate must be between 0 and 100"
        }

        val start = startDate
        val maturity = if (showsMaturity) maturityDate else null
        if (start != null && maturity != null && maturity <= start) {
            errors[FormField.DATES] = "Maturity must be after the start date"
        }

        var sipVal: Double? = null
        if (isSip) {
            sipVal = parseAmount(sipAmount)
            if (sipAmount.isBlank() || sipVal == null || sipVal <= 0.0) errors[FormField.SIP] = "Enter the SIP amount"
        }

        if (errors.isNotEmpty()) return FormResult.Invalid(errors)

        val estimated = if (cur == null) {
            estimateCurrentValue(type, inv!!, rateVal, start, maturity, sipVal, nowMs)
                ?.let { (it * 100).roundToLong() / 100.0 }
        } else null

        return FormResult.Valid(
            Investment(
                id = id,
                name = name.trim(),
                assetType = type,
                platform = platform.trim().ifEmpty { null },
                investedAmount = inv!!,
                currentValue = cur ?: estimated,
                units = unitsVal,
                startDate = start,
                maturityDate = maturity,
                interestRate = if (showsMaturity) rateVal else null,
                isSip = isSip,
                sipAmount = if (isSip) sipVal else null,
                notes = notes.trim().ifEmpty { null },
            ),
        )
    }

    companion object {
        fun from(inv: Investment) = HoldingForm(
            id = inv.id,
            type = inv.assetType,
            name = inv.name,
            platform = inv.platform.orEmpty(),
            invested = plainNumber(inv.investedAmount),
            current = inv.currentValue?.let { plainNumber(it) }.orEmpty(),
            units = inv.units?.let { plainNumber(it) }.orEmpty(),
            startDate = inv.startDate,
            maturityDate = inv.maturityDate,
            rate = inv.interestRate?.let { plainNumber(it) }.orEmpty(),
            isSip = inv.isSip,
            sipAmount = inv.sipAmount?.let { plainNumber(it) }.orEmpty(),
            notes = inv.notes.orEmpty(),
        )
    }
}

/** Validates a quick "update value" entry. Returns the new value or an error message. */
fun validateNewValue(input: String): Pair<Double?, String?> {
    if (input.isBlank()) return null to "Enter the current value"
    val v = parseAmount(input)
    return if (v == null || v < 0.0) null to "Enter a valid amount" else v to null
}
