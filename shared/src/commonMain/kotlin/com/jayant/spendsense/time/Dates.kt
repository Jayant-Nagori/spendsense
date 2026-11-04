package com.jayant.spendsense.time

import com.jayant.spendsense.currentTimeMillis
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Small java.time-flavoured helpers over kotlinx-datetime, so the date logic that used java.time reads the same
 * in common code. All "zone" parameters are kotlinx [TimeZone]s.
 */
object Zones {
    val IST: TimeZone = TimeZone.of("Asia/Kolkata")
    fun system(): TimeZone = TimeZone.currentSystemDefault()
}

/** A date-time in a zone (the parts of java.time.ZonedDateTime the app uses). */
data class ZonedDateTime(val dateTime: LocalDateTime, val zone: TimeZone) {
    fun toLocalDate(): LocalDate = dateTime.date
    fun toEpochMilli(): Long = dateTime.toInstant(zone).toEpochMilliseconds()
    val year: Int get() = dateTime.year
    val hour: Int get() = dateTime.hour
    val minute: Int get() = dateTime.minute
    val dayOfWeek: DayOfWeek get() = dateTime.dayOfWeek

    companion object {
        fun now(zone: TimeZone): ZonedDateTime = ofEpochMilli(currentTimeMillis(), zone)
        fun ofEpochMilli(ms: Long, zone: TimeZone): ZonedDateTime =
            ZonedDateTime(Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone), zone)
        fun of(y: Int, m: Int, d: Int, h: Int, min: Int, zone: TimeZone): ZonedDateTime =
            ZonedDateTime(LocalDateTime(y, m, d, h, min), zone)
    }
}

fun localDateOf(millis: Long, zone: TimeZone): LocalDate = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone).date
fun localDateTimeOf(millis: Long, zone: TimeZone): LocalDateTime = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)

fun LocalDate.atStartOfDayMillis(zone: TimeZone): Long = atStartOfDayIn(zone).toEpochMilliseconds()
fun LocalDate.atTimeMillis(time: LocalTime, zone: TimeZone): Long = atTime(time).toInstant(zone).toEpochMilliseconds()
fun LocalDateTime.toEpochMillis(zone: TimeZone): Long = toInstant(zone).toEpochMilliseconds()

fun LocalDate.plusDays(n: Long): LocalDate = plus(n, DateTimeUnit.DAY)
fun LocalDate.plusDays(n: Int): LocalDate = plus(n, DateTimeUnit.DAY)
fun LocalDate.minusDays(n: Long): LocalDate = minus(n, DateTimeUnit.DAY)
fun LocalDate.minusDays(n: Int): LocalDate = minus(n, DateTimeUnit.DAY)
fun LocalDate.plusWeeks(n: Long): LocalDate = plus(n * 7, DateTimeUnit.DAY)
fun LocalDate.minusWeeks(n: Long): LocalDate = minus(n * 7, DateTimeUnit.DAY)
fun LocalDate.minusWeeks(n: Int): LocalDate = minusWeeks(n.toLong())
fun LocalDate.plusMonths(n: Long): LocalDate = plus(n, DateTimeUnit.MONTH)
fun LocalDate.plusMonths(n: Int): LocalDate = plus(n, DateTimeUnit.MONTH)
fun LocalDate.minusMonths(n: Long): LocalDate = minus(n, DateTimeUnit.MONTH)
fun LocalDate.minusMonths(n: Int): LocalDate = minus(n, DateTimeUnit.MONTH)
fun LocalDate.plusYears(n: Int): LocalDate = plus(n, DateTimeUnit.YEAR)
fun LocalDate.minusYears(n: Int): LocalDate = minus(n, DateTimeUnit.YEAR)
fun LocalDate.minusYears(n: Long): LocalDate = minus(n, DateTimeUnit.YEAR)
fun LocalDate.withDayOfMonth(d: Int): LocalDate = LocalDate(year, month, d)
fun LocalDate.withDayOfYear(d: Int): LocalDate = LocalDate(year, 1, 1).plusDays(d - 1)
fun LocalDate.isAfter(other: LocalDate): Boolean = this > other
fun LocalDate.isBefore(other: LocalDate): Boolean = this < other
val LocalDate.monthValue: Int get() = month.number
val LocalDate.dayOfMonth: Int get() = day
fun LocalDate.lengthOfMonth(): Int = LocalDate(year, month, 1).plusMonths(1).minusDays(1).day
/** java.time `date.with(DayOfWeek.X)`: that weekday in the same Monday-based week. */
fun LocalDate.withDayOfWeek(dow: DayOfWeek): LocalDate = plusDays(dow.isoDayNumber - dayOfWeek.isoDayNumber)
fun LocalDate.daysUntil(other: LocalDate): Long = (other.toEpochDays() - toEpochDays())
fun safeLocalDate(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate(y, m, d) }.getOrNull()

private val MONTH_SHORT = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTH_FULL = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
private val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DAY_FULL = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

fun Month.shortName(): String = MONTH_SHORT[number - 1]
fun Month.fullName(): String = MONTH_FULL[number - 1]
fun DayOfWeek.shortName(): String = DAY_SHORT[isoDayNumber - 1]
fun DayOfWeek.fullName(): String = DAY_FULL[isoDayNumber - 1]

/**
 * English date formatting with a SimpleDateFormat-style pattern. Supports y, M, d, E, H, h, m, s, a and
 * literal text (quotes are not needed for the patterns the app uses: spaces, commas, ':', '-', '/').
 */
fun formatPattern(dt: LocalDateTime, pattern: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        var n = 1
        while (i + n < pattern.length && pattern[i + n] == c) n++
        when (c) {
            'y' -> sb.append(if (n == 2) (dt.year % 100).toString().padStart(2, '0') else dt.year.toString().padStart(n, '0'))
            'M' -> sb.append(
                when {
                    n >= 4 -> dt.month.fullName()
                    n == 3 -> dt.month.shortName()
                    else -> dt.month.number.toString().padStart(n, '0')
                },
            )
            'd' -> sb.append(dt.day.toString().padStart(n, '0'))
            'E' -> sb.append(if (n >= 4) dt.dayOfWeek.fullName() else dt.dayOfWeek.shortName())
            'H' -> sb.append(dt.hour.toString().padStart(n, '0'))
            'h' -> sb.append((if (dt.hour % 12 == 0) 12 else dt.hour % 12).toString().padStart(n, '0'))
            'm' -> sb.append(dt.minute.toString().padStart(n, '0'))
            's' -> sb.append(dt.second.toString().padStart(n, '0'))
            'a' -> sb.append(if (dt.hour < 12) "AM" else "PM")
            else -> repeat(n) { sb.append(c) }
        }
        i += n
    }
    return sb.toString()
}

fun formatPattern(millis: Long, zone: TimeZone, pattern: String): String = formatPattern(localDateTimeOf(millis, zone), pattern)
fun formatPattern(date: LocalDate, pattern: String): String = formatPattern(date.atTime(0, 0), pattern)
fun ZonedDateTime.format(pattern: String): String = formatPattern(dateTime, pattern)

/** Parses "2026-10-22" strictly; null when malformed or not a real date. */
fun parseIsoDate(s: String): LocalDate? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(s.trim()) ?: return null
    return safeLocalDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
}

val LocalTimeNoon: LocalTime = LocalTime(12, 0)

/** For DatePeriod-based arithmetic callers. */
fun days(n: Int) = DatePeriod(days = n)

/** java.time LocalDate.toEpochDay(). */
fun LocalDate.toEpochDay(): Long = toEpochDays().toLong()
