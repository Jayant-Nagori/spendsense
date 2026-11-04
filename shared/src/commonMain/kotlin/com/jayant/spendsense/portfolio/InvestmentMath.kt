package com.jayant.spendsense.portfolio

import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.decimalPlain
import com.jayant.spendsense.formatFixed
import com.jayant.spendsense.time.atStartOfDayMillis
import com.jayant.spendsense.time.formatPattern
import com.jayant.spendsense.time.localDateOf
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// Pure Kotlin (common): unit-tested on the JVM and on iOS.

private const val DAYS_PER_YEAR = 365.0
private const val DAYS_PER_MONTH = 30.4375
private val DAY_MS = 24 * 60 * 60 * 1000L

/** Quarterly-compounded value of [principal] between [startMs] and [endMs] at [ratePct] % p.a. */
fun fdValue(principal: Double, ratePct: Double, startMs: Long, endMs: Long): Double {
    if (endMs <= startMs || principal <= 0.0) return principal
    val years = (endMs - startMs) / DAY_MS / DAYS_PER_YEAR
    return principal * (1 + ratePct / 100.0 / 4.0).pow(4.0 * years)
}

/**
 * Recurring-deposit value: monthly [installment]s paid from [startMs], each compounding quarterly,
 * valued at [endMs]. [maxInstallments] caps the number of deposits (tenure in months).
 */
fun rdValue(installment: Double, ratePct: Double, startMs: Long, endMs: Long, maxInstallments: Int? = null): Double {
    if (installment <= 0.0 || endMs < startMs) return 0.0
    val elapsedMonths = (endMs - startMs) / DAY_MS / DAYS_PER_MONTH
    var count = floor(elapsedMonths).toInt() + 1
    if (maxInstallments != null) count = min(count, maxInstallments)
    count = max(count, 1)
    var total = 0.0
    for (i in 0 until count) {
        val yearsGrown = max(0.0, (elapsedMonths - i) / 12.0)
        total += installment * (1 + ratePct / 100.0 / 4.0).pow(4.0 * yearsGrown)
    }
    return total
}

/** Number of RD installments that have been paid by [nowMs] (capped by tenure when known). */
fun rdInstallmentsPaid(startMs: Long, nowMs: Long, maturityMs: Long?): Int {
    val elapsedMonths = max(0.0, (nowMs - startMs) / DAY_MS / DAYS_PER_MONTH)
    var count = floor(elapsedMonths).toInt() + 1
    if (maturityMs != null && maturityMs > startMs) {
        val tenure = ((maturityMs - startMs) / DAY_MS / DAYS_PER_MONTH).roundToInt()
        if (tenure > 0) count = min(count, tenure)
    }
    return max(count, 1)
}

/**
 * Best-effort current value for interest-bearing instruments, or null when it can't be estimated
 * (wrong type, or missing rate / start date). Accrual stops at maturity.
 *  - FD: quarterly compounding on the principal.
 *  - RD: monthly instalments (sipAmount, else invested / instalments paid) compounding quarterly.
 *  - Bond: simple interest.
 */
fun estimateCurrentValue(
    type: AssetType,
    invested: Double,
    ratePct: Double?,
    startMs: Long?,
    maturityMs: Long?,
    sipAmount: Double?,
    nowMs: Long,
): Double? {
    if (ratePct == null || startMs == null || invested <= 0.0) return null
    val end = if (maturityMs != null) min(nowMs, maturityMs) else nowMs
    return when (type) {
        AssetType.FIXED_DEPOSIT -> fdValue(invested, ratePct, startMs, end)
        AssetType.RECURRING_DEPOSIT -> {
            val paid = rdInstallmentsPaid(startMs, end, maturityMs)
            val tenure = maturityMs?.takeIf { it > startMs }
                ?.let { ((it - startMs) / DAY_MS / DAYS_PER_MONTH).roundToInt() }
            val inst = sipAmount?.takeIf { it > 0 } ?: (invested / paid)
            rdValue(inst, ratePct, startMs, end, tenure)
        }
        AssetType.BOND -> {
            if (end <= startMs) invested
            else invested * (1 + ratePct / 100.0 * ((end - startMs) / DAY_MS / DAYS_PER_YEAR))
        }
        else -> null
    }
}

/** Value at maturity (FD only needs principal, rate and both dates). */
fun fdMaturityValue(principal: Double, ratePct: Double, startMs: Long, maturityMs: Long): Double =
    fdValue(principal, ratePct, startMs, maturityMs)

