package com.jayant.spendsense.backup

/**
 * AES-256-GCM (NIST SP 800-38D, 96-bit nonce, 128-bit tag) in pure Kotlin. Used on iOS, where CommonCrypto has no
 * public GCM API; Android uses the platform's JCA implementation. Both are checked against each other and against
 * NIST vectors in the tests, so a backup made on one platform opens on the other.
 * Not constant-time (table lookups); it only ever runs on the user's own device over their own data.
 */
internal class AesGcm(key: ByteArray) {
    init { require(key.size == 32) { "AES-256 key must be 32 bytes" } }

    private val rk = expandKey(key)
    private val hHi: Long
    private val hLo: Long

    init {
        val h = encryptBlock(ByteArray(16))
        hHi = h.getLong(0); hLo = h.getLong(8)
    }

    /** Returns ciphertext || 16-byte tag. */
    fun encrypt(nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        require(nonce.size == 12) { "nonce must be 12 bytes" }
        val out = ByteArray(plain.size + 16)
        ctr(nonce, plain, out)
        val tag = tag(nonce, aad, out, plain.size)
        tag.copyInto(out, plain.size)
        return out
    }

    /** Null when the tag doesn't verify (wrong key, or the data / header was changed). */
    fun decrypt(nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (nonce.size != 12 || sealed.size < 16) return null
        val n = sealed.size - 16
        val expect = tag(nonce, aad, sealed, n)
        var diff = 0
        for (i in 0 until 16) diff = diff or (expect[i].toInt() xor sealed[n + i].toInt())
        if (diff != 0) return null
        val out = ByteArray(n)
        ctr(nonce, sealed.copyOf(n), out)
        return out
    }

    private fun counterBlock(nonce: ByteArray, counter: Int): ByteArray {
        val b = ByteArray(16)
        nonce.copyInto(b)
        b[12] = (counter ushr 24).toByte(); b[13] = (counter ushr 16).toByte(); b[14] = (counter ushr 8).toByte(); b[15] = counter.toByte()
        return b
    }

