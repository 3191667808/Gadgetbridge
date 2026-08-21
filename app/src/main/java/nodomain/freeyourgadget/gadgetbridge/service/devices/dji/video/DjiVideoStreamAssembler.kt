package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.video

import org.slf4j.LoggerFactory

/**
 * Reassembles DJI USB video payloads (raw H.264 Annex-B elementary stream) into individual
 * NAL units.
 *
 * A NAL unit larger than one USB payload (~2040 bytes) is simply split across multiple payloads,
 * in order, with no fragment header: the first fragment starts with an Annex-B start code
 * (`00 00 01` or `00 00 00 01`), later fragments don't. Annex-B guarantees a start code can never
 * appear inside a NAL's own payload, so in a byte-accurate stream this class wouldn't need to
 * track fragmentation at all - it could just watch for the next start code and flush everything
 * before it as one completed NAL.
 *
 * In practice, bytes upstream of this class (kernel gadget driver, USB bulk-read loop) can be
 * lost under load. When that happens, the real start code that should have ended a NAL is gone,
 * and this class would otherwise glue the fragments on either side of the gap into one garbage
 * "access unit" - with nothing to indicate anything went wrong. [isPlausibleUnit] catches the
 * common case: DJI's fixed-format NAL types (AUD, SEI, SPS, PPS) have a known, tightly bounded
 * real size on this device, so a "unit" far bigger than that almost certainly swallowed a lost
 * boundary rather than being a legitimate access unit. Slice NALs have no fixed size and aren't
 * checked.
 *
 * Since [feed] runs on the same thread that drains the USB accessory's read endpoint, it
 * must stay cheap: falling behind risks the RC dropping video data to keep up with real-time
 * encoding. The buffer grows geometrically (amortized O(1) per append) and [findStartCode]
 * remembers how far it already scanned, so it never rescans bytes it has already checked.
 */
class DjiVideoStreamAssembler(private val onAccessUnit: (ByteArray) -> Unit) {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0

    // How much of buffer[0 until length] is already confirmed to contain no start code -
    // findStartCode resumes from here instead of rescanning from the top on every feed() call.
    private var searchFrom = 0

    /** Feeds one video-port envelope payload; emits any NAL unit(s) this completes. */
    fun feed(payload: ByteArray) {
        ensureCapacity(length + payload.size)
        System.arraycopy(payload, 0, buffer, length, payload.size)
        length += payload.size

        while (true) {
            val next = findStartCode(searchFrom)
            if (next < 0) {
                // Nothing found in [0, length) - remember that, minus a small overlap in case
                // part of the next start code is already in the buffer.
                searchFrom = maxOf(1, length - (START_CODE.size - 1))
                break
            }
            if (isPlausibleUnit(next)) {
                onAccessUnit(buffer.copyOfRange(0, next))
            } else {
                LOG.warn(
                    "Dropping implausible NAL unit (type={}, {} bytes) - a start code was likely lost upstream",
                    nalType(next), next - START_CODE.size
                )
            }
            System.arraycopy(buffer, next, buffer, 0, length - next)
            length -= next
            searchFrom = 1
        }

        if (length > MAX_BUFFER_SIZE) {
            LOG.error("USB video buffer overflow, resetting")
            length = 0
            searchFrom = 0
        }
    }

    fun reset() {
        length = 0
        searchFrom = 0
    }

    private fun ensureCapacity(minCapacity: Int) {
        if (buffer.size >= minCapacity) return
        var newCapacity = buffer.size * 2
        while (newCapacity < minCapacity) newCapacity *= 2
        buffer = buffer.copyOf(newCapacity)
    }

    /** Finds the next `00 00 01` start-code marker at or after [fromIndex] in `buffer[0 until length)`, or -1. */
    private fun findStartCode(fromIndex: Int): Int {
        val limit = length - START_CODE.size
        var i = fromIndex
        while (i <= limit) {
            if (buffer[i] == START_CODE[0] && buffer[i + 1] == START_CODE[1] && buffer[i + 2] == START_CODE[2]) {
                return i
            }
            i++
        }
        return -1
    }

    /** NAL unit type of the pending unit `buffer[0 until end)`, i.e. the byte right after its start code. */
    private fun nalType(end: Int): Int {
        if (end < START_CODE.size + 1) return -1
        return buffer[START_CODE.size].toInt() and 0x1F
    }

    /**
     * Whether `buffer[0 until end)` is plausible as one real access unit, per [MAX_SIZE_BY_NAL_TYPE].
     * NAL types with no entry (slices, chiefly) have no fixed size on the wire and are always
     * accepted here.
     */
    private fun isPlausibleUnit(end: Int): Boolean {
        val type = nalType(end)
        if (type < 0) return false
        val maxSize = MAX_SIZE_BY_NAL_TYPE[type] ?: return true
        return (end - START_CODE.size) <= maxSize
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DjiVideoStreamAssembler::class.java)

        // The minimal 3-byte start code is enough to find NAL boundaries even
        // when the real marker is the 4-byte `00 00 00 01` form - the extra
        // leading zero just ends up as a harmless trailing byte on the
        // previous NAL, which decoders ignore.
        private val START_CODE = byteArrayOf(0x00, 0x00, 0x01)

        private const val MAX_BUFFER_SIZE = 1 * 1024 * 1024
        private const val INITIAL_CAPACITY = 4096

        // Observed unit size (NAL header + payload, excluding the start code) on real DJI
        // video-port streams: access unit delimiters are always exactly 2 bytes, DJI's
        // proprietary per-frame SEI telemetry block is always 29 bytes, SPS is 48, PPS is 4.
        // A generous multiple of each is used as the plausibility ceiling below, so ordinary
        // firmware/field variation doesn't false-positive - this is only meant to catch a unit
        // that swallowed a lost start code and is therefore many times its normal size.
        // NAL type numbers per ITU-T H.264 7.4.1.
        private const val NAL_TYPE_AUD = 9
        private const val NAL_TYPE_SEI = 6
        private const val NAL_TYPE_SPS = 7
        private const val NAL_TYPE_PPS = 8

        private val MAX_SIZE_BY_NAL_TYPE = mapOf(
            NAL_TYPE_AUD to 8,
            NAL_TYPE_SEI to 128,
            NAL_TYPE_SPS to 256,
            NAL_TYPE_PPS to 256,
        )
    }
}
