package com.jayant.spendsense.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Extra semantic tokens that Material3's ColorScheme has no slot for. */
@Immutable
data class SpendColors(
    val credit: Color,
    val debit: Color,
    val accent2: Color,
    val accent2Container: Color,
    val hairline: Color,
    val cardTop: Color,       // gradient start for hero surfaces
    val isDark: Boolean,
)

private val DarkSpend = SpendColors(
    credit = CreditGreen, debit = DebitRed, accent2 = Orchid, accent2Container = OrchidDeep,
    hairline = Color.White.copy(alpha = 0.07f), cardTop = Surface2, isDark = true,
)
private val LightSpend = SpendColors(
    credit = LightCredit, debit = LightDebit, accent2 = Color(0xFF6A58E0), accent2Container = Color(0xFFE6E2FF),
    hairline = Color.Black.copy(alpha = 0.07f), cardTop = PaperHigh, isDark = false,
)

val LocalSpendColors = staticCompositionLocalOf { DarkSpend }

val MaterialTheme.spend: SpendColors
    @Composable @ReadOnlyComposable get() = LocalSpendColors.current

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Saffron, onPrimary = Color(0xFF1B1200),
    primaryContainer = SaffronDeep, onPrimaryContainer = Color(0xFFFFE0A8),
    secondary = Orchid, onSecondary = Color(0xFF17123A),
    secondaryContainer = OrchidDeep, onSecondaryContainer = Color(0xFFE4DFFF),
    tertiary = CreditGreen, onTertiary = Color(0xFF00210F),
    tertiaryContainer = Color(0xFF0F3324), onTertiaryContainer = Color(0xFFB6F5D6),
    background = Ink, onBackground = TextHigh,
    surface = Ink, onSurface = TextHigh,
    surfaceVariant = Surface2, onSurfaceVariant = TextMid,
    surfaceTint = Saffron,
    surfaceContainerLowest = Ink, surfaceContainerLow = InkLow, surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2, surfaceContainerHighest = Surface3,
    inverseSurface = TextHigh, inverseOnSurface = Ink, inversePrimary = AmberInk,
    outline = TextLow, outlineVariant = Color(0xFF26262F),
    error = DebitRed, onError = Color(0xFF3A0000),
    errorContainer = Color(0xFF3A1414), onErrorContainer = Color(0xFFFFD2D2),
    scrim = Color.Black,
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = AmberInk, onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3B8), onPrimaryContainer = Color(0xFF3B2400),
    secondary = Color(0xFF6A58E0), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E2FF), onSecondaryContainer = Color(0xFF1F1666),
    tertiary = LightCredit, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFF5E2), onTertiaryContainer = Color(0xFF00391F),
    background = Paper, onBackground = LightText,
    surface = Paper, onSurface = LightText,
    surfaceVariant = PaperHigh, onSurfaceVariant = LightTextMid,
    surfaceTint = AmberInk,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAFAFD), surfaceContainer = PaperCard,
    surfaceContainerHigh = PaperHigh, surfaceContainerHighest = PaperHighest,
    inverseSurface = LightText, inverseOnSurface = Paper, inversePrimary = Saffron,
    outline = Color(0xFF8A8A9C), outlineVariant = Color(0xFFDADAE4),
    error = LightDebit, onError = Color.White,
    errorContainer = Color(0xFFFFDADA), onErrorContainer = Color(0xFF410000),
)

/** Concentric radii: card 24, inner tile 16, chip/pill full. */
val SpendShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** App-wide theme preference (dark is the default). Backed by a tiny SharedPreferences in ui/ThemePrefs. */
object ThemeState {
    val mode = mutableStateOf(ThemeMode.DARK)
}

@Composable
fun resolveDark(mode: ThemeMode = ThemeState.mode.value): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun SpendSenseTheme(darkTheme: Boolean = resolveDark(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSpendColors provides if (darkTheme) DarkSpend else LightSpend) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = SpendTypography,
            shapes = SpendShapes,
            content = content,
        )
    }
}
