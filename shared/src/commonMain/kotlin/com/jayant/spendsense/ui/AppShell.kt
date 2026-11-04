package com.jayant.spendsense.ui

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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jayant.spendsense.ai.AssistantScreen
import com.jayant.spendsense.portfolio.PortfolioScreen
import com.jayant.spendsense.security.AppLockGate
import com.jayant.spendsense.ui.components.SquareIconButton
import com.jayant.spendsense.ui.theme.SpendSenseTheme
import com.jayant.spendsense.ui.theme.resolveDark
import com.jayant.spendsense.ui.theme.spend
import androidx.compose.ui.ExperimentalComposeUiApi
import com.jayant.spendsense.platform.Platform

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
    /** Activity filtered to one card / account (from the dashboard's "By card & account"). */
    data class AccountActivity(val key: String) : Route
    data object Settings : Route
    data object Import : Route
    data object ConnectedApps : Route
    data object Backup : Route
    data object Accounts : Route
    data object Splitwise : Route
}

/** Secondary screens opened from Settings go back to Settings. */
private val Route.fromSettings: Boolean
    get() = this == Route.Import || this == Route.ConnectedApps || this == Route.Backup || this == Route.Accounts || this == Route.Splitwise

private val RouteSaver = Saver<Route, String>(
    save = {
        when (it) {
            Route.Tabs -> "tabs"; Route.Activity -> "activity"; Route.Settings -> "settings"; Route.Import -> "import"
            Route.ConnectedApps -> "connected"; Route.Backup -> "backup"; Route.Accounts -> "accounts"; Route.Splitwise -> "splitwise"
            is Route.AccountActivity -> "account:" + it.key
        }
    },
    restore = {
        when {
            it == "activity" -> Route.Activity; it == "settings" -> Route.Settings; it == "import" -> Route.Import
            it == "connected" -> Route.ConnectedApps; it == "backup" -> Route.Backup; it == "accounts" -> Route.Accounts
            it == "splitwise" -> Route.Splitwise; it.startsWith("account:") -> Route.AccountActivity(it.removePrefix("account:"))
            else -> Route.Tabs
        }
    },
)

/** The three-tab shell with full-screen secondary routes. Shared by Android and iOS. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppShell() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(Route.Tabs) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val ctx = com.jayant.spendsense.platformContext()
    // Incremental order sync for connected delivery apps (throttled inside).
    LaunchedEffect(Unit) { com.jayant.spendsense.orders.OrderSync.onAppOpen(ctx) }
    // One-time card/account backfill for rows stored before 1.3, then a Splitwise sync if connected (throttled).
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(com.jayant.spendsense.ioDispatcher) { com.jayant.spendsense.data.Maintenance.onAppOpen(ctx) }
        com.jayant.spendsense.splitwise.SplitwiseSync.onAppOpen(ctx)
    }
    // Debug-only deep links (QA screenshots).
    var debugTxn by remember { mutableStateOf<com.jayant.spendsense.data.Transaction?>(null) }
    val debugNav by DebugLinks.nav.collectAsState()
    LaunchedEffect(debugNav) {
        val n = debugNav ?: return@LaunchedEffect
        DebugLinks.nav.value = null
        when {
            n == "home" -> { route = Route.Tabs; selected = 0 }
            n == "settings" -> route = Route.Settings
            n == "connected" -> route = Route.ConnectedApps
            n.startsWith("txn:") -> debugTxn = kotlinx.coroutines.withContext(com.jayant.spendsense.ioDispatcher) {
                com.jayant.spendsense.data.TransactionRepository.get(ctx).get(n.removePrefix("txn:").toLong())
            }
        }
    }
    debugTxn?.let { TransactionDetailSheet(it, onDismiss = { debugTxn = null }) }

    // System back: secondary screen -> Home, other tab -> Home.
    AppBackHandler(enabled = route != Route.Tabs) { route = if (route.fromSettings) Route.Settings else Route.Tabs }
    AppBackHandler(enabled = route == Route.Tabs && selected != 0) { selected = 0 }

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
                            onOpenAccount = { route = Route.AccountActivity(it) },
                        )
                        1 -> PortfolioScreen()
                        else -> AssistantScreen()
                    }
                }
                Route.Activity -> TransactionsScreen(onBack = { route = Route.Tabs })
                is Route.AccountActivity -> TransactionsScreen(onBack = { route = Route.Tabs }, accountKey = (route as Route.AccountActivity).key)
                Route.Settings -> SettingsScreen(
                    onBack = { route = Route.Tabs }, onOpenImport = { route = Route.Import }, onOpenConnectedApps = { route = Route.ConnectedApps },
                    onOpenBackup = { route = Route.Backup }, onOpenAccounts = { route = Route.Accounts }, onOpenSplitwise = { route = Route.Splitwise },
                )
                Route.Backup -> BackupScreen(onBack = { route = Route.Settings })
                Route.Accounts -> AccountsScreen(onBack = { route = Route.Settings })
                Route.Splitwise -> com.jayant.spendsense.splitwise.SplitwiseScreen(onBack = { route = Route.Settings })
                Route.ConnectedApps -> com.jayant.spendsense.orders.ConnectedAppsScreen(onBack = { route = Route.Settings })
                Route.Import -> Platform.services.importScreen?.invoke { route = Route.Settings }
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
