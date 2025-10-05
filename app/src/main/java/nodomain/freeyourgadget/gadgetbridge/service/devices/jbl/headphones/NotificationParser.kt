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

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.beShortAt
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min

object NotificationParser {
    private val LOG = LoggerFactory.getLogger(NotificationParser::class.java)

    private const val MAGIC = 0xaa.toByte()

    // Notifications

    private const val ID_BATTERY_STATUS = 0x25.toByte()

    /**
     * Parse a characteristic notification.
     * @return A list of actions to be applied to the device.
     */
    fun tryParse(value: ByteArray): List<GBDeviceEvent> {
        if (value.size < 3) {
            LOG.error("Received malformed notification (the data was less than 3 bytes long). Ignoring.")
            return listOf()
        }

        if (value[0] != MAGIC) {
            LOG.error("Received malformed notification (it didn't start with the magic byte 0xAA). Ignoring.")
            return listOf()
        }

        val id = value[1]
        val length = value[2].toInt()

        if (length != value.size - 3) {
            LOG.error("Received malformed notification (expected length of {}, got {}). Ignoring.", value.size - 3, length)
            return listOf()
        }

        val data = value.drop(3).toByteArray()

        return when (id) {
            ID_BATTERY_STATUS -> parseBatteryStatus(data)
            else -> {
                LOG.warn(
                    "Received unrecognized notification (id={}, data={}). Ignoring.",
                    id.toHexString(),
                    data.toHexString()
                )
                listOf()
            }
        }
    }

    private fun parseBatteryStatus(value: ByteArray): List<GBDeviceEvent> {
        if (value.isEmpty()) {
            LOG.error("Malformed battery status packet (expected at least one byte, got empty data). Ignoring.")
            return listOf()
        }

        val subCommand = value[0]

        if (subCommand != 1.toByte()) {
            LOG.error("Unsupported subcommand for battery status (only 1 is supported, got {}). Ignoring.", subCommand)
            return listOf()
        }

        if (value.size != 13) {
            LOG.error("Malformed battery status packet (expected size of 13, got {}). Ignoring.", value.size)
            return listOf()
        }

        val leftChargeStatus = value[1]
        val rightChargeStatus = value[2]
        val leftBatteryLevel = value[3]
        val rightBatteryLevel = value[4]
        val boxChargeStatus = value[5]
        val boxBatteryLevel = value[6]
        val leftVoltage = value.beShortAt(7)
        val rightVoltage = value.beShortAt(9)
        val boxVoltage = value.beShortAt(11)

        LOG.debug(
            "Received battery status\n" +
                    "    leftChargeStatus={}\n" +
                    "    rightChargeStatus={}\n" +
                    "    leftBatteryLevel={}\n" +
                    "    rightBatteryLevel={}\n" +
                    "    boxChargeStatus={}\n" +
                    "    boxBatteryLevel={}\n" +
                    "    leftVoltage={}\n" +
                    "    rightVoltage={}\n" +
                    "    boxVoltage={}",
            leftChargeStatus,
            rightChargeStatus,
            leftBatteryLevel,
            rightBatteryLevel,
            boxChargeStatus,
            boxBatteryLevel,
            leftVoltage,
            rightVoltage,
            boxVoltage
        )

        return listOf(
            GBDeviceEventBatteryInfo().apply {
                level = max(min(rightBatteryLevel.toInt(), 100), 0)
                state =
                    if (rightChargeStatus != 0.toByte())
                        if (level == 100)
                            BatteryState.BATTERY_CHARGING_FULL
                        else
                            BatteryState.BATTERY_CHARGING
                    else when (level) {
                        100 -> BatteryState.BATTERY_NOT_CHARGING_FULL
                        in 0..20 -> BatteryState.BATTERY_LOW
                        else -> BatteryState.BATTERY_NORMAL
                    }
            }
        )
    }
}
