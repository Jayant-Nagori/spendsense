package com.jayant.spendsense.ai

import com.jayant.spendsense.time.Zones
import com.jayant.spendsense.time.formatPattern
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong

/** Pure helpers shared by the tools, the offline answerer and the UI. No android.* here. */
object Money {
    /** 125000.5 -> "₹1,25,000.50"; whole rupees drop the decimals: "₹254". */
    fun inr(value: Double): String {
        val neg = value < 0
        val cents = (abs(value) * 100).roundToLong()
        val rupees = cents / 100
        val paise = (cents % 100).toInt()
        val grouped = groupIndian(rupees.toString())
        val tail = if (paise != 0) "." + paise.toString().padStart(2, '0') else ""
        return (if (neg) "-" else "") + "₹" + grouped + tail
    }

    /** "1234567" -> "12,34,567". */
    fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val parts = rest.reversed().chunked(2).map { it.reversed() }.reversed()
        return parts.joinToString(",") + "," + last3
    }
}

object Ist {
    val ZONE: TimeZone = Zones.IST

    fun row(millis: Long, zone: TimeZone = ZONE): String = formatPattern(millis, zone, "yyyy-MM-dd HH:mm EEE")
    fun human(millis: Long, zone: TimeZone = ZONE): String = formatPattern(millis, zone, "d MMM yyyy, h:mm a")
    fun card(millis: Long, zone: TimeZone = ZONE): String =
        formatPattern(millis, zone, "d MMM, h:mm a").replace("AM", "am").replace("PM", "pm")
}
