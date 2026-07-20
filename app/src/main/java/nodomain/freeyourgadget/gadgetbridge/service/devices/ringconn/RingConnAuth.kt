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

/** RingConn Gen2 per-connection auth. The ring answers `01 00 00` with `81 00 <challenge> <xor>`;
 *  the host replies `01 01 <r0 r1 r2> 00` where the three response bytes are the last three bytes
 *  of SM3(V, challenge) and V = XOR of the ring MAC's last three bytes. Only key material is the
 *  ring's own MAC — computable offline. Verified against a captured on-device pair. */
object RingConnAuth {
    private const val CMD = 0x01
    private const val SUB_AUTH = 0x01
    private const val TRAILER = 0x00
    private const val DIGEST_LEN = 32
    private const val RESP_BYTES = 3
    private const val BYTE_MASK = 0xFF
    private const val MIN_MAC_LEN = 6

    /** Build the 6-byte auth command answering [challenge], keyed by the ring's [mac] (>= 6 bytes). */
    fun authCommand(challenge: Int, mac: ByteArray): ByteArray {
        require(mac.size >= MIN_MAC_LEN) { "mac must be at least $MIN_MAC_LEN bytes" }
        val xorMac = mac[mac.size - RESP_BYTES].toInt() xor mac[mac.size - RESP_BYTES + 1].toInt() xor
            mac[mac.size - 1].toInt()
        val v = xorMac and BYTE_MASK
        val digest = RingConnSm3Hash.digest(byteArrayOf(v.toByte(), (challenge and BYTE_MASK).toByte()))
        val r = digest.copyOfRange(DIGEST_LEN - RESP_BYTES, DIGEST_LEN)
        return byteArrayOf(CMD.toByte(), SUB_AUTH.toByte(), r[0], r[1], r[2], TRAILER.toByte())
    }
}
