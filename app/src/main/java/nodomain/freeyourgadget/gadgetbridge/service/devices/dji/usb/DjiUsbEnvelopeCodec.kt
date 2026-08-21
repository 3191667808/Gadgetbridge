package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.DecodeResult

/**
 * Pure encode/decode logic for a single DJI USB accessory envelope. This is
 * the outer wrapper used on every payload sent over the USB accessory
 * bulk endpoints (both the DUML control channels and the raw video stream).
 *
 * Envelope layout:
 *   0-1      magic, always 0x55 0xCC
 *   2-3      port number (LE)
 *   4-5      payload length (LE), excludes this 8-byte header
 *   6-7      reserved, always observed as 0x00 0x00
 *   8..N-1   payload (N = 8 + length)
 *
 * One USB bulk transfer can contain several envelopes back to back, usually
 * followed by zero-byte padding up to the transfer's fixed read-buffer size.
 */
object DjiUsbEnvelopeCodec {
    private const val MAGIC_0: Byte = 0x55
    private const val MAGIC_1: Byte = 0xCC.toByte()
    private const val HEADER_SIZE = 8

    /**
     * Tries to decode exactly one envelope starting at [offset] in [data].
     * Does not assume [data] contains only one envelope - only bytes up to
     * the decoded envelope's own length are consumed/considered.
     */
    fun decodeOne(data: ByteArray, offset: Int = 0): DecodeResult<DjiUsbEnvelope> {
        val available = data.size - offset
        if (available < HEADER_SIZE) return DecodeResult.NeedMoreData

        if (data[offset] != MAGIC_0 || data[offset + 1] != MAGIC_1) {
            return DecodeResult.Invalid(
                "bad magic bytes 0x%02x 0x%02x".format(data[offset], data[offset + 1])
            )
        }

        val port = (data[offset + 2].toInt() and 0xFF) or ((data[offset + 3].toInt() and 0xFF) shl 8)
        val length = (data[offset + 4].toInt() and 0xFF) or ((data[offset + 5].toInt() and 0xFF) shl 8)

        if (available < HEADER_SIZE + length) return DecodeResult.NeedMoreData

        val payload = data.copyOfRange(offset + HEADER_SIZE, offset + HEADER_SIZE + length)
        val envelope = DjiUsbEnvelope(port, payload)
        return DecodeResult.Success(envelope, HEADER_SIZE + length)
    }

    fun encode(envelope: DjiUsbEnvelope): ByteArray {
        val buf = ByteArray(HEADER_SIZE + envelope.payload.size)
        buf[0] = MAGIC_0
        buf[1] = MAGIC_1
        buf[2] = (envelope.port and 0xFF).toByte()
        buf[3] = ((envelope.port shr 8) and 0xFF).toByte()
        buf[4] = (envelope.payload.size and 0xFF).toByte()
        buf[5] = ((envelope.payload.size shr 8) and 0xFF).toByte()
        buf[6] = 0
        buf[7] = 0
        System.arraycopy(envelope.payload, 0, buf, HEADER_SIZE, envelope.payload.size)
        return buf
    }
}
