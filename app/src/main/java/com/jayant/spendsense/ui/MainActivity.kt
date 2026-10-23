package com.jayant.spendsense.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
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

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Home", Icons.Rounded.Home),
    Tab("Wealth", Icons.Rounded.AccountBalanceWallet),
    Tab("Ask", Icons.Rounded.AutoAwesome),
)

/** Where the shell currently is: the three tabs, or a full-screen secondary route opened from Home. */
private sealed interface Route {
    data object Tabs : Route
    data object Activity : Route
    data object Settings : Route
    data object Import : Route
}

private val RouteSaver = Saver<Route, String>(
    save = { when (it) { Route.Tabs -> "tabs"; Route.Activity -> "activity"; Route.Settings -> "settings"; Route.Import -> "import" } },
    restore = { when (it) { "activity" -> Route.Activity; "settings" -> Route.Settings; "import" -> Route.Import; else -> Route.Tabs } },
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
                window.setBackgroundDrawable(ColorDrawable(if (dark) AColor.BLACK else AColor.parseColor("#F4F4F0")))
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
    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(Route.Tabs) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // System back: secondary screen -> Home, other tab -> Home.
    BackHandler(enabled = route != Route.Tabs) { route = if (route == Route.Import) Route.Settings else Route.Tabs }
    BackHandler(enabled = route == Route.Tabs && selected != 0) { selected = 0 }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            // Hidden on secondary screens and while typing so screens with their own imePadding keep their layout.
            AnimatedVisibility(
                visible = !imeVisible && route == Route.Tabs,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(120)),
            ) {
                FlatNav(selected) { selected = it }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (route) {
                Route.Tabs -> AnimatedContent(
                    targetState = selected,
                    transitionSpec = { fadeIn(tween(200, delayMillis = 40)) togetherWith fadeOut(tween(120)) },
                    label = "tab",
                ) { tab ->
                    when (tab) {
                        0 -> DashboardScreen(
                            onSeeAllTransactions = { route = Route.Activity },
                            onOpenSettings = { route = Route.Settings },
                        )
                        1 -> PortfolioScreen()
                        else -> AssistantScreen()
                    }
                }
                Route.Activity -> TransactionsScreen(onBack = { route = Route.Tabs })
                Route.Settings -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp)) {
                        SquareIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", { route = Route.Tabs })
                    }
                    Box(Modifier.weight(1f)) { SettingsScreen() }
                }
                Route.Import -> com.jayant.spendsense.imports.ImportScreen(
                    onBack = { route = Route.Settings },
                    emailSection = { com.jayant.spendsense.imports.email.EmailImportSection() },
                )
            }
        }
    }
}

/** Flat full-width bar: black, 1dp top hairline, three items with icon + tiny uppercase label. */
@Composable
private fun FlatNav(selected: Int, onSelect: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.spend.hairline))
        Row(Modifier.fillMaxWidth()) {
            tabs.forEachIndexed { i, tab ->
                val active = selected == i
                val tint = if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    Modifier
                        .weight(1f)
                        .height(60.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                            if (!active) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        }
                        .semantics { this.selected = active; contentDescription = tab.label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tab.icon, null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(tab.label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.2.sp), color = tint, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
