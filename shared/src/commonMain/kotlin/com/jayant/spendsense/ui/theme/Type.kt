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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.jayant.spendsense.resources.Res
import com.jayant.spendsense.resources.instrument_serif
import com.jayant.spendsense.resources.instrument_serif_italic
import com.jayant.spendsense.resources.manrope
import org.jetbrains.compose.resources.Font

/** Display face: Instrument Serif (single weight, never bold it). It has no rupee glyph; see MoneyText. */
val SerifFamily: FontFamily
    @Composable get() = FontFamily(
        Font(Res.font.instrument_serif, FontWeight.Normal),
        Font(Res.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
    )

/** Text face: Manrope variable font (bundled as a compose resource so iOS matches), tabular figures app-wide. */
val SansFamily: FontFamily
    @Composable get() = FontFamily(
        listOf(400, 500, 600, 700, 800).map { w ->
            Font(Res.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )

private const val Tnum = "tnum"

@Composable
private fun sans(size: Int, weight: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = SansFamily, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = spacing.sp, fontFeatureSettings = Tnum,
)

@Composable
private fun serif(size: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = SerifFamily, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

// CRED-style: money and hero numbers in heavy geometric sans; serif only for screen titles.
val SpendTypography: Typography
    @Composable get() = Typography(
    displayLarge = sans(64, 800, 68, -2.5),
    displayMedium = sans(44, 800, 48, -1.5),
    displaySmall = sans(32, 800, 36, -0.8),
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
    labelMedium = sans(12, 700, 16, 1.2),
    labelSmall = sans(11, 700, 14, 1.6),
)

/** Big money digits that are not Instrument Serif (e.g. in cramped rows). */
val MoneyRowStyle: TextStyle
    @Composable get() = TextStyle(
    fontFamily = SansFamily, fontWeight = FontWeight.W700, fontSize = 16.sp, lineHeight = 20.sp,
    fontFeatureSettings = Tnum, letterSpacing = (-0.01).em,
)
