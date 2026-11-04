package com.jayant.spendsense.ui

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.data.keyValueStore
import com.jayant.spendsense.ui.theme.ThemeMode
import com.jayant.spendsense.ui.theme.ThemeState

/** Persists the Dark / Light / System choice (dark is the default). Same "ui_prefs" file as before on Android. */
object ThemePrefs {
    private fun prefs(c: PlatformContext) = keyValueStore(c, "ui_prefs")

    fun load(context: PlatformContext) {
        val name = runCatching { prefs(context).getString("theme", ThemeMode.DARK.name) }.getOrNull()
        ThemeState.mode.value = ThemeMode.entries.firstOrNull { it.name == name } ?: ThemeMode.DARK
    }

    fun save(context: PlatformContext, mode: ThemeMode) {
        ThemeState.mode.value = mode
        prefs(context).putString("theme", mode.name)
    }
}
