package com.jayant.spendsense.data

enum class AssetType(val label: String) {
    MUTUAL_FUND("Mutual Fund"), STOCK("Stocks"), FIXED_DEPOSIT("Fixed Deposit"), RECURRING_DEPOSIT("Recurring Deposit"),
    BOND("Bonds"), GOLD("Gold"), PPF("PPF"), EPF("EPF"), NPS("NPS"), CRYPTO("Crypto"),
    REAL_ESTATE("Real Estate"), SAVINGS("Savings"), INSURANCE("Insurance"), LOAN_GIVEN("Money Lent"), OTHER("Other"),
}

/** A holding the user owns. Entered manually (or pre-filled from a detected investment transaction). */
data class Investment(
    val id: Long = 0,
    val name: String,                 // "Parag Parikh Flexi Cap", "HDFC FD #2", "RELIANCE"
    val assetType: AssetType,
    val platform: String?,            // "Zerodha", "Groww", "SBI"
    val investedAmount: Double,
    val currentValue: Double? = null, // null = unknown, treat as investedAmount
    val units: Double? = null,
    val startDate: Long? = null,
    val maturityDate: Long? = null,
    val interestRate: Double? = null, // % p.a.
    val isSip: Boolean = false,
    val sipAmount: Double? = null,
    val notes: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val valueNow: Double get() = currentValue ?: investedAmount
    val gain: Double get() = valueNow - investedAmount
}
