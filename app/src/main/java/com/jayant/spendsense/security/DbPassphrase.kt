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

    @Synchronized
    fun getOrCreate(context: Context): ByteArray {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        val raw: ByteArray = if (wrapped != null) {
            KeystoreCipher.decrypt(KeystoreCipher.ALIAS_DB, Base64.decode(wrapped, Base64.NO_WRAP))
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

    /** Destroy all database key material (wrapped blob + Keystore key). */
    @Synchronized
    fun destroy(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        runCatching { KeystoreCipher.deleteKey(KeystoreCipher.ALIAS_DB) }
    }
}
