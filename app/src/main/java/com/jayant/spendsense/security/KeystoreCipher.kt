package com.jayant.spendsense.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM using a non-exportable Android Keystore key (hardware / TEE backed where available,
 * StrongBox preferred). Keys are deliberately NOT bound to user authentication: SMS and notification
 * ingestion must keep working while the UI is locked. The UI lock is [AppLockGate]; this class
 * protects data at rest against anyone who obtains the app's files (adb/root/forensic copy).
 *
 * Output format: [1 byte version=1][12 byte IV][ciphertext+16 byte tag]. The key alias is bound in as
 * GCM AAD, so a blob cannot be replayed under another alias.
 */
object KeystoreCipher {
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val VERSION: Byte = 1

    const val ALIAS_DB = "spendsense.db.wrap.v1"
    const val ALIAS_PREFS = "spendsense.prefs.v1"

    private val keyStore: KeyStore
        get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun hasKey(alias: String): Boolean = keyStore.containsAlias(alias)

    fun deleteKey(alias: String) {
        val ks = keyStore
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }

    @Synchronized
    private fun getOrCreateKey(alias: String): SecretKey {
        val ks = keyStore
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return generate(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    private fun generate(alias: String, strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setIsStrongBoxBacked(true)
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        return try {
            gen.init(builder.build())
            gen.generateKey()
        } catch (e: Exception) {
            // StrongBoxUnavailableException (API 28+) or devices that reject the StrongBox flag.
            if (strongBox && (e is StrongBoxUnavailableException || e is java.security.ProviderException)) {
                generate(alias, strongBox = false)
            } else {
                throw e
            }
        }
    }

    fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size == IV_LEN) { "unexpected IV length" }
        return ByteArray(1 + IV_LEN + ct.size).also {
            it[0] = VERSION
            System.arraycopy(iv, 0, it, 1, IV_LEN)
            System.arraycopy(ct, 0, it, 1 + IV_LEN, ct.size)
        }
    }

    /** Throws if the key is missing/invalidated or the blob was tampered with. */
    fun decrypt(alias: String, blob: ByteArray): ByteArray {
        require(blob.size > 1 + IV_LEN + 16 && blob[0] == VERSION) { "bad blob" }
        val key = keyStore.getKey(alias, null) as? SecretKey ?: error("keystore key missing: $alias")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN))
        cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
    }
}
