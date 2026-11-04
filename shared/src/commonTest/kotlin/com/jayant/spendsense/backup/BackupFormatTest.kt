package com.jayant.spendsense.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

class BackupFormatTest {
    // Fewer iterations keep the suite fast; production uses BackupFormat.ITERATIONS (310 000).
    private val iter = 100_000

    @Test fun aesGcmMatchesNistVectors() {
        // SP 800-38D / McGrew-Viega test cases 13, 14 and 16 (AES-256).
        val z = AesGcm(ByteArray(32))
        assertEquals("530f8afbc74536b9a963b4f1c4cb738b", z.encrypt(ByteArray(12), ByteArray(0), ByteArray(0)).hex())
        assertEquals("cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919", z.encrypt(ByteArray(12), ByteArray(0), ByteArray(16)).hex())
        val k = AesGcm(hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308"))
        val p = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
        val a = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val iv = hex("cafebabefacedbaddecaf888")
        val sealed = k.encrypt(iv, a, p)
        assertEquals(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b",
            sealed.hex(),
        )
        assertEquals(p.hex(), k.decrypt(iv, a, sealed)!!.hex())
        sealed[3] = (sealed[3].toInt() xor 1).toByte()
        assertNull(k.decrypt(iv, a, sealed))
    }

    @Test fun pbkdf2MatchesRfc7914Vector() {
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            BackupCrypto.pbkdf2("password", "salt".encodeToByteArray(), 1, 32).hex(),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            BackupCrypto.pbkdf2("password", "salt".encodeToByteArray(), 4096, 32).hex(),
        )
    }

    @Test fun platformAndPureGcmAgree() {
        val key = BackupCrypto.random(32)
        val nonce = BackupCrypto.random(12)
        val aad = "hdr".encodeToByteArray()
        val msg = ByteArray(1000) { (it * 7).toByte() }
        val a = BackupCrypto.seal(key, nonce, aad, msg)
        assertEquals(a.hex(), AesGcm(key).encrypt(nonce, aad, msg).hex())
        assertEquals(msg.hex(), BackupCrypto.open(key, nonce, aad, a)!!.hex())
    }

    @Test fun roundTrip() {
        val f = BackupFormat.seal("{\"hello\":\"₹ world\"}", "correct horse battery", iter)
        val r = BackupFormat.open(f, "correct horse battery")
        assertTrue(r is BackupFormat.Opened.Ok)
        assertEquals("{\"hello\":\"₹ world\"}", r.payload)
        // Fresh salt and nonce every time.
        val g = BackupFormat.seal("{\"hello\":\"₹ world\"}", "correct horse battery", iter)
        assertTrue(!f.contentEquals(g))
    }

    @Test fun productionDefaultUsesAtLeast310kIterations() {
        assertTrue(BackupFormat.ITERATIONS >= 310_000)
        val f = BackupFormat.seal("{}", "passphrase-1")
        val iterations = ((f[6].toInt() and 0xFF) shl 24) or ((f[7].toInt() and 0xFF) shl 16) or ((f[8].toInt() and 0xFF) shl 8) or (f[9].toInt() and 0xFF)
        assertEquals(BackupFormat.ITERATIONS, iterations)
    }

    @Test fun wrongPassphraseFails() {
        val f = BackupFormat.seal("{}", "the right one", iter)
        assertEquals(BackupFormat.Opened.WrongPassphraseOrTampered, BackupFormat.open(f, "the wrong one"))
    }

    @Test fun tamperedFileFails() {
        val f = BackupFormat.seal("{\"a\":1}", "passphrase!", iter)
        val body = f.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 0x40).toByte() }
        assertEquals(BackupFormat.Opened.WrongPassphraseOrTampered, BackupFormat.open(body, "passphrase!"))
        // Header is authenticated too: a lowered iteration count (still in range) doesn't open.
        val hdr = f.copyOf().also { it[7] = (it[7] - 1).toByte() }
        assertTrue(BackupFormat.open(hdr, "passphrase!") !is BackupFormat.Opened.Ok)
        val truncated = f.copyOf(f.size - 5)
        assertTrue(BackupFormat.open(truncated, "passphrase!") !is BackupFormat.Opened.Ok)
        assertEquals(BackupFormat.Opened.NotABackup, BackupFormat.open("PK\u0003\u0004 not a backup".encodeToByteArray(), "passphrase!"))
    }

    @Test fun newerVersionIsRefusedAndHugeIterationCountsAreRejected() {
        val f = BackupFormat.seal("{}", "passphrase!", iter)
        assertEquals(BackupFormat.Opened.TooNew, BackupFormat.open(f.copyOf().also { it[4] = 9 }, "passphrase!"))
        val dos = f.copyOf().also { it[6] = 0x7F } // ~2 billion iterations
        assertEquals(BackupFormat.Opened.NotABackup, BackupFormat.open(dos, "passphrase!"))
    }

    @Test fun shortPassphraseIsRefused() {
        assertFailsWith<IllegalArgumentException> { BackupFormat.seal("{}", "short", iter) }
    }
}