// ---------- dates ----------

/** Whole calendar days from today to [targetMs] (negative = in the past). */
fun daysUntil(targetMs: Long, nowMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long {
    val today = localDateOf(nowMs, zone)
    val target = localDateOf(targetMs, zone)
    return target.toEpochDays() - today.toEpochDays()
}

fun maturityLabel(days: Long): String = when {
    days < -1 -> "Matured ${-days} days ago"
    days == -1L -> "Matured yesterday"
    days == 0L -> "Matures today"
    days == 1L -> "Matures tomorrow"
    days < 60 -> "Matures in $days days"
    days < 730 -> "Matures in ${(days / DAYS_PER_MONTH).roundToInt()} months"
    else -> "Matures in ${formatFixed(days / DAYS_PER_YEAR, 1)} years"
}

/** Short form for big type: "Today", "45 days", "6 months", "2.1 years", "Matured". */
fun maturityShort(days: Long): String = when {
    days < 0 -> "Matured"
    days == 0L -> "Today"
    days == 1L -> "Tomorrow"
    days < 60 -> "$days days"
    days < 730 -> "${(days / DAYS_PER_MONTH).roundToInt()} months"
    else -> "${formatFixed(days / DAYS_PER_YEAR, 1)} years"
}

fun formatDate(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String =
    formatPattern(ms, zone, "d MMM yyyy")

/** The Material3 date picker works in UTC midnight; convert to local-midnight millis. */
fun utcPickerToLocalMillis(utcMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
    localDateOf(utcMs, TimeZone.UTC).atStartOfDayMillis(zone)

fun localMillisToUtcPicker(localMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
    localDateOf(localMs, zone).atStartOfDayMillis(TimeZone.UTC)

fun startOfDay(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long =
    localDateOf(ms, zone).atStartOfDayMillis(zone)

// ---------- money formatting ----------

/** Indian digit grouping: 1234567.5 -> "₹12,34,568" (decimals configurable). */
fun formatInr(amount: Double, decimals: Int = 0, symbol: Boolean = true): String {
    val plain = decimalPlain(abs(amount), decimals, stripTrailingZeros = false)
    val intPart = plain.substringBefore('.')
    val frac = if (plain.contains('.')) plain.substringAfter('.') else ""
    val grouped = groupIndian(intPart)
    val neg = amount < 0 && plain.any { it in '1'..'9' }
    return buildString {
        if (neg) append('-')
        if (symbol) append('₹')
        append(grouped)
        if (frac.isNotEmpty()) append('.').append(frac)
    }
}

internal fun groupIndian(digits: String): String {
    if (digits.length <= 3) return digits
    val last3 = digits.takeLast(3)
    var rest = digits.dropLast(3)
    val parts = ArrayList<String>()
    while (rest.length > 2) {
        parts.add(0, rest.takeLast(2))
        rest = rest.dropLast(2)
    }
    if (rest.isNotEmpty()) parts.add(0, rest)
    return parts.joinToString(",") + "," + last3
}

/** "₹1.25 L", "₹3.4 Cr", "₹12,500" for chart legends and tight spaces. */
fun formatInrCompact(amount: Double): String {
    val a = abs(amount)
    val sign = if (amount < 0) "-" else ""
    return when {
        a >= 1_00_00_000 -> "$sign₹${trimZero(a / 1_00_00_000)} Cr"
        a >= 1_00_000 -> "$sign₹${trimZero(a / 1_00_000)} L"
        else -> formatInr(amount)
    }
}

private fun trimZero(v: Double): String {
    val s = formatFixed(v, 2)
    return s.trimEnd('0').trimEnd('.')
}

fun formatPct(pct: Double, signed: Boolean = true): String {
    val s = formatFixed(abs(pct), 1)
    val sign = when {
        !signed -> if (pct < 0) "-" else ""
        pct > 0.049 -> "+"
        pct < -0.049 -> "-"
        else -> ""
    }
    return "$sign$s%"
}

/** Parses user input like "1,23,456.50" or "₹ 5000"; null when blank or not a number. */
fun parseAmount(input: String): Double? {
    val cleaned = input.replace(",", "").replace("₹", "").trim()
    if (cleaned.isEmpty()) return null
    val v = cleaned.toDoubleOrNull() ?: return null
    return if (v.isNaN() || v.isInfinite()) null else v
}

/** Number -> editable text without a trailing ".0". */
fun plainNumber(v: Double): String {
    return decimalPlain(v, 4, stripTrailingZeros = true)
}
