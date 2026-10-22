package com.jayant.spendsense.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.fragment.app.FragmentActivity
import com.jayant.spendsense.ai.AssistantScreen
import com.jayant.spendsense.portfolio.PortfolioScreen
import com.jayant.spendsense.security.AppLockGate
import com.jayant.spendsense.security.SecureWindow
import com.jayant.spendsense.ui.theme.SpendSenseTheme

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Dashboard", Icons.Filled.Dashboard),
    Tab("Transactions", Icons.AutoMirrored.Filled.ReceiptLong),
    Tab("Wealth", Icons.Filled.AccountBalanceWallet),
    Tab("Ask AI", Icons.Filled.AutoAwesome),
    Tab("Settings", Icons.Filled.Settings),
)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SecureWindow.apply(this)
        enableEdgeToEdge()
        setContent {
            SpendSenseTheme {
              AppLockGate(this) {
                var selected by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEachIndexed { i, tab ->
                                NavigationBarItem(
                                    selected = selected == i,
                                    onClick = { selected = i },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (selected) {
                            0 -> DashboardScreen()
                            1 -> TransactionsScreen()
                            2 -> PortfolioScreen()
                            3 -> AssistantScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
              }
            }
        }
    }
}
