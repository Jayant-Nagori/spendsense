package com.jayant.spendsense.backup

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
internal actual object BackupCrypto {
    actual fun pbkdf2(password: String, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        val pw = password.encodeToByteArray()
        val out = ByteArray(keyBytes)
        // The password parameter is a C string: Kotlin passes it as UTF-8, and pw.size is that UTF-8 length.
        val rc = run {
            salt.usePinned { s ->
                out.usePinned { o ->
                    CCKeyDerivationPBKDF(
                        kCCPBKDF2, password, pw.size.convert(),
                        s.addressOf(0).reinterpret(), salt.size.convert(),
                        kCCPRFHmacAlgSHA256, iterations.convert(), o.addressOf(0).reinterpret(), keyBytes.convert(),
                    )
                }
            }
        }
        pw.fill(0)
        check(rc == kCCSuccess) { "key derivation failed" }
        return out
    }

    actual fun random(n: Int): ByteArray {
        val b = ByteArray(n)
        val rc = b.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
        check(rc == errSecSuccess) { "no secure randomness" }
        return b
    }

    actual fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray = AesGcm(key).encrypt(nonce, aad, plain)

    actual fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = AesGcm(key).decrypt(nonce, aad, sealed)
}
