package com.jayant.spendsense.ui

import android.content.Context
import android.content.res.Configuration
import com.jayant.spendsense.ui.theme.ThemeMode
import com.jayant.spendsense.ui.theme.ThemeState

/** Resolves the current mode against the system setting, for non-Compose chrome (window background, bars). */
fun ThemePrefs.isDark(context: Context): Boolean = when (ThemeState.mode.value) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
