package com.jayant.spendsense.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Exact wording shown when the APK signature check fails (also asserted in tests). */
const val NOT_GENUINE_MESSAGE =
    "This copy of SpendSense isn't genuine — it may have been modified to steal your data. " +
        "Uninstall it and install only from the official link."

/**
 * Full-screen block shown instead of the app when [com.jayant.spendsense.security.SignatureCheck] fails in a release
 * build. Deliberately self-contained: no database, settings, network or shared UI is touched.
 */
@Composable
fun NotGenuineScreen() {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0B0B)).systemBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Don't use this app",
            color = Color(0xFFFF6B5E),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            NOT_GENUINE_MESSAGE,
            color = Color(0xFFF2F2F2),
            fontSize = 17.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            "Your data has not been opened by this copy.",
            color = Color(0xFFA8A8A8),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 18.dp),
        )
    }
}
