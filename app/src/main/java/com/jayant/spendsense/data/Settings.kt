package com.jayant.spendsense.data

import android.content.Context
import android.util.Base64
import com.jayant.spendsense.ai.AiProvider
import com.jayant.spendsense.security.KeystoreCipher

/**
 * Small key/value settings. The AI API key (any provider) is stored only as an AES-256-GCM blob wrapped by a
 * non-exportable Android Keystore key; never written to disk in plaintext. Other values are
 * non-secret preferences.
 */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init { migrateLegacyKey() }

    var aiApiKey: String
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

    /** Explicit provider choice; null = auto-detect from the key prefix. */
    var aiProvider: AiProvider?
        get() = AiProvider.fromName(prefs.getString("ai_provider", null))
        set(v) { prefs.edit().apply { if (v == null) remove("ai_provider") else putString("ai_provider", v.name) }.apply() }

    /** Model id override; blank = the provider's default. */
    var aiModel: String
        get() = prefs.getString("ai_model", "").orEmpty()
        set(v) = prefs.edit().putString("ai_model", v.trim()).apply()

    /** Base URL, only used with the Custom provider. https only. */
    var aiBaseUrl: String
        get() = prefs.getString("ai_base_url", "").orEmpty()
        set(v) = prefs.edit().putString("ai_base_url", v.trim()).apply()

    var askSplitAfterPayment: Boolean
        get() = prefs.getBoolean("ask_split", true)
        set(v) = prefs.edit().putBoolean("ask_split", v).apply()

    /** Version of the one-time category clean-up already applied to stored transactions. */
    var categoryFixVersion: Int
        get() = prefs.getInt("category_fix_version", 0)
        set(v) = prefs.edit().putInt("category_fix_version", v).apply()

    /** Full-screen pop-up over other apps after each payment (needs "Display over other apps"). */
    var paymentPopup: Boolean
        get() = prefs.getBoolean("payment_popup", true)
        set(v) = prefs.edit().putBoolean("payment_popup", v).apply()

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

    /** Old installs stored the key as "anthropic_api_key_enc" (same cipher) or even older plaintext. Move it over. */
    private fun migrateLegacyKey() {
        val old = prefs.getString(KEY_API_ENC_OLD, null)
        if (old != null) {
            if (!prefs.contains(KEY_API_ENC)) prefs.edit().putString(KEY_API_ENC, old).remove(KEY_API_ENC_OLD).apply()
            else prefs.edit().remove(KEY_API_ENC_OLD).apply()
        }
        val legacy = prefs.getString(KEY_API_LEGACY, null) ?: return
        val ok = runCatching { if (legacy.isNotBlank() && aiApiKey.isEmpty()) aiApiKey = legacy }.isSuccess
        if (ok) prefs.edit().remove(KEY_API_LEGACY).apply()
    }

    private companion object {
        const val KEY_API_ENC = "ai_api_key_enc"
        const val KEY_API_ENC_OLD = "anthropic_api_key_enc"
        const val KEY_API_LEGACY = "anthropic_api_key"
    }
}
