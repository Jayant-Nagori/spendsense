package com.jayant.spendsense.portfolio

import com.jayant.spendsense.data.AssetType
import com.jayant.spendsense.data.Investment
import kotlin.math.abs

// Pure Kotlin summary logic for the Wealth tab.

data class Totals(val invested: Double, val current: Double) {
    val gain: Double get() = current - invested
    val gainPct: Double get() = if (invested > 0.0) gain / invested * 100.0 else 0.0
}

fun totals(list: List<Investment>): Totals =
    Totals(invested = list.sumOf { it.investedAmount }, current = list.sumOf { it.valueNow })

fun Investment.gainPct(): Double = if (investedAmount > 0.0) gain / investedAmount * 100.0 else 0.0

data class AllocationSlice(val label: String, val type: AssetType?, val value: Double, val fraction: Float)

/** Allocation by asset type (current value), largest first; the tail beyond [maxSlices] folds into "Others". */
fun allocation(list: List<Investment>, maxSlices: Int = 6): List<AllocationSlice> {
    val byType = list.groupBy { it.assetType }
        .mapValues { (_, v) -> v.sumOf { it.valueNow } }
        .filterValues { it > 0.0 }
        .entries.sortedByDescending { it.value }
    val total = byType.sumOf { it.value }
    if (total <= 0.0) return emptyList()
    val head = byType.take(maxSlices)
    val tail = byType.drop(maxSlices)
    val slices = head.map { AllocationSlice(it.key.label, it.key, it.value, (it.value / total).toFloat()) }.toMutableList()
    if (tail.isNotEmpty()) {
        val v = tail.sumOf { it.value }
        slices += AllocationSlice("Others", null, v, (v / total).toFloat())
    }
    return slices
}

/** Holdings grouped by asset type; groups ordered by total current value, holdings by value. */
fun groupByType(list: List<Investment>): List<Pair<AssetType, List<Investment>>> =
    list.groupBy { it.assetType }
        .map { (t, v) -> t to v.sortedByDescending { it.valueNow } }
        .sortedByDescending { (_, v) -> v.sumOf { it.valueNow } }

/** Types for which a maturity date / interest rate makes sense. */
val MATURITY_TYPES = setOf(
    AssetType.FIXED_DEPOSIT, AssetType.RECURRING_DEPOSIT, AssetType.BOND,
    AssetType.PPF, AssetType.EPF, AssetType.NPS,
)

/** Types that show a maturity countdown on the list. */
val COUNTDOWN_TYPES = setOf(AssetType.FIXED_DEPOSIT, AssetType.RECURRING_DEPOSIT, AssetType.BOND)

/** Holdings maturing within [withinDays] (or matured up to [graceDays] ago), soonest first. */
fun upcomingMaturities(
    list: List<Investment>,
    nowMs: Long,
    withinDays: Long = 180,
    graceDays: Long = 30,
): List<Pair<Investment, Long>> =
    list.mapNotNull { inv ->
        val m = inv.maturityDate ?: return@mapNotNull null
        val d = daysUntil(m, nowMs)
        if (d in -graceDays..withinDays) inv to d else null
    }.sortedBy { it.second }

// ---------- detected investment payments ----------

fun guessAssetType(counterparty: String?): AssetType {
    val c = counterparty?.lowercase().orEmpty()
    return when {
        listOf("groww", "mutual", "mf", "kuvera", "coin", "smallcase", "etmoney", "paytm money", "amc", "fund").any { c.contains(it) } -> AssetType.MUTUAL_FUND
        listOf("zerodha", "upstox", "angel", "icici direct", "5paisa", "dhan", "kite", "broking", "securities").any { c.contains(it) } -> AssetType.STOCK
        c.contains("ppf") -> AssetType.PPF
        c.contains("nps") || c.contains("pension") -> AssetType.NPS
        listOf("gold", "jewel", "mmtc", "safegold", "tanishq").any { c.contains(it) } -> AssetType.GOLD
        listOf("wazirx", "coindcx", "binance", "crypto", "bitcoin").any { c.contains(it) } -> AssetType.CRYPTO
        listOf("lic", "insurance", "hdfc life", "sbi life", "policy").any { c.contains(it) } -> AssetType.INSURANCE
        c.contains("rd ") || c.contains("recurring") -> AssetType.RECURRING_DEPOSIT
        c.contains("fd") || c.contains("fixed deposit") -> AssetType.FIXED_DEPOSIT
        else -> AssetType.OTHER
    }
}

data class PaymentInfo(val id: Long, val counterparty: String?, val amount: Double, val timestamp: Long)

/** True when another payment of the same amount to the same payee exists, i.e. it looks like a SIP. */
fun looksRecurring(p: PaymentInfo, all: List<PaymentInfo>): Boolean =
    all.any {
        it.id != p.id &&
            abs(it.amount - p.amount) < 0.5 &&
            !p.counterparty.isNullOrBlank() &&
            it.counterparty.equals(p.counterparty, ignoreCase = true)
    }

const val TXN_MARKER = "ref#"

/** A detected payment is "tracked" when a holding was created from it, or a matching SIP holding exists. */
fun isPaymentTracked(p: PaymentInfo, holdings: List<Investment>): Boolean = holdings.any { h ->
    (h.notes?.contains("$TXN_MARKER${p.id}") == true) || run {
        val payee = p.counterparty?.trim()?.lowercase().orEmpty()
        if (payee.isEmpty() || !h.isSip) return@run false
        val sameAmount = h.sipAmount != null && abs(h.sipAmount - p.amount) < 0.5
        val samePayee = listOfNotNull(h.platform, h.name).any {
            val n = it.lowercase()
            n.contains(payee) || payee.contains(n)
        }
        sameAmount && samePayee
    }
}

fun prefillFromPayment(p: PaymentInfo, recurring: Boolean, dateLabel: String): Investment {
    val payee = p.counterparty?.takeIf { it.isNotBlank() } ?: "Investment"
    return Investment(
        name = payee,
        assetType = guessAssetType(p.counterparty),
        platform = payee,
        investedAmount = p.amount,
        startDate = p.timestamp,
        isSip = recurring,
        sipAmount = if (recurring) p.amount else null,
        notes = "Added from payment to $payee on $dateLabel · $TXN_MARKER${p.id}",
    )
}

/** Sum of monthly SIP amounts across holdings flagged as SIPs. */
fun monthlySipTotal(list: List<Investment>): Double =
    list.filter { it.isSip }.sumOf { it.sipAmount ?: 0.0 }

/** Fraction of the tenure elapsed (0..1), or null when start/maturity are unknown. */
fun tenureProgress(startMs: Long?, maturityMs: Long?, nowMs: Long): Float? {
    if (startMs == null || maturityMs == null || maturityMs <= startMs) return null
    return ((nowMs - startMs).toDouble() / (maturityMs - startMs)).coerceIn(0.0, 1.0).toFloat()
}
