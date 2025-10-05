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

    fun deviceStatus(type: DeviceStatusType): ByteArray = byteArrayOf(-86, 33, 1, type.id)

    fun batteryInfo(): ByteArray = byteArrayOf(-86, 37, 1, 0)

    fun shutDown(): ByteArray = byteArrayOf(-86, -105, 0)
}
