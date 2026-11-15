package com.wildenglish.app.audiobook.playback

import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Generates the `Sec-MS-GEC` v2 token required by Microsoft's Edge Read-Aloud
 * WebSocket since October 2026.
 *
 * The token is `<16 hex CS64>-<64 hex SHA-256>`, where the SHA-256 is a
 * deliberately modified variant (custom IV derived from the first 7 salt
 * bytes, one replaced round constant, fabricated padding length) plus a
 * separate CS64 digest. `MessageDigest.getInstance("SHA-256")` CANNOT be used.
 *
 * Faithful port of `generate_sec_ms_gec_v2` from edge-tts `drm.py`
 * (rany2/edge-tts PR #492, released in 7.3.0). The arithmetic is wrapping
 * 32-bit, matching Python's `& 0xFFFFFFFF`.
 *
 * The ticks string must follow the official floating-point path: rounding
 * down to a 5-minute window then scaling by 1e7 as a Double. Replacing this
 * with exact integer arithmetic changes the last digits for some timestamps
 * and produces a token the server rejects.
 */
internal object GecToken {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11644473600.0

    private val SHA256_INITIAL = words(
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val SHA256_STATE = words(
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    )
    private val SHA256_SCHEDULE = words(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C,
        0xD1DD9491, 0xC0DD25A0, 0xF9E70AB6, 0xF0C31573, 0x7058CE4E,
        0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    )
    private val ROUND_K = words(
        0x428A2F98,
        0x71374491,
        0xB5C0FBCF,
        0xE9B5DBA5,
        0x3956C25B,
        0x59F111F1,
        0x923F82A4,
        0xAB1C5ED5,
        0xD807AA98,
        0x12835B01,
        0x243185BE,
        0x550C7DC3,
        0x72BE5D74,
        0x80DEB1FE,
        0x9BDC06A7,
        0xC19BF174,
        0xE49B69C1,
        0xEFBE4786,
        0x0FC19DC6,
        0x240CA1CC,
        0x2DE92C6F,
        0x4A7484AA,
        0x5CB0A9DC,
        0x76F988DA,
        0x983E5152,
        0xA831C66D,
        0xB00327C8,
        0xBF597FC7,
        0xC6E00BF3,
        0xD5A79147,
        0x06CA6351,
        0x14292967,
        0x27B70A85,
        0x2E1B2138,
        0x4D2C6DFC,
        0x53380D13,
        0x650A7354,
        0x766A0ABB,
        0x81C2C92E,
        0x92722C85,
        0xA2BFE8A1,
        0xA81A664B,
        0xC24B8B70,
        0xC76C51A3,
        0xD192E819,
        0xD6990624,
        0xF40E3585,
        0x106AA070,
        0x19A4C116,
        0x1E376C08,
        0x2748774C,
        0x34B0BCB5,
        0x391C0CB3,
        0x4ED8AA4A,
        0x5B9CCA4F,
        0x682E6FF3,
        0x748F82EE,
        0x78A5636F,
        0x84C87814,
        0x8CC70208,
        0x90BEFFFA,
        0xA4506CEB,
        0xBEF9A3F7,
        0xC67178F2,
    )

    // Hex literals above Int.MAX_VALUE are Long in Kotlin; force them back to
    // the wrapping 32-bit Int the algorithm is defined on.
    private val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()
    private val SIGMA0_ADJUSTMENT = 0x059C0B80
    private val SIGMA1_ADJUSTMENT = 0x000B92C0

    /** Standard constants except index 14, which Microsoft replaced. */
    private val BLOCK_K = ROUND_K.clone().also { it[14] = SHA256_ADJUSTMENT }

    private val CS64_COEFFICIENTS = words(
        0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933, 0x8D1EC211, 0x314EF5D5, 0xE92491B1,
        0xF0A5B9D5, 0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8, 0x35FA38C6, 0xCB49E3C6,
    )
    private val CS64_OFFSETS = words(
        0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF, 0xD405B4D5, 0x26E55F3C, 0x607801C6,
        0x82B2C1FB, 0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61, 0xBB04A2C1, 0xC5626167,
    )
    private val CS64_FACTOR = 0x82893933.toInt()
    private val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
    private val CS64_ADJUSTMENT1 = 0x0B1A0000

    private val M_EF0569FB = 0xEF0569FB.toInt()
    private val M_C31713DB = 0xC31713DB.toInt()
    private val M_76F50F34 = 0x76F50F34.toInt()
    private val M_C3EFEA97 = 0xC3EFEA97.toInt()
    private val M_1881CE3C = 0x1881CE3C.toInt()
    private val M_35BD1EC9 = 0x35BD1EC9.toInt()
    private val M_CF98B111 = 0xCF98B111.toInt()
    private val M_87085B9F = 0x87085B9F.toInt()
    private val M_12CEB96D = 0x12CEB96D
    private val M_257E1D83 = 0x257E1D83
    private val M_A27416F5 = 0xA27416F5.toInt()
    private val M_D38396FF = 0xD38396FF.toInt()
    private val M_7C932B89 = 0x7C932B89
    private val M_BFA49F69 = 0xBFA49F69.toInt()

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** Hex literals above Int.MAX_VALUE are Long in Kotlin; keep them 32-bit. */
    private fun words(vararg v: Long): IntArray = IntArray(v.size) { v[it].toInt() }

    /** [unixSeconds] should include any server-clock skew correction. */
    fun generate(unixSeconds: Double): String {
        // Keep the official floating-point path; integer math diverges at this magnitude.
        var ticks = unixSeconds + WIN_EPOCH
        ticks -= ticks % 300.0
        ticks *= 1e7
        val salt = java.lang.String.format(Locale.ROOT, "%.0f", ticks) + TRUSTED_CLIENT_TOKEN
        val data = salt.toByteArray(StandardCharsets.US_ASCII)
        val digest = sha256Digest(data)
        val cs = cs64Digest(data, digest)
        return hexLe(cs[0]) + hexLe(cs[1]) + "-" + hexUpper(digest)
    }

    /**
     * Offline regression check against the token captured from Edge by
     * rany2/edge-tts#492. Returns false if any bit of the port is wrong.
     */
    fun selfTest(): Boolean = generate(1_791_888_000.0) ==
            "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876"

    // ---- modified SHA-256 ----

    private fun rotl16(x: Int) = Integer.rotateLeft(x, 16)
    private fun rotr(x: Int, n: Int) = Integer.rotateRight(x, n)

    private fun sigma0(v: Int): Int =
        (v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)

    private fun sigma1(v: Int): Int =
        (v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)

    private fun bigSigmaE(e: Int) = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
    private fun bigSigmaA(a: Int) = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
    private fun ch(x: Int, y: Int, z: Int) = z xor (x and (y xor z))
    private fun maj(x: Int, y: Int, z: Int) = (x and y) or (z and (x or y))

    /** Standard SHA-256 compression rounds, optionally starting at round 14. */
    private fun rounds(state: IntArray, words: IntArray, first: Int): IntArray {
        var a = state[0];
        var b = state[1];
        var c = state[2];
        var d = state[3]
        var e = state[4];
        var f = state[5];
        var g = state[6];
        var h = state[7]
        val k = if (first == 14) BLOCK_K else ROUND_K
        for (i in first until 64) {
            val t1 = h + bigSigmaE(e) + ch(e, f, g) + k[i] + words[i]
            val t2 = bigSigmaA(a) + maj(a, b, c)
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: IntArray, first: Int) {
        for (i in first until 64) {
            words[i] = sigma1(words[i - 2]) + words[i - 7] + sigma0(words[i - 15]) + words[i - 16]
        }
    }

    /** Custom IV derived from the first 7 bytes of the salt. */
    private fun sha256Init(head: ByteArray): IntArray {
        val w = IntArray(64)
        w[14] = ((head[0].toInt() and 0xFF) shl 16) or
                ((head[1].toInt() and 0xFF) shl 8) or (head[2].toInt() and 0xFF)
        w[15] = ((head[3].toInt() and 0xFF) shl 24) or ((head[4].toInt() and 0xFF) shl 16) or
                ((head[5].toInt() and 0xFF) shl 8) or (head[6].toInt() and 0xFF)
        for (i in 16 until 30) {
            var v = SHA256_SCHEDULE[i - 16]
            if (i - 2 >= 14) {
                var s = sigma1(w[i - 2])
                if (i == 16) s = s xor SIGMA1_ADJUSTMENT
                v += s
            }
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) {
                var s = sigma0(w[i - 15])
                if (i == 29) s = s xor SIGMA0_ADJUSTMENT
                v += s
            }
            w[i] = v
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] +
                SHA256_ADJUSTMENT - ROUND_K[14]
        expandSchedule(w, 31)
        val working = rounds(SHA256_STATE, w, 14)
        return IntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        val length = 57 + data.size
        val zeros = Math.floorMod(55 - length, 64)
        val padded = ByteArray((data.size - 7) + 1 + zeros + 8)
        var p = data.size - 7
        data.copyInto(padded, 0, 7, data.size)
        padded[p++] = 0x80.toByte()
        p += zeros
        val bits = length.toLong() * 8L
        for (i in 7 downTo 0) padded[p++] = (bits ushr (8 * i)).toByte()

        val state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = IntArray(64)
            for (j in 0 until 16) {
                val q = offset + j * 4
                words[j] = ((padded[q].toInt() and 0xFF) shl 24) or
                        ((padded[q + 1].toInt() and 0xFF) shl 16) or
                        ((padded[q + 2].toInt() and 0xFF) shl 8) or
                        (padded[q + 3].toInt() and 0xFF)
            }
            expandSchedule(words, 16)
            val working = rounds(state, words, 0)
            for (i in 0 until 8) state[i] += working[i]
            offset += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (state[i] ushr 24).toByte()
            out[i * 4 + 1] = (state[i] ushr 16).toByte()
            out[i * 4 + 2] = (state[i] ushr 8).toByte()
            out[i * 4 + 3] = state[i].toByte()
        }
        return out
    }

    // ---- CS64 ----

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun wordsLe4Even(data: ByteArray): IntArray {
        val n = (data.size + 3) / 4 * 4
        val p = data.copyOf(n)
        val count = n / 4
        val words = IntArray(if (count % 2 == 0) count else count + 1)
        for (i in 0 until count) {
            val q = i * 4
            words[i] = (p[q].toInt() and 0xFF) or ((p[q + 1].toInt() and 0xFF) shl 8) or
                    ((p[q + 2].toInt() and 0xFF) shl 16) or ((p[q + 3].toInt() and 0xFF) shl 24)
        }
        return words
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): IntArray {
        val keyA = le32(digest, 0) or 1
        val keyB = le32(digest, 4) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR, keyB * CS64_FACTOR)

        val buf = ByteArray(data.size + 1) // one leading zero byte
        data.copyInto(buf, 1, 0, data.size)
        val wl = wordsLe4Even(buf)
        val total = 14 + wl.size
        val terms = IntArray(total)
        val offsets = IntArray(total)
        for (i in 0 until 14) {
            terms[i] = CS64_COEFFICIENTS[i] * factors[i % 2]
            offsets[i] = CS64_OFFSETS[i]
        }
        for (i in wl.indices) {
            val word = wl[i]
            var term = word * if (i % 2 == 0) keyA else keyB
            var offset = rotl16(word) * if (i % 2 == 0) M_EF0569FB else M_C31713DB
            if (i == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms[14 + i] = term
            offsets[14 + i] = offset
        }

        var swapAcc = 0;
        var swapLast = 0;
        var state = 0;
        var acc = 0
        var i = 0
        while (i < total) {
            val x = terms[i];
            val y = terms[i + 1]
            var t = rotl16(offsets[i] + x)
            var q = t * M_76F50F34
            swapAcc += rotl16(q) * M_C3EFEA97 + q
            t = rotl16(offsets[i + 1] + y)
            q = t * M_1881CE3C
            swapLast = rotl16(q) * M_35BD1EC9 + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rotl16(state) * M_CF98B111
            state = rotl16(state) * M_87085B9F
            state = rotl16(state) * M_12CEB96D
            state = rotl16(state) * M_257E1D83
            acc += state
            t = state * keyB + y
            t = rotl16(t) * M_A27416F5
            t = rotl16(t) * M_D38396FF
            state = rotl16(rotl16(t) * M_7C932B89) * M_BFA49F69
            acc += state
            i += 2
        }
        return intArrayOf(swapLast xor state, swapAcc xor acc)
    }

    // ---- hex ----

    private fun hexLe(v: Int): String {
        val sb = StringBuilder(8)
        for (i in 0 until 4) {
            val b = (v ushr (8 * i)) and 0xFF
            sb.append(HEX[b ushr 4]); sb.append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private fun hexUpper(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]); sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
