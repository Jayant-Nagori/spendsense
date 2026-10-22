package com.jayant.spendsense.data

import android.content.Context
import android.util.Base64
import com.jayant.spendsense.security.KeystoreCipher

/**
 * Small key/value settings. The Anthropic API key is stored only as an AES-256-GCM blob wrapped by a
 * non-exportable Android Keystore key; never written to disk in plaintext. Other values are
 * non-secret preferences.
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init { migrateLegacyKey() }

    var anthropicApiKey: String
        get() {
            val enc = prefs.getString(KEY_API_ENC, null) ?: return ""
            return runCatching {
                String(KeystoreCipher.decrypt(KeystoreCipher.ALIAS_PREFS, Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8)
            }.getOrDefault("")
        }
        set(v) {
            val key = v.trim()
            if (key.isEmpty()) {
                prefs.edit().remove(KEY_API_ENC).apply()
                return
            }
            val blob = KeystoreCipher.encrypt(KeystoreCipher.ALIAS_PREFS, key.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(KEY_API_ENC, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        }

    var askSplitAfterPayment: Boolean
        get() = prefs.getBoolean("ask_split", true)
        set(v) = prefs.edit().putBoolean("ask_split", v).apply()

    var smsBackfillDone: Boolean
        get() = prefs.getBoolean("sms_backfill_done", false)
        set(v) = prefs.edit().putBoolean("sms_backfill_done", v).apply()

    /** Require biometric / device credential to open the app. Default on. */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean("app_lock_enabled", true)
        set(v) = prefs.edit().putBoolean("app_lock_enabled", v).apply()

    /** Re-lock after being in the background this long. 0 = every time the app is left. */
    var lockTimeoutSeconds: Int
        get() = prefs.getInt("lock_timeout_s", 30)
        set(v) = prefs.edit().putInt("lock_timeout_s", v.coerceAtLeast(0)).apply()

    /** Remove every stored value (used by "Wipe all data"). */
    fun clearAll() { prefs.edit().clear().commit() }

    private fun migrateLegacyKey() {
        val legacy = prefs.getString(KEY_API_LEGACY, null) ?: return
        val ok = runCatching { if (legacy.isNotBlank()) anthropicApiKey = legacy }.isSuccess
        if (ok) prefs.edit().remove(KEY_API_LEGACY).apply()
    }

    private companion object {
        const val KEY_API_ENC = "anthropic_api_key_enc"
        const val KEY_API_LEGACY = "anthropic_api_key"
    }
}
