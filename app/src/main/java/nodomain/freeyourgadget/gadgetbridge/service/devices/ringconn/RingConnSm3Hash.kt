/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.ringconn

/** Pure Kotlin SM3 (GB/T 32905) 256-bit hash. Sole use: derive the RingConn per-connection auth
 *  response (see [RingConnAuth]). Verified by the GB/T 32905 known-answer vectors in its test. */
object RingConnSm3Hash {
    private const val BLOCK_BYTES = 64
    private const val DIGEST_WORDS = 8
    private val IV = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt(),
    )
    private const val T0 = 0x79cc4519
    private const val T1 = 0x7a879d8a

    fun digest(input: ByteArray): ByteArray {
        val v = IV.copyOf()
        val padded = pad(input)
        val w = IntArray(68)
        val w1 = IntArray(64)
        var off = 0
        while (off < padded.size) {
            for (i in 0 until 16) {
                w[i] = (padded[off + i * 4].toInt() and 0xff shl 24) or
                    (padded[off + i * 4 + 1].toInt() and 0xff shl 16) or
                    (padded[off + i * 4 + 2].toInt() and 0xff shl 8) or
                    (padded[off + i * 4 + 3].toInt() and 0xff)
            }
            for (j in 16 until 68) {
                val x = w[j - 16] xor w[j - 9] xor rotl(w[j - 3], 15)
                w[j] = p1(x) xor rotl(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]
            compress(v, w, w1)
            off += BLOCK_BYTES
        }
        val out = ByteArray(DIGEST_WORDS * 4)
        for (i in 0 until DIGEST_WORDS) {
            out[i * 4] = (v[i] ushr 24).toByte()
            out[i * 4 + 1] = (v[i] ushr 16).toByte()
            out[i * 4 + 2] = (v[i] ushr 8).toByte()
            out[i * 4 + 3] = v[i].toByte()
        }
        return out
    }

    private fun compress(v: IntArray, w: IntArray, w1: IntArray) {
        var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
        var e = v[4]; var f = v[5]; var g = v[6]; var h = v[7]
        for (j in 0 until 64) {
            val tj = if (j < 16) T0 else T1
            val ss1 = rotl(rotl(a, 12) + e + rotl(tj, j % 32), 7)
            val ss2 = ss1 xor rotl(a, 12)
            val tt1 = (if (j < 16) (a xor b xor c) else ((a and b) or (a and c) or (b and c))) + d + ss2 + w1[j]
            val tt2 = (if (j < 16) (e xor f xor g) else ((e and f) or (e.inv() and g))) + h + ss1 + w[j]
            d = c; c = rotl(b, 9); b = a; a = tt1
            h = g; g = rotl(f, 19); f = e; e = p0(tt2)
        }
        v[0] = v[0] xor a; v[1] = v[1] xor b; v[2] = v[2] xor c; v[3] = v[3] xor d
        v[4] = v[4] xor e; v[5] = v[5] xor f; v[6] = v[6] xor g; v[7] = v[7] xor h
    }

    private fun pad(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        var padLen = BLOCK_BYTES - ((input.size + 9) % BLOCK_BYTES)
        if (padLen == BLOCK_BYTES) padLen = 0
        val out = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(out)
        out[input.size] = 0x80.toByte()
        for (i in 0 until 8) out[out.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        return out
    }

    private fun rotl(x: Int, n: Int) = (x shl (n and 31)) or (x ushr (32 - (n and 31)))
    private fun p0(x: Int) = x xor rotl(x, 9) xor rotl(x, 17)
    private fun p1(x: Int) = x xor rotl(x, 15) xor rotl(x, 23)
}
