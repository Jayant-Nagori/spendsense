package com.jayant.spendsense.backup

/** Platform crypto for backups. Android: JCA. iOS: CommonCrypto PBKDF2 + SecRandomCopyBytes + [AesGcm]. */
internal expect object BackupCrypto {
    /** PBKDF2-HMAC-SHA256. */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray

    fun random(n: Int): ByteArray

    /** AES-256-GCM, 12-byte nonce; returns ciphertext || 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray

    /** Null when authentication fails. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?
}
