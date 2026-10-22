package com.jayant.spendsense.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalGroceryStore
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.jayant.spendsense.data.PaymentMode

/** Same names as ingest.CategoryGuesser.CATEGORIES (kept local to avoid a compile dependency). */
val SpendCategories = listOf(
    "Food & Dining", "Groceries", "Shopping", "Transport", "Bills & Utilities", "Rent", "Entertainment",
    "Health", "Travel", "Investment", "Transfers", "Cash", "Education", "Fees & Charges", "Other",
)

const val TransfersCategory = "Transfers"

fun categoryColor(name: String): Color = when (name) {
    "Food & Dining" -> Color(0xFFFF8A5B)
    "Groceries" -> Color(0xFF7BD88F)
    "Shopping" -> Color(0xFFFF6FAE)
    "Transport" -> Color(0xFF5CC8FF)
    "Bills & Utilities" -> Color(0xFFFFD166)
    "Rent" -> Color(0xFFA99BFF)
    "Entertainment" -> Color(0xFFC77DFF)
    "Health" -> Color(0xFF4FE0C0)
    "Travel" -> Color(0xFF6C8CFF)
    "Investment" -> Color(0xFFFFB547)
    "Transfers" -> Color(0xFF8E94AB)
    "Cash" -> Color(0xFFB8E986)
    "Education" -> Color(0xFFE2A8FF)
    "Fees & Charges" -> Color(0xFFE5989B)
    else -> Color(0xFF6F7487)
}

fun categoryIcon(name: String): ImageVector = when (name) {
    "Food & Dining" -> Icons.Rounded.Restaurant
    "Groceries" -> Icons.Rounded.LocalGroceryStore
    "Shopping" -> Icons.Rounded.ShoppingBag
    "Transport" -> Icons.Rounded.DirectionsCar
    "Bills & Utilities" -> Icons.Rounded.Bolt
    "Rent" -> Icons.Rounded.Home
    "Entertainment" -> Icons.Rounded.Movie
    "Health" -> Icons.Rounded.MedicalServices
    "Travel" -> Icons.Rounded.Flight
    "Investment" -> Icons.Rounded.TrendingUp
    "Transfers" -> Icons.Rounded.SwapHoriz
    "Cash" -> Icons.Rounded.Payments
    "Education" -> Icons.Rounded.School
    "Fees & Charges" -> Icons.Rounded.Percent
    else -> Icons.Rounded.Category
}

fun PaymentMode.label(): String = when (this) {
    PaymentMode.UPI -> "UPI"
    PaymentMode.CARD -> "Card"
    PaymentMode.CASH -> "Cash"
    PaymentMode.NETBANKING -> "Netbanking"
    PaymentMode.WALLET -> "Wallet"
    PaymentMode.OTHER -> "Other"
}

/** Order used in pickers: Cash first because manual entries are mostly cash. */
val PaymentModePickerOrder = listOf(
    PaymentMode.CASH, PaymentMode.UPI, PaymentMode.CARD, PaymentMode.NETBANKING, PaymentMode.WALLET, PaymentMode.OTHER,
)

/** Validated categorical chart palette (dataviz skill, adjacent-pair CVD checked), assigned by rank in a chart. */
private val ChartDark = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF9085E9, 0xFFE66767).map { Color(it) }
private val ChartLight = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF6250D6, 0xFFE34948).map { Color(it) }
val ChartOtherColor = Color(0xFF6B7185)

fun chartColor(rank: Int, dark: Boolean): Color = (if (dark) ChartDark else ChartLight).getOrElse(rank) { ChartOtherColor }
