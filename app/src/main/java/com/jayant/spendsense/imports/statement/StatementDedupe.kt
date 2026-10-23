package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.Transaction
import kotlin.math.abs

/**
 * Statement rows carry only a date (no time), so the repository's 10-minute window cannot match an SMS-captured
 * row of the same payment. This matches a candidate against rows already recorded on the same day (same type
 * and amount), letting each existing row absorb at most one candidate so genuine repeats still import.
 */
object StatementDedupe {
    fun matchOnSameDay(candidate: Transaction, existingSameDay: List<Transaction>, claimed: MutableSet<Long>): Transaction? {
        val hit = existingSameDay.firstOrNull {
            it.id !in claimed && it.type == candidate.type && abs(it.amount - candidate.amount) < 0.005
        } ?: return null
        claimed += hit.id
        return hit
    }
}
