package com.jayant.spendsense.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.data.keyValueStore

/** Anti-phishing promise shown on first run and in Settings › About (both platforms). */
const val SCAM_WARNING =
    "SpendSense never asks for your bank password, OTP, card number, CVV or UPI PIN. Anyone who does is a scammer."

/** Compact bordered card with [SCAM_WARNING]. */
@Composable
fun ScamWarningCard(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth()
            .border(1.dp, c.outlineVariant, RoundedCornerShape(4.dp))
            .padding(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = SCAM_WARNING },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Shield, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
        Text(SCAM_WARNING, style = MaterialTheme.typography.bodySmall, color = c.onSurface)
    }
}

/** iOS first run (Android shows the card on its setup screen): one-time safety notice before the app. */
object FirstRunSafety {
    private const val PREFS = "first_run"
    private const val KEY = "safety_seen_v1"

    fun seen(context: PlatformContext): Boolean = keyValueStore(context, PREFS).getBoolean(KEY, false)

    fun markSeen(context: PlatformContext) = keyValueStore(context, PREFS).putBoolean(KEY, true)
}

@Composable
fun FirstRunSafetyScreen(onContinue: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(c.background).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 32.dp)) {
        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("WELCOME", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            Text("Before you start", style = MaterialTheme.typography.headlineMedium, color = c.onSurface)
            Text(
                "Your payments stay encrypted on this phone. SpendSense only reads the bank messages you send it.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            )
            ScamWarningCard()
        }
        PrimaryButton("I understand", onContinue, Modifier.fillMaxWidth().align(Alignment.BottomCenter))
    }
}
