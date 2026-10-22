package com.jayant.spendsense.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jayant.spendsense.R

/** Display face: Instrument Serif (single weight, never bold it). It has no rupee glyph; see MoneyText. */
val SerifFamily = FontFamily(
    Font(R.font.instrument_serif, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
/** Text face: Manrope variable font, tabular figures enabled app-wide so money columns align. */
val SansFamily = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    }
)

private const val Tnum = "tnum"

private fun sans(size: Int, weight: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = SansFamily, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = spacing.sp, fontFeatureSettings = Tnum,
)

private fun serif(size: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = SerifFamily, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val SpendTypography = Typography(
    displayLarge = serif(68, 72, -1.5),
    displayMedium = serif(48, 52, -1.0),
    displaySmall = serif(34, 38, -0.5),
    headlineLarge = serif(32, 38, -0.3),
    headlineMedium = serif(28, 34, -0.2),
    headlineSmall = serif(24, 30),
    titleLarge = sans(20, 700, 26, -0.2),
    titleMedium = sans(16, 700, 22, -0.1),
    titleSmall = sans(14, 700, 20),
    bodyLarge = sans(16, 500, 24),
    bodyMedium = sans(14, 500, 21),
    bodySmall = sans(12, 500, 17),
    labelLarge = sans(14, 700, 18),
    labelMedium = sans(12, 600, 16, 0.1),
    labelSmall = sans(11, 600, 14, 0.2),
)

/** Big money digits that are not Instrument Serif (e.g. in cramped rows). */
val MoneyRowStyle = TextStyle(
    fontFamily = SansFamily, fontWeight = FontWeight.W700, fontSize = 16.sp, lineHeight = 20.sp,
    fontFeatureSettings = Tnum, letterSpacing = (-0.01).em,
)
