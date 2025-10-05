package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones

object RequestBuilder {
    enum class DeviceStatusType(val id: Byte) {
        ALL_STATUS(48),
        ANC(49),
        AMBIENT_AWARE_MODE(50),
        AUTO_OFF(51),
        EQ_PRESET(52),
        MULTI_AI(53),
        BT_CONNECTION_STATUS(54),
        OTA_UPGRADE_STATUS(55),
        AUTO_PLAY_PAUSE_ENABLE_STATUS(56),
        TWS_CONNECTION_STATUS(57),
        ANC_TUNING_STATUS(58),
    }

    fun deviceStatus(type: DeviceStatusType): ByteArray = byteArrayOf(-86, 33, 1, type.id)

    fun batteryInfo(): ByteArray = byteArrayOf(-86, 37, 1, 0)
}
