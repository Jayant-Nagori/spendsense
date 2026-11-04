package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.time.LocalTimeNoon
import com.jayant.spendsense.time.Zones
import com.jayant.spendsense.time.atTimeMillis
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Date formats found on Indian statements: dd/MM/yy, dd-MM-yyyy, dd MMM yyyy, dd-MMM-yy, yyyy-MM-dd. */
object StatementDates {
    val IST: TimeZone = Zones.IST

    data class Match(val date: LocalDate, val time: LocalTime?, val start: Int, val end: Int)

    private const val MON = "jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec"
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val NUMERIC = "(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4}|\\d{2})(?!\\d)"
    private val TEXTUAL = "(\\d{1,2})[\\s\\-/.]*($MON)[a-z]*[\\s\\-/.,]*'?(\\d{4}|\\d{2})(?!\\d)"
    private val ISO = "(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)"
    private val TIME = "(?:\\s*(\\d{1,2}):(\\d{2})(?::\\d{2})?(?:\\s?([AaPp][Mm]))?)?"

    private val ANY = Regex("(?<![\\d/.\\-])(?:$ISO|$NUMERIC|$TEXTUAL)", RegexOption.IGNORE_CASE)
    private val LEADING = Regex("^\\s*(?:$ISO|$NUMERIC|$TEXTUAL)$TIME", RegexOption.IGNORE_CASE)

    private fun build(m: MatchResult): LocalDate? {
        val g = m.groupValues
        return try {
            when {
                g[1].isNotEmpty() -> LocalDate(g[1].toInt(), g[2].toInt(), g[3].toInt())
                g[4].isNotEmpty() -> LocalDate(year(g[6]), g[5].toInt(), g[4].toInt())
                else -> {
                    val mon = MONTHS.indexOf(g[8].lowercase().take(3)) + 1
                    if (mon == 0) null else LocalDate(year(g[9]), mon, g[7].toInt())
                }
            }
        } catch (_: Exception) {
            null
        }?.takeIf { it.year in 2000..2100 }
    }

    private fun year(s: String) = if (s.length == 2) 2000 + s.toInt() else s.toInt()

    private fun time(g: List<String>, base: Int): LocalTime? {
        val h = g.getOrNull(base)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: return null
        val min = g.getOrNull(base + 1)?.toIntOrNull() ?: return null
        val ampm = g.getOrNull(base + 2).orEmpty().lowercase()
        var hh = h
        if (ampm == "pm" && hh < 12) hh += 12
        if (ampm == "am" && hh == 12) hh = 0
        return try { LocalTime(hh, min) } catch (_: Exception) { null }
    }

    /** A date at the very start of [s] (optionally followed by a clock time). */
    fun matchLeading(s: String): Match? {
        val m = LEADING.find(s) ?: return null
        val date = build(m) ?: return null
        return Match(date, time(m.groupValues, 10), 0, m.range.last + 1)
    }

    /** A date that ends [s] (value-date column sitting just before the amounts). */
    fun matchTrailing(s: String): Match? {
        val t = s.trimEnd()
        val last = ANY.findAll(t).lastOrNull { it.range.last + 1 == t.length } ?: return null
        val date = build(last) ?: return null
        return Match(date, null, last.range.first, last.range.last + 1)
    }

    /** Parses a whole cell such as "01/04/2024", "1-Apr-24" or "2024-04-01 10:15". */
    fun parse(s: String): LocalDate? = matchLeading(s.trim())?.date

    fun toMillis(date: LocalDate, time: LocalTime? = null): Long =
        date.atTimeMillis(time ?: LocalTimeNoon, IST)
}
