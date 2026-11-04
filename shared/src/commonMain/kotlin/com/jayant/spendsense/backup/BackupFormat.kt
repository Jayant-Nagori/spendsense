package com.jayant.spendsense.backup

/**
 * The encrypted backup file (".ssbk"). Layout, all integers big-endian:
 *
 *     "SSBK" | version u8 | kdf u8 (1 = PBKDF2-HMAC-SHA256) | iterations u32 | salt length u8 | salt | nonce (12) | AES-256-GCM(ciphertext || tag)
 *
 * The whole header is the GCM associated data, so changing any header byte (e.g. lowering the iteration count) makes
 * the file fail to open. Key = PBKDF2(passphrase, random 16-byte salt, 600 000 iterations); random 12-byte nonce.
 */
object BackupFormat {
    const val VERSION = 1
    /** OWASP 2023 figure for PBKDF2-HMAC-SHA256 (the brief's floor is 310 000). */
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 10
    /** Refuse anything bigger: a backup of 50 000 payments is a few MB. */
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val KDF_PBKDF2_SHA256: Byte = 1
    private const val SALT = 16
    private const val NONCE = 12
    // A crafted header must not make us spin for minutes, nor accept a trivially weak key derivation.
    private const val MIN_ITER = 100_000
    private const val MAX_ITER = 1_000_000

    sealed interface Opened {
        data class Ok(val payload: String, val version: Int) : Opened
        /** Wrong passphrase, or the file was modified (GCM can't tell the two apart). */
        data object WrongPassphraseOrTampered : Opened
        data object NotABackup : Opened
        data object TooNew : Opened
    }

    fun isLikelyBackup(bytes: ByteArray): Boolean = bytes.size > 4 && bytes.copyOf(4).contentEquals(MAGIC)

    fun seal(payload: String, passphrase: String, iterations: Int = ITERATIONS): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE) { "passphrase too short" }
        val salt = BackupCrypto.random(SALT)
        val nonce = BackupCrypto.random(NONCE)
        val header = header(VERSION, iterations, salt, nonce)
        val key = BackupCrypto.pbkdf2(passphrase, salt, iterations, 32)
        try {
            return header + BackupCrypto.seal(key, nonce, header, payload.encodeToByteArray())
        } finally {
            key.fill(0)
        }
    }

    fun open(file: ByteArray, passphrase: String): Opened {
        if (file.size > MAX_FILE_BYTES || !isLikelyBackup(file)) return Opened.NotABackup
        if (file.size < 4 + 1 + 1 + 4 + 1) return Opened.NotABackup
        val version = file[4].toInt() and 0xFF
        if (version == 0) return Opened.NotABackup
        if (version > VERSION) return Opened.TooNew
        if (file[5] != KDF_PBKDF2_SHA256) return Opened.NotABackup
        val iterations = ((file[6].toInt() and 0xFF) shl 24) or ((file[7].toInt() and 0xFF) shl 16) or
            ((file[8].toInt() and 0xFF) shl 8) or (file[9].toInt() and 0xFF)
        if (iterations !in MIN_ITER..MAX_ITER) return Opened.NotABackup
        val saltLen = file[10].toInt() and 0xFF
        if (saltLen !in 16..64) return Opened.NotABackup
        val headerLen = 11 + saltLen + NONCE
        if (file.size < headerLen + 16) return Opened.NotABackup
        val salt = file.copyOfRange(11, 11 + saltLen)
        val nonce = file.copyOfRange(11 + saltLen, headerLen)
        val header = file.copyOf(headerLen)
        val key = BackupCrypto.pbkdf2(passphrase, salt, iterations, 32)
        val plain = try {
            BackupCrypto.open(key, nonce, header, file.copyOfRange(headerLen, file.size))
        } finally {
            key.fill(0)
        }
        return plain?.let { Opened.Ok(it.decodeToString(), version) } ?: Opened.WrongPassphraseOrTampered
    }

    internal fun header(version: Int, iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray =
        MAGIC + byteArrayOf(
            version.toByte(), KDF_PBKDF2_SHA256,
            (iterations ushr 24).toByte(), (iterations ushr 16).toByte(), (iterations ushr 8).toByte(), iterations.toByte(),
            salt.size.toByte(),
        ) + salt + nonce
}
