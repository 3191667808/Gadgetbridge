package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml

/**
 * A DUML sender/receiver address: a module type (bits 0-4, see [DumlModuleType])
 * plus a 3-bit index (bits 5-7) - which instance of that module type, e.g. more
 * than one camera or battery could share a type and be told apart by index.
 */
data class DumlAddress(val type: DumlModuleType, val index: Int = 0) {
    fun toByte(): Byte = (((index and 0x07) shl 5) or (type.value and 0x1F)).toByte()

    override fun toString(): String = if (index == 0) "$type" else "$type[$index]"

    companion object {
        val APP = DumlAddress(DumlModuleType.App)
        val WIFI = DumlAddress(DumlModuleType.Wifi)
        val FLIGHT_CONTROLLER = DumlAddress(DumlModuleType.FlightController)

        fun fromByte(b: Byte): DumlAddress {
            val v = b.toInt() and 0xFF
            return DumlAddress(DumlModuleType.fromValue(v and 0x1F), (v shr 5) and 0x07)
        }
    }
}
