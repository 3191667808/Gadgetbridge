package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

import nodomain.freeyourgadget.gadgetbridge.util.GB

data class DjiUsbEnvelope(
    val port: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DjiUsbEnvelope) return false
        return port == other.port && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = port
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "DjiUsbEnvelope(port=%d, payload=%s)".format(port, GB.hexdump(payload))
    }
}
