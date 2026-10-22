package com.jayant.spendsense.data

import android.content.Context

/** Small key/value settings. Personal sideloaded app: plain SharedPreferences is acceptable. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var anthropicApiKey: String
        get() = prefs.getString("anthropic_api_key", "") ?: ""
        set(v) = prefs.edit().putString("anthropic_api_key", v.trim()).apply()

    var askSplitAfterPayment: Boolean
        get() = prefs.getBoolean("ask_split", true)
        set(v) = prefs.edit().putBoolean("ask_split", v).apply()

    var smsBackfillDone: Boolean
        get() = prefs.getBoolean("sms_backfill_done", false)
        set(v) = prefs.edit().putBoolean("sms_backfill_done", v).apply()
}
