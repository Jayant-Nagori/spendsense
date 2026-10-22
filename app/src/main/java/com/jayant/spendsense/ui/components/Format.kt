package com.jayant.spendsense.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    fun trim(v: Double): String = if (v >= 100 || floor(v) == v) v.roundToLong().toString() else String.format(Locale.US, "%.1f", v).removeSuffix(".0")
    return sign + "₹" + when {
        a >= 1e7 -> trim(a / 1e7) + "Cr"
        a >= 1e5 -> trim(a / 1e5) + "L"
        a >= 1e4 -> trim(a / 1e3) + "K"
        else -> groupIndian(a.roundToLong())
    }
}

private val dayFmt = SimpleDateFormat("EEE, d MMM", Locale.ENGLISH)
private val dayFmtYear = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
private val dateTimeFmt = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)
private val dateTimeYearFmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH)
private val timeFmt = SimpleDateFormat("h:mm a", Locale.ENGLISH)
private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.ENGLISH)

fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

fun startOfMonth(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = startOfDay(millis)
    set(Calendar.DAY_OF_MONTH, 1)
}.timeInMillis

fun addMonths(startOfMonth: Long, delta: Int): Long = Calendar.getInstance().apply {
    timeInMillis = startOfMonth
    add(Calendar.MONTH, delta)
}.timeInMillis

private fun sameYear(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
}

fun formatMonth(millis: Long): String = monthFmt.format(Date(millis))
fun formatTime(millis: Long): String = timeFmt.format(Date(millis)).lowercase(Locale.ENGLISH)

/** "12 Oct, 9:41 pm" (year added when not the current year). */
fun formatDateTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val f = if (sameYear(millis, now)) dateTimeFmt else dateTimeYearFmt
    return f.format(Date(millis)).replace("AM", "am").replace("PM", "pm")
}

/** "Today", "Yesterday", "Mon, 12 Oct". */
fun formatDayHeader(millis: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    val day = startOfDay(millis)
    val diffDays = Math.round((today - day) / 86_400_000.0).toInt()
    return when (diffDays) {
        0 -> "Today"
        1 -> "Yesterday"
        else -> (if (sameYear(millis, today)) dayFmt else dayFmtYear).format(Date(millis))
    }
}

fun daysInMonth(startOfMonth: Long): Int = Calendar.getInstance().apply { timeInMillis = startOfMonth }.getActualMaximum(Calendar.DAY_OF_MONTH)
fun dayOfMonth(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)
