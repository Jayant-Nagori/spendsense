package com.jayant.spendsense.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * Database key management. A 32-byte random key is generated once with [SecureRandom] and persisted
 * ONLY as an AES-GCM blob wrapped by a non-exportable Keystore key. It is unwrapped at open time and
 * handed to SQLCipher as a raw key ("x'<64 hex>'").
 *
 * If the wrapped blob exists but cannot be unwrapped (Keystore key lost/invalidated), this throws
 * rather than silently creating a new key, because that would orphan the existing database.
 */
internal object DbPassphrase {
    private const val PREFS = "ss_keys"
    private const val KEY_WRAPPED = "db_key_wrapped"

    /**
     * [allowCreate] is false when an encrypted database already exists: then a missing or unreadable key is reported
     * as [DbKeyLostException] (→ recovery screen) instead of silently making a new key that can't open the old file.
     */
    @Synchronized
    fun getOrCreate(context: Context, allowCreate: Boolean = true): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        val raw: ByteArray = if (wrapped != null) {
            try {
                KeystoreCipher.decrypt(KeystoreCipher.ALIAS_DB, Base64.decode(wrapped, Base64.NO_WRAP))
            } catch (e: Exception) {
                throw DbKeyLostException(e)
            }
        } else if (!allowCreate) {
            throw DbKeyLostException()
        } else {
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val blob = KeystoreCipher.encrypt(KeystoreCipher.ALIAS_DB, fresh)
            // commit(): the key must be durable before the database is created with it.
            check(prefs.edit().putString(KEY_WRAPPED, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
                "could not persist wrapped database key"
            }
            fresh
        }
        try {
            val sb = StringBuilder("x'")
            for (b in raw) sb.append("%02x".format(b))
            sb.append('\'')
            return sb.toString().toByteArray(Charsets.US_ASCII)
        } finally {
            raw.fill(0)
        }
    }

    /**
     * Recovery of an unreadable (not key-lost) database: keep its wrapped key under a per-generation name so the
     * set-aside file stays decryptable, and free the slot for a new key (same Keystore alias wraps both).
     */
    @Synchronized
    fun setAside(context: Context, stamp: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(KEY_WRAPPED, null) ?: return
        prefs.edit().putString("$KEY_WRAPPED.unreadable-$stamp", wrapped).remove(KEY_WRAPPED).commit()
    }

    /** Destroy all database key material (wrapped blob + Keystore key). */
    @Synchronized
    fun destroy(context: Context) {
        // Only the live key: wrapped keys kept for set-aside databases stay (R4-07).
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_WRAPPED).commit()
        runCatching { KeystoreCipher.deleteKey(KeystoreCipher.ALIAS_DB) }
    }
}
