/*  Copyright (C) 2025 hemisputnik (https://512b.dev/)

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

    enum class VoiceAwareMode(val id: Byte, val isEnabled: Byte, val prefValue: String) {
        OFF(0, 0, "off"),
        LOW(1, 1, "low"),
        MEDIUM(2, 1, "medium"),
        HIGH(3, 1, "high");

        companion object {
            val prefValueMap = entries.associateBy { it.prefValue }
            val idMap = entries.associateBy { it.id }
        }
    }

    fun deviceStatus(type: DeviceStatusType) = byteArrayOf(-86, 33, 1, type.id)
    fun batteryInfo() = byteArrayOf(-86, 37, 1, 0)
    fun ancStatus(enabled: Boolean) = byteArrayOf(-86, 49, if (enabled) 1 else 0)
    fun voiceAwareMode() = byteArrayOf(-86, -104, 1, 1)
    fun setVoiceAwareMode(mode: VoiceAwareMode) = byteArrayOf(-86, -104, 3, 0, mode.id, mode.isEnabled)
    fun shutDown() = byteArrayOf(-86, -105, 0)
    fun factoryReset() = byteArrayOf(-86, -107, 0)
}
