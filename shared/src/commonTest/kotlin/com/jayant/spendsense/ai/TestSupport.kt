package com.jayant.spendsense.ai

import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment
import com.jayant.spendsense.data.PaymentMode
import com.jayant.spendsense.data.Transaction
import com.jayant.spendsense.data.TxnSource
import com.jayant.spendsense.data.TxnType
import com.jayant.spendsense.time.ZonedDateTime
import kotlin.math.abs

/** Thursday 22 Oct 2026, 14:00 IST. */
val NOW: ZonedDateTime = ZonedDateTime.of(2026, 10, 22, 14, 0, Ist.ZONE)

fun millis(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
    ZonedDateTime.of(y, m, d, h, min, Ist.ZONE).toEpochMilli()

fun txn(
    id: Long, amount: Double, to: String?, at: Long, type: TxnType = TxnType.DEBIT, category: String = "Other",
    split: Boolean = false, share: Double? = null, reviewed: Boolean = true,
    mode: PaymentMode? = PaymentMode.UPI, account: String? = "XX1234",
    paidForOthers: Boolean = false, paidFor: String? = null,
) = Transaction(
    id = id, amount = amount, type = type, counterparty = to, account = account, bank = "HDFC Bank", upiRef = null,
    source = TxnSource.SMS, sourceApp = null, rawText = "raw sms $id", timestamp = at, category = category,
    isSplit = split || paidForOthers, myShare = if (paidForOthers) 0.0 else share, reviewed = reviewed || paidForOthers, paymentMode = mode,
    paidForOthers = paidForOthers, paidFor = paidFor,
)

/** Mirrors TransactionRepository.search semantics. */
class FakeData(
    private val txns: List<Transaction> = emptyList(),
    private val invs: List<Investment> = emptyList(),
) : AssistantData {
    override fun search(
        fromMillis: Long?, toMillis: Long?, amount: Double?, minAmount: Double?, maxAmount: Double?,
        text: String?, type: TxnType?, category: String?, limit: Int,
    ): List<Transaction> = txns.filter { t ->
        (fromMillis == null || t.timestamp >= fromMillis) &&
            (toMillis == null || t.timestamp < toMillis) &&
            (amount == null || abs(t.amount - amount) < 0.5 || (t.myShare != null && abs(t.myShare - amount) < 0.5)) &&
            (minAmount == null || t.amount >= minAmount) &&
            (maxAmount == null || t.amount <= maxAmount) &&
            (text.isNullOrBlank() || listOfNotNull(t.counterparty, t.rawText, t.note, t.category, t.bank, t.paidFor).any { it.contains(text, true) }) &&
            (type == null || t.type == type) &&
            (category == null || t.category == category)
    }.sortedByDescending { it.timestamp }.take(limit)

    override fun pendingReview(): List<Transaction> = txns.filter { !it.reviewed && it.type == TxnType.DEBIT }
    override fun investments(): List<Investment> = invs
}

fun sampleData(): FakeData = FakeData(
    txns = listOf(
        txn(1, 254.0, "Swiggy", millis(2026, 10, 12, 20, 15), category = "Food"),
        txn(2, 254.0, "Uber", millis(2026, 9, 3, 9, 0), category = "Transport"),
        txn(3, 1200.0, "Zomato", millis(2026, 10, 21, 13, 0), category = "Food", split = true, share = 300.0),
        txn(4, 5000.0, "Reliance Digital", millis(2026, 10, 20, 18, 0), category = "Shopping"),
        txn(5, 800.0, "Blinkit", millis(2026, 10, 22, 10, 0), category = "Groceries"),
        txn(6, 60000.0, "ACME Corp", millis(2026, 10, 1, 9, 0), type = TxnType.CREDIT, category = "Income"),
        txn(7, 450.0, "Cafe Coffee Day", millis(2026, 10, 15, 16, 0), category = "Food", reviewed = false),
    ),
    invs = listOf(
        Investment(1, "Parag Parikh Flexi Cap", AssetType.MUTUAL_FUND, "Groww", 100000.0, 125000.0),
        Investment(2, "Nifty Index", AssetType.MUTUAL_FUND, "Zerodha", 50000.0, null),
        Investment(3, "HDFC FD", AssetType.FIXED_DEPOSIT, "HDFC", 200000.0, 210000.0),
    ),
)
