package com.jayant.spendsense.ai

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Pure helpers shared by the tools, the offline answerer and the UI. No android.* here. */
object Money {
    /** 125000.5 -> "₹1,25,000.50"; whole rupees drop the decimals: "₹254". */
    fun inr(value: Double): String {
        val neg = value < 0
        val cents = Math.round(abs(value) * 100)
        val rupees = cents / 100
        val paise = (cents % 100).toInt()
        val grouped = groupIndian(rupees.toString())
        val tail = if (paise != 0) String.format(Locale.US, ".%02d", paise) else ""
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
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    private val rowFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm EEE", Locale.ENGLISH)
    private val humanFmt = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)
    private val cardFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

    fun row(millis: Long, zone: ZoneId = ZONE): String = rowFmt.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun human(millis: Long, zone: ZoneId = ZONE): String = humanFmt.format(Instant.ofEpochMilli(millis).atZone(zone))
    fun card(millis: Long, zone: ZoneId = ZONE): String =
        cardFmt.format(Instant.ofEpochMilli(millis).atZone(zone)).replace("AM", "am").replace("PM", "pm")
}
