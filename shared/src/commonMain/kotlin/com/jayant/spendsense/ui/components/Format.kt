package com.jayant.spendsense.ui.components

import com.jayant.spendsense.currentTimeMillis
import com.jayant.spendsense.formatFixed
import com.jayant.spendsense.time.atStartOfDayMillis
import com.jayant.spendsense.time.formatPattern
import com.jayant.spendsense.time.lengthOfMonth
import com.jayant.spendsense.time.localDateOf
import com.jayant.spendsense.time.localDateTimeOf
import com.jayant.spendsense.time.plusMonths
import com.jayant.spendsense.time.toEpochMillis
import com.jayant.spendsense.time.withDayOfMonth
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/** Indian digit grouping: 1234567 -> "12,34,567". */
fun groupIndian(n: Long): String {
    val neg = n < 0
    val s = abs(n).toString()
    val out = if (s.length <= 3) s else {
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        "$rest,$last3"
    }
    return if (neg) "-$out" else out
}

/** "1,23,456" or "1,23,456.50" (decimals only when the value has paise, or when [forceDecimals]). */
fun formatIndianNumber(amount: Double, forceDecimals: Boolean = false): String {
    val paise = (abs(amount) * 100).roundToLong()
    val whole = paise / 100
    val frac = (paise % 100).toInt()
    val sign = if (amount < 0 && paise > 0) "-" else ""
    val body = groupIndian(whole)
    return if (frac != 0 || forceDecimals) "$sign$body.${frac.toString().padStart(2, '0')}" else "$sign$body"
}

/** "₹1,23,456" with optional paise. */
fun formatRupees(amount: Double, forceDecimals: Boolean = false): String = "₹" + formatIndianNumber(amount, forceDecimals)

/** Whole rupees only, for hero numbers that count up. */
fun formatRupeesWhole(amount: Double): String = "₹" + groupIndian(amount.roundToLong())

/** Compact: ₹1.2Cr, ₹3.4L, ₹12.5K, ₹850. */
fun formatRupeesCompact(amount: Double): String {
    val a = abs(amount)
    val sign = if (amount < 0) "-" else ""
    fun trim(v: Double): String = if (v >= 100 || floor(v) == v) v.roundToLong().toString() else formatFixed(v, 1).removeSuffix(".0")
    return sign + "₹" + when {
        a >= 1e7 -> trim(a / 1e7) + "Cr"
        a >= 1e5 -> trim(a / 1e5) + "L"
        a >= 1e4 -> trim(a / 1e3) + "K"
        else -> groupIndian(a.roundToLong())
    }
}

private fun zone() = TimeZone.currentSystemDefault()

fun startOfDay(millis: Long): Long = localDateOf(millis, zone()).atStartOfDayMillis(zone())

fun startOfMonth(millis: Long): Long = localDateOf(millis, zone()).withDayOfMonth(1).atStartOfDayMillis(zone())

fun addMonths(startOfMonth: Long, delta: Int): Long {
    // Calendar.add(MONTH) semantics: same wall-clock time, day clamped to the target month.
    val dt = localDateTimeOf(startOfMonth, zone())
    return LocalDateTime(dt.date.plusMonths(delta), dt.time).toEpochMillis(zone())
}

private fun sameYear(a: Long, b: Long): Boolean = localDateOf(a, zone()).year == localDateOf(b, zone()).year

fun formatMonth(millis: Long): String = formatPattern(millis, zone(), "MMMM yyyy")
fun formatTime(millis: Long): String = formatPattern(millis, zone(), "h:mm a").lowercase()

/** "12 Oct, 9:41 pm" (year added when not the current year). */
fun formatDateTime(millis: Long): String {
    val now = currentTimeMillis()
    val f = if (sameYear(millis, now)) "d MMM, h:mm a" else "d MMM yyyy, h:mm a"
    return formatPattern(millis, zone(), f).replace("AM", "am").replace("PM", "pm")
}

/** "Today", "Yesterday", "Mon, 12 Oct". */
fun formatDayHeader(millis: Long): String {
    val today = startOfDay(currentTimeMillis())
    val day = startOfDay(millis)
    val diffDays = ((today - day) / 86_400_000.0).roundToInt()
    return when (diffDays) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> formatPattern(millis, zone(), if (sameYear(millis, today)) "EEE, d MMM" else "EEE, d MMM yyyy")
    }
}

fun daysInMonth(startOfMonth: Long): Int = localDateOf(startOfMonth, zone()).lengthOfMonth()
fun dayOfMonth(millis: Long): Int = localDateOf(millis, zone()).day
