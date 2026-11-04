package com.jayant.spendsense.ui.components

import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.time.lengthOfMonth
import com.jayant.spendsense.time.localDateOf
import kotlinx.datetime.TimeZone
import kotlinx.datetime.yearMonth
import kotlin.math.PI
import kotlin.math.atan2

/**
 * My spend (split share, net of refunds) per day of the month starting at [monthStart], index 0 = day 1.
 * Only days up to [now] are included for the current month; earlier months cover every day.
 */
fun dailySpend(txns: List<Transaction>, monthStart: Long, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): List<Double> {
    val first = localDateOf(monthStart, zone)
    val today = localDateOf(now, zone)
    val days = if (today.yearMonth == first.yearMonth) today.day else first.lengthOfMonth()
    val out = DoubleArray(days)
    for (t in txns) {
        if (t.type != TxnType.DEBIT) continue
        val d = localDateOf(t.timestamp, zone)
        if (d.yearMonth != first.yearMonth) continue
        val i = d.day - 1
        if (i in out.indices) out[i] += t.effectiveAmount
    }
    return out.toList()
}

/** Index of the donut slice under a tap at ([dx], [dy]) from the centre (y down, slices start at 12 o'clock, clockwise), or null. */
fun donutHit(dx: Float, dy: Float, values: List<Double>, innerRadius: Float, outerRadius: Float): Int? {
    val r = kotlin.math.sqrt(dx * dx + dy * dy)
    if (r < innerRadius || r > outerRadius) return null
    val total = values.sum()
    if (total <= 0.0) return null
    var deg = atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI + 90.0
    if (deg < 0) deg += 360.0
    var acc = 0.0
    values.forEachIndexed { i, v ->
        acc += v / total * 360.0
        if (deg < acc) return i
    }
    return values.lastIndex.takeIf { it >= 0 }
}

/** Slot index (0..count-1) for an x position across [width], or null for an empty chart. */
fun slotAt(x: Float, width: Float, count: Int): Int? =
    if (count <= 0 || width <= 0f) null else (x / (width / count)).toInt().coerceIn(0, count - 1)
