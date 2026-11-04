package com.jayant.spendsense.data

import com.jayant.spendsense.PlatformContext
import com.jayant.spendsense.ai.AiProvider

/**
 * Small key/value settings. The AI API key (any provider) is stored only in [SecretStore] (Android: AES-256-GCM
 * blob wrapped by a non-exportable Keystore key; iOS: Keychain); never written to disk in plaintext. Other values
 * are non-secret preferences.
 */
class Settings(context: PlatformContext) {
    private val prefs = keyValueStore(context, "settings")
    private val secrets = secretStore(context, "settings")

    init { migrateLegacyKey() }

    var aiApiKey: String
        get() = runCatching { secrets.read(KEY_API_ENC) }.getOrNull().orEmpty()
        set(v) {
            val key = v.trim()
            secrets.write(KEY_API_ENC, key.ifEmpty { null })
        }

    /** Explicit provider choice; null = auto-detect from the key prefix. */
    var aiProvider: AiProvider?
        get() = AiProvider.fromName(prefs.getString("ai_provider", null))
        set(v) { prefs.putString("ai_provider", v?.name) }

    /** Model id override; blank = the provider's default. */
    var aiModel: String
        get() = prefs.getString("ai_model", "").orEmpty()
        set(v) = prefs.putString("ai_model", v.trim())

    /** Base URL, only used with the Custom provider. https only. */
    var aiBaseUrl: String
        get() = prefs.getString("ai_base_url", "").orEmpty()
        set(v) = prefs.putString("ai_base_url", v.trim())

    /** Dashboard spending chart style. */
    var chartType: ChartType
        get() = ChartType.fromName(prefs.getString("chart_type", null))
        set(v) = prefs.putString("chart_type", v.name)

    var askSplitAfterPayment: Boolean
        get() = prefs.getBoolean("ask_split", true)
        set(v) = prefs.putBoolean("ask_split", v)

    /** Version of the one-time category clean-up already applied to stored transactions. */
    var categoryFixVersion: Int
        get() = prefs.getInt("category_fix_version", 0)
        set(v) = prefs.putInt("category_fix_version", v)

    /** Version of the one-time card/account backfill applied to stored transactions (v6). */
    var accountBackfillVersion: Int
        get() = prefs.getInt("account_backfill_version", 0)
        set(v) = prefs.putInt("account_backfill_version", v)

    /** Full-screen pop-up over other apps after each payment (needs "Display over other apps"). */
    var paymentPopup: Boolean
        get() = prefs.getBoolean("payment_popup", true)
        set(v) = prefs.putBoolean("payment_popup", v)

    /** Android: keep the low-priority "watching for payments" foreground service running while capture is on. */
    var captureService: Boolean
        get() = prefs.getBoolean("capture_service", true)
        set(v) = prefs.putBoolean("capture_service", v)

    var smsBackfillDone: Boolean
        get() = prefs.getBoolean("sms_backfill_done", false)
        set(v) = prefs.putBoolean("sms_backfill_done", v)

    /** Require biometric / device credential to open the app. Default on. */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean("app_lock_enabled", true)
        set(v) = prefs.putBoolean("app_lock_enabled", v)

    /** Re-lock after being in the background this long. 0 = every time the app is left. */
    var lockTimeoutSeconds: Int
        get() = prefs.getInt("lock_timeout_s", 30)
        set(v) = prefs.putInt("lock_timeout_s", v.coerceAtLeast(0))

    /** Splitwise: offer "Also add to Splitwise" after a split (off by default; every push needs a tap). */
    var splitwisePush: Boolean
        get() = prefs.getBoolean("splitwise_push", false)
        set(v) = prefs.putBoolean("splitwise_push", v)

    /** Remove every stored value (used by "Wipe all data"). */
    fun clearAll() { prefs.clear() }

    /** Old installs stored the key as "anthropic_api_key_enc" (same cipher) or even older plaintext. Move it over. */
    private fun migrateLegacyKey() {
        val old = prefs.getString(KEY_API_ENC_OLD, null)
        if (old != null) {
            // Same cipher and file as the current key, just a different name.
            if (!prefs.contains(KEY_API_ENC)) prefs.putString(KEY_API_ENC, old)
            prefs.remove(KEY_API_ENC_OLD)
        }
        val legacy = prefs.getString(KEY_API_LEGACY, null) ?: return
        val ok = runCatching { if (legacy.isNotBlank() && aiApiKey.isEmpty()) aiApiKey = legacy }.isSuccess
        if (ok) prefs.remove(KEY_API_LEGACY)
    }

    private companion object {
        const val KEY_API_ENC = "ai_api_key_enc"
        const val KEY_API_ENC_OLD = "anthropic_api_key_enc"
        const val KEY_API_LEGACY = "anthropic_api_key"
    }
}
