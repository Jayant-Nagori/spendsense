package com.jayant.spendsense.ui

import android.content.Context
import android.content.res.Configuration
import com.jayant.spendsense.ui.theme.ThemeMode
import com.jayant.spendsense.ui.theme.ThemeState

/** Persists the Dark / Light / System choice (dark is the default). */
object ThemePrefs {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val name = runCatching { prefs(context).getString("theme", ThemeMode.DARK.name) }.getOrNull()
        ThemeState.mode.value = ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.DARK
    }

    fun save(context: Context, mode: ThemeMode) {
        ThemeState.mode.value = mode
        prefs(context).edit().putString("theme", mode.name).apply()
    }

    /** Resolves the current mode against the system setting, for non-Compose chrome (window background, bars). */
    fun isDark(context: Context): Boolean = when (ThemeState.mode.value) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