    private fun ctr(nonce: ByteArray, input: ByteArray, out: ByteArray) {
        var counter = 2
        var off = 0
        while (off < input.size) {
            val ks = encryptBlock(counterBlock(nonce, counter++))
            val n = minOf(16, input.size - off)
            for (i in 0 until n) out[off + i] = (input[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
        }
    }

    private fun tag(nonce: ByteArray, aad: ByteArray, cipher: ByteArray, cLen: Int): ByteArray {
        var yHi = 0L
        var yLo = 0L
        fun absorb(data: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val blk = ByteArray(16)
                data.copyInto(blk, 0, off, minOf(off + 16, len))
                yHi = yHi xor blk.getLong(0); yLo = yLo xor blk.getLong(8)
                val (a, b) = gmul(yHi, yLo)
                yHi = a; yLo = b
                off += 16
            }
        }
        absorb(aad, aad.size)
        absorb(cipher, cLen)
        yHi = yHi xor (aad.size.toLong() * 8)
        yLo = yLo xor (cLen.toLong() * 8)
        val (a, b) = gmul(yHi, yLo)
        val s = ByteArray(16).also { it.putLong(0, a); it.putLong(8, b) }
        val ek = encryptBlock(counterBlock(nonce, 1))
        for (i in 0 until 16) s[i] = (s[i].toInt() xor ek[i].toInt()).toByte()
        return s
    }

    /** X · H in GF(2^128) with GCM's bit order. */
    private fun gmul(xHi: Long, xLo: Long): Pair<Long, Long> {
        var zHi = 0L
        var zLo = 0L
        var vHi = hHi
        var vLo = hLo
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHi ushr (63 - i)) and 1L else (xLo ushr (127 - i)) and 1L
            if (bit == 1L) { zHi = zHi xor vHi; zLo = zLo xor vLo }
            val lsb = vLo and 1L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = vHi ushr 1
            if (lsb == 1L) vHi = vHi xor -0x1f00000000000000L // 0xE1 << 56
        }
        return zHi to zLo
    }

    // ---- AES-256 block cipher ------------------------------------------------------------------------------------

    private fun encryptBlock(input: ByteArray): ByteArray {
        val s = IntArray(16) { input[it].toInt() and 0xFF }
        addRoundKey(s, 0)
        for (round in 1 until ROUNDS) {
            subBytes(s); shiftRows(s); mixColumns(s); addRoundKey(s, round)
        }
        subBytes(s); shiftRows(s); addRoundKey(s, ROUNDS)
        return ByteArray(16) { s[it].toByte() }
    }

    private fun addRoundKey(s: IntArray, round: Int) { for (i in 0 until 16) s[i] = s[i] xor rk[round * 16 + i] }

    private fun subBytes(s: IntArray) { for (i in 0 until 16) s[i] = SBOX[s[i]] }

    private fun shiftRows(s: IntArray) {
        // State is column-major: s[c * 4 + r].
        val t = s.copyOf()
        for (c in 0 until 4) for (r in 0 until 4) s[c * 4 + r] = t[((c + r) % 4) * 4 + r]
    }

    private fun mixColumns(s: IntArray) {
        for (c in 0 until 4) {
            val a0 = s[c * 4]; val a1 = s[c * 4 + 1]; val a2 = s[c * 4 + 2]; val a3 = s[c * 4 + 3]
            s[c * 4] = xt(a0) xor (xt(a1) xor a1) xor a2 xor a3
            s[c * 4 + 1] = a0 xor xt(a1) xor (xt(a2) xor a2) xor a3
            s[c * 4 + 2] = a0 xor a1 xor xt(a2) xor (xt(a3) xor a3)
            s[c * 4 + 3] = (xt(a0) xor a0) xor a1 xor a2 xor xt(a3)
        }
    }

    private companion object {
        const val ROUNDS = 14

        fun xt(a: Int): Int = ((a shl 1) xor (if (a and 0x80 != 0) 0x1B else 0)) and 0xFF

        val SBOX: IntArray = run {
            val sbox = IntArray(256)
            var p = 1
            var q = 1
            do {
                p = p xor ((p shl 1) and 0xFF) xor (if (p and 0x80 != 0) 0x1B else 0)
                q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xFF
                if (q and 0x80 != 0) q = q xor 0x09
                val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
                sbox[p] = (x xor 0x63) and 0xFF
            } while (p != 1)
            sbox[0] = 0x63
            sbox
        }

        fun rotl8(x: Int, n: Int): Int = ((x shl n) or (x ushr (8 - n))) and 0xFF

        fun expandKey(key: ByteArray): IntArray {
            val w = IntArray(16 * (ROUNDS + 1))
            for (i in 0 until 32) w[i] = key[i].toInt() and 0xFF
            var rcon = 1
            var i = 32
            while (i < w.size) {
                var t0 = w[i - 4]; var t1 = w[i - 3]; var t2 = w[i - 2]; var t3 = w[i - 1]
                if (i % 32 == 0) {
                    val tmp = t0
                    t0 = SBOX[t1] xor rcon; t1 = SBOX[t2]; t2 = SBOX[t3]; t3 = SBOX[tmp]
                    rcon = xt(rcon)
                } else if (i % 32 == 16) {
                    t0 = SBOX[t0]; t1 = SBOX[t1]; t2 = SBOX[t2]; t3 = SBOX[t3]
                }
                w[i] = w[i - 32] xor t0; w[i + 1] = w[i - 31] xor t1; w[i + 2] = w[i - 30] xor t2; w[i + 3] = w[i - 29] xor t3
                i += 4
            }
            return w
        }
    }
}

internal fun ByteArray.getLong(off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (this[off + i].toLong() and 0xFF)
    return v
}

internal fun ByteArray.putLong(off: Int, v: Long) {
    for (i in 0 until 8) this[off + i] = (v ushr (56 - 8 * i)).toByte()
}
