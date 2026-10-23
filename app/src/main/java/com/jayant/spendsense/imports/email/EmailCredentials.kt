package com.jayant.spendsense.imports.email

import android.content.Context
import android.util.Base64
import com.jayant.spendsense.security.KeystoreCipher

/**
 * Gmail address + App Password for IMAP import. Own prefs file ("email_import"); the password is stored only
 * as a Keystore AES-GCM blob and is decrypted in memory at connect time. Nothing here is ever logged.
 */
class EmailCredentials(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val address: String get() = prefs.getString(K_ADDRESS, "").orEmpty()

    /** Last four characters of the saved password, for display only. */
    val passwordLast4: String get() = prefs.getString(K_LAST4, "").orEmpty()

    val isConfigured: Boolean get() = address.isNotBlank() && prefs.contains(K_PASS)

    var enabled: Boolean
        get() = prefs.getBoolean(K_ENABLED, false) && isConfigured
        set(v) { prefs.edit().putBoolean(K_ENABLED, v).apply() }

    var lastSyncMillis: Long
        get() = prefs.getLong(K_LAST_SYNC, 0L)
        set(v) { prefs.edit().putLong(K_LAST_SYNC, v).apply() }

    /** Earliest instant a successful sync has already covered (used to re-read when the range is widened). */
    var coveredFromMillis: Long
        get() = prefs.getLong(K_COVERED, 0L)
        set(v) { prefs.edit().putLong(K_COVERED, v).apply() }

    var rangeMonths: Int
        get() = prefs.getInt(K_MONTHS, 3).takeIf { it in RANGES } ?: 3
        set(v) { prefs.edit().putInt(K_MONTHS, if (v in RANGES) v else 3).apply() }

    fun save(address: String, appPassword: String) {
        val pw = normalizePassword(appPassword)
        val blob = KeystoreCipher.encrypt(KeystoreCipher.ALIAS_PREFS, pw.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(K_ADDRESS, address.trim())
            .putString(K_PASS, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putString(K_LAST4, pw.takeLast(4))
            .putLong(K_LAST_SYNC, 0L).putLong(K_COVERED, 0L)
            .putBoolean(K_ENABLED, true)
            .apply()
    }

    /** Decrypts into memory for one connection. Null if nothing saved or the Keystore key is gone (e.g. after a wipe). */
    fun password(): CharArray? {
        val enc = prefs.getString(K_PASS, null) ?: return null
        return runCatching {
            String(KeystoreCipher.decrypt(KeystoreCipher.ALIAS_PREFS, Base64.decode(enc, Base64.NO_WRAP)), Charsets.UTF_8).toCharArray()
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val FILE = "email_import"
        private const val K_ADDRESS = "address"
        private const val K_PASS = "pass_enc"
        private const val K_LAST4 = "pass_last4"
        private const val K_ENABLED = "enabled"
        private const val K_LAST_SYNC = "last_sync"
        private const val K_COVERED = "covered_from"
        private const val K_MONTHS = "range_months"
        val RANGES = listOf(1, 3, 6, 12)

        /** Google shows App Passwords as "abcd efgh ijkl mnop"; IMAP wants them without spaces. */
        fun normalizePassword(raw: String): String = raw.filter { !it.isWhitespace() }
    }
}
