package com.jayant.spendsense.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.jayant.spendsense.ai.AssistantScreen
import com.jayant.spendsense.portfolio.PortfolioScreen
import com.jayant.spendsense.security.AppLockGate
import com.jayant.spendsense.security.SecureWindow
import com.jayant.spendsense.ui.components.SquareIconButton
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import com.jayant.spendsense.ui.theme.resolveDark
import com.jayant.spendsense.ui.theme.spend

class MainActivity : FragmentActivity() {
    override fun onResume() {
        super.onResume()
        if (com.jayant.spendsense.security.AppIntegrity.tampered) return
        com.jayant.spendsense.orders.AndroidAuthBrowser.onHostResumed()
        // Payments queued while the phone was locked and the process was dead get their pop-up now.
        com.jayant.spendsense.notify.PaymentPopup.onUnlock(this)
        // In the foreground a foreground-service start is always allowed: (re)start capture if the system stopped it.
        com.jayant.spendsense.ingest.CaptureService.sync(this)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (com.jayant.spendsense.security.AppIntegrity.tampered) return
        debugLink(intent)
    }

    /** Debug builds: spendsense://debug/... QA links (intent filter only in src/debug's manifest). */
    private fun debugLink(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (com.jayant.spendsense.BuildConfig.DEBUG && data.scheme == "spendsense") {
            com.jayant.spendsense.ui.DebugLinks.handle(this, data.toString())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (com.jayant.spendsense.security.AppIntegrity.tampered) {
            SecureWindow.apply(this)
            setContent { NotGenuineScreen() }
            return
        }
        debugLink(intent)
        SecureWindow.apply(this)
        ThemePrefs.load(this)
        enableEdgeToEdge()
        setContent {
            val dark = resolveDark()
            SideEffect {
                // Keep system bars and the window behind Compose in step with the chosen theme.
                val bar = AColor.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(bar) else SystemBarStyle.light(bar, bar),
                )
                window.setBackgroundDrawable(ColorDrawable(if (dark) AColor.BLACK else AColor.parseColor("#F4F4F0")))
            }
            SpendSenseTheme(dark) {
                // Lost database key / unreadable file: the recovery screen, never a crash loop or a silent wipe.
                var health by remember { mutableStateOf<com.jayant.spendsense.security.StorageHealth.State?>(null) }
                LaunchedEffect(Unit) {
                    health = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.jayant.spendsense.security.StorageHealth.check {
                            com.jayant.spendsense.data.TransactionRepository.get(applicationContext).pendingReview()
                        }
                    }
                }
                when (health) {
                    null -> Unit
                    com.jayant.spendsense.security.StorageHealth.State.OK -> AppLockGate(this) {
                        var setupDone by remember { mutableStateOf(isSetupDone(this@MainActivity)) }
                        if (setupDone) AppShell() else SetupScreen(onDone = { setupDone = true })
                    }
                    // Recovery restores a backup or starts fresh; it is gated by the device lock like the app itself.
                    else -> AppLockGate(this) { com.jayant.spendsense.ui.RecoveryScreen(health!!) }
                }
            }
        }
    }
}
