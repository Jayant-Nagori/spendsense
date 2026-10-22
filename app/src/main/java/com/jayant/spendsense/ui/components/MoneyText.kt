package com.jayant.spendsense.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.jayant.spendsense.ui.theme.LocalSpendColors
import com.jayant.spendsense.ui.theme.MoneyRowStyle
import com.jayant.spendsense.ui.theme.SansFamily

private val CountEase = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

enum class MoneyDecimals { AUTO, ALWAYS, NONE }

/**
 * Money with Indian grouping. The rupee sign is always set in Manrope at a reduced size (Instrument Serif has no
 * rupee glyph), which also makes big hero numbers read as a small "₹" prefix plus confident digits.
 *
 * [animate] counts up from the previously shown value to [amount].
 */
@Composable
fun MoneyText(
    amount: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    animate: Boolean = false,
    decimals: MoneyDecimals = MoneyDecimals.AUTO,
    prefix: String = "",
    symbolScale: Float = 0.55f,
    textAlign: TextAlign? = null,
) {
    var shown by remember { mutableDoubleStateOf(if (animate) 0.0 else amount) }
    if (animate) {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(amount) {
            val from = shown
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1100, easing = CountEase)) {
                shown = from + (amount - from) * value
            }
            shown = amount
        }
    } else shown = amount

    val text = when (decimals) {
        MoneyDecimals.NONE -> groupIndian(Math.round(shown))
        MoneyDecimals.ALWAYS -> formatIndianNumber(shown, forceDecimals = true)
        MoneyDecimals.AUTO -> if (animate) groupIndian(Math.round(shown)) else formatIndianNumber(shown)
    }
    val fontSize: TextUnit =
        if (style.fontSize == TextUnit.Unspecified) MaterialTheme.typography.bodyMedium.fontSize else style.fontSize
    val resolved = when {
        color != Color.Unspecified -> color
        style.color != Color.Unspecified -> style.color
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = buildAnnotatedString {
            if (prefix.isNotEmpty()) append(prefix)
            withStyle(
                SpanStyle(
                    fontFamily = SansFamily, fontWeight = FontWeight.SemiBold,
                    fontSize = fontSize * symbolScale, color = resolved.copy(alpha = 0.7f),
                ),
            ) { append("₹") }
            append(text)
        },
        modifier = modifier,
        style = style.copy(color = resolved),
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
    )
}

/** Hero-size money: Instrument Serif digits, animated count-up. */
@Composable
fun HeroMoney(
    amount: Double,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    animate: Boolean = true,
) {
    MoneyText(
        amount, modifier, MaterialTheme.typography.displayLarge, color, animate,
        MoneyDecimals.NONE, symbolScale = 0.5f,
    )
}

/** Signed money for list rows: "+₹1,200" in green for credit, "₹254" neutral for debit. */
@Composable
fun SignedMoney(
    amount: Double,
    isCredit: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MoneyRowStyle,
) {
    val spend = LocalSpendColors.current
    MoneyText(
        amount = amount,
        modifier = modifier,
        style = style,
        color = if (isCredit) spend.credit else MaterialTheme.colorScheme.onSurface,
        prefix = if (isCredit) "+" else "",
        symbolScale = 0.8f,
    )
}
