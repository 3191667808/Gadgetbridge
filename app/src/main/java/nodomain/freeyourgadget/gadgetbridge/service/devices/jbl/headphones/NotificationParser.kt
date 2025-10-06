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
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdatePreferences
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

object NotificationParser {
    private val LOG = LoggerFactory.getLogger(NotificationParser::class.java)

    private const val MAGIC = 0xaa.toByte()

    // Notifications

    private const val ID_BATTERY_STATUS = 0x25.toByte()
    private const val ID_VOICEAWARE_INFO = 0x98.toByte()

    /**
     * Parse a characteristic notification.
     * @return A list of actions to be applied to the device.
     */
    fun tryParse(data: ByteArray): List<GBDeviceEvent> {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        if (buf.limit() < 3) {
            LOG.error("Received malformed notification (the data was less than 3 bytes long). Ignoring.")
            return listOf()
        }

        if (buf.get() != MAGIC) {
            LOG.error("Received malformed notification (it didn't start with the magic byte 0xAA). Ignoring.")
            return listOf()
        }

        val id = buf.get()
        val length = buf.get().toInt()

        if (length != buf.remaining()) {
            LOG.error("Received malformed notification (expected length of {}, got {}). Ignoring.", buf.remaining(), length)
            return listOf()
        }

        val arguments = buf.slice()

        return when (id) {
            ID_BATTERY_STATUS -> parseBatteryStatus(arguments)
            ID_VOICEAWARE_INFO -> parseVoiceAwareInfo(arguments)
            else -> {
                LOG.warn(
                    "Received unrecognized notification (id={}, data={}). Ignoring.",
                    id.toHexString(),
                    buf.array().toHexString()
                )
                listOf()
            }
        }
    }

    private fun parseBatteryStatus(data: ByteBuffer): List<GBDeviceEvent> {
        if (!data.hasRemaining()) {
            LOG.error("Malformed battery status packet (expected at least one byte, got empty data). Ignoring.")
            return listOf()
        }

        val subCommand = data.get()

        if (subCommand != 1.toByte()) {
            LOG.error("Unsupported subcommand for battery status (only 1 is supported, got {}). Ignoring.", subCommand)
            return listOf()
        }

        if (data.limit() != 13) {
            LOG.error("Malformed battery status packet (expected size of 13, got {}). Ignoring.", data.limit())
            return listOf()
        }

        val leftChargeStatus = data.get()
        val rightChargeStatus = data.get()
        val leftBatteryLevel = data.get()
        val rightBatteryLevel = data.get()
        val boxChargeStatus = data.get()
        val boxBatteryLevel = data.get()
        val leftVoltage = data.getShort()
        val rightVoltage = data.getShort()
        val boxVoltage = data.getShort()

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

    private fun parseVoiceAwareInfo(data: ByteBuffer): List<GBDeviceEvent> {
        if (data.limit() != 3) {
            LOG.error("Malformed VoiceAware info packet (expected three bytes, got {} instead). Ignoring.", data.limit())
            return listOf()
        }

        val voiceAwareMode = data.get(1)

        return listOf(
            GBDeviceEventUpdatePreferences(
                SettingKeys.PREF_JBL_VOICEAWARE,
                RequestBuilder.VoiceAwareMode.idMap.getOrElse(voiceAwareMode) {
                    LOG.error("Malformed VoiceAware info packet (expected 0-3 for the mode, got {} instead). Ignoring.", voiceAwareMode)
                    return listOf()
                }.also {
                    LOG.debug("Received VoiceAware info: {}", it)
                }.prefValue
            )
        )
    }
}
