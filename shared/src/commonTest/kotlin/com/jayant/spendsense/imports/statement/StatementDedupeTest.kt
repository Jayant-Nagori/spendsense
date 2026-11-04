package com.jayant.spendsense.imports.statement

import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.Test

class StatementDedupeTest {
    private fun t(id: Long, amount: Double, type: TxnType = TxnType.DEBIT) = Transaction(
        id = id, amount = amount, type = type, counterparty = null, account = null, bank = null, upiRef = null,
        source = TxnSource.SMS, sourceApp = null, rawText = "", timestamp = 0,
    )

    @Test fun each_existing_row_absorbs_one_candidate() {
        val existing = listOf(t(1, 20.0))
        val claimed = mutableSetOf<Long>()
        assertNotNull(StatementDedupe.matchOnSameDay(t(0, 20.0), existing, claimed))
        assertNull(StatementDedupe.matchOnSameDay(t(0, 20.0), existing, claimed))
    }

    @Test fun type_and_amount_must_match() {
        val existing = listOf(t(1, 20.0))
        assertNull(StatementDedupe.matchOnSameDay(t(0, 20.0, TxnType.CREDIT), existing, mutableSetOf()))
        assertNull(StatementDedupe.matchOnSameDay(t(0, 21.0), existing, mutableSetOf()))
        assertEquals(1L, StatementDedupe.matchOnSameDay(t(0, 20.004), existing, mutableSetOf())?.id)
    }
}
