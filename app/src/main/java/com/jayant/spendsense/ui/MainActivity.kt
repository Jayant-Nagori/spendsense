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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.jayant.spendsense.ai.AssistantScreen
import com.jayant.spendsense.portfolio.PortfolioScreen
import com.jayant.spendsense.security.AppLockGate
import com.jayant.spendsense.security.SecureWindow
import com.jayant.spendsense.ui.components.pressable
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import com.jayant.spendsense.ui.theme.resolveDark
import com.jayant.spendsense.ui.theme.spend

private data class Tab(val label: String, val icon: ImageVector, val short: String = label)

private val tabs = listOf(
    Tab("Dashboard", Icons.Rounded.Dashboard, "Home"),
    Tab("Transactions", Icons.AutoMirrored.Rounded.ReceiptLong, "Activity"),
    Tab("Wealth", Icons.Rounded.AccountBalanceWallet),
    Tab("Ask AI", Icons.Rounded.AutoAwesome),
    Tab("Settings", Icons.Rounded.Settings),
)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                window.setBackgroundDrawable(ColorDrawable(if (dark) AColor.parseColor("#0A0A0F") else AColor.parseColor("#F5F5F9")))
            }
            SpendSenseTheme(dark) {
                AppLockGate(this) {
                    NotificationPermissionOnFirstLaunch()
                    AppShell()
                }
            }
        }
    }
}

@Composable
private fun NotificationPermissionOnFirstLaunch() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val prefs = context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
        val asked = prefs.getBoolean("asked_post_notifications", false)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!asked && !granted) {
            prefs.edit().putBoolean("asked_post_notifications", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun AppShell() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Hidden while typing so screens with their own imePadding (Ask AI) keep their layout.
            AnimatedVisibility(
                visible = !imeVisible,
                enter = fadeIn(tween(160)) + slideInVertically { it / 2 },
                exit = fadeOut(tween(120)) + slideOutVertically { it / 2 },
            ) {
                FloatingNav(selected) { selected = it }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
                label = "tab",
            ) { tab ->
                when (tab) {
                    0 -> DashboardScreen(onSeeAllTransactions = { selected = 1 })
                    1 -> TransactionsScreen()
                    2 -> PortfolioScreen()
                    3 -> AssistantScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}

/** Floating pill: icons only, the active destination expands to icon + label on a tinted capsule. */
@Composable
private fun FloatingNav(selected: Int, onSelect: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .shadow(18.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, MaterialTheme.spend.hairline, CircleShape)
                .padding(6.dp)
                .animateContentSize(spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, tab ->
                val active = selected == i
                val bg by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent, label = "navBg",
                )
                val fg by animateColorAsState(
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "navFg",
                )
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(CircleShape)
                        .background(bg)
                        .pressable(role = Role.Tab) {
                            if (!active) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        }
                        .semantics { this.selected = active; contentDescription = tab.label }
                        .padding(horizontal = if (active) 14.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(tab.icon, null, tint = fg, modifier = Modifier.size(22.dp))
                    if (active) Text(tab.short, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
                }
            }
        }
    }
}

@Suppress("unused")
private val keepShape = RoundedCornerShape(0.dp)
@Suppress("unused")
private val keepFill = Modifier.fillMaxWidth()
