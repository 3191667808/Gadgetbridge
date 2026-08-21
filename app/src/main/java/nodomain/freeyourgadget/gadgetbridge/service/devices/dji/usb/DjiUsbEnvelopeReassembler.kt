package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.DecodeResult
import org.slf4j.LoggerFactory

/**
 * Accumulates raw bytes across multiple USB accessory reads and yields
 * complete [DjiUsbEnvelope]s as soon as they're fully available. Handles
 * both an envelope split across several reads, and several envelopes
 * delivered in a single read. Discards zero-padding.
 */
class DjiUsbEnvelopeReassembler {
    private var buffer = ByteArray(0)

    /** Feeds newly received bytes; returns any envelopes that could be fully decoded so far. */
    fun feed(bytes: ByteArray): List<DjiUsbEnvelope> {
        buffer += bytes
        val envelopes = mutableListOf<DjiUsbEnvelope>()
        var offset = 0

        while (offset < buffer.size) {
            when (val result = DjiUsbEnvelopeCodec.decodeOne(buffer, offset)) {
                is DecodeResult.Success -> {
                    envelopes.add(result.content)
                    offset += result.bytesConsumed
                }
                is DecodeResult.Invalid -> {
                    // Resynchronize by dropping one byte, rather than getting
                    // stuck forever on corruption/garbage. This also handles
                    // the zero-byte padding from the USB read.
                    offset += 1
                }
                DecodeResult.NeedMoreData -> break
            }
        }

        buffer = if (offset > 0) buffer.copyOfRange(offset, buffer.size) else buffer

        if (buffer.size > MAX_BUFFER_SIZE) {
            LOG.error("USB envelope buffer overflow, resetting")
            buffer = ByteArray(0)
        }

        return envelopes
    }

    fun reset() {
        buffer = ByteArray(0)
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DjiUsbEnvelopeReassembler::class.java)

        private const val MAX_BUFFER_SIZE = 65535
    }
}
