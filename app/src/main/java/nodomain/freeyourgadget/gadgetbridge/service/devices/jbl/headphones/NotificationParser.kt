package nodomain.freeyourgadget.gadgetbridge.service.devices.jbl.headphones

import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.service.AbstractDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.kotlin.beShortAt
import org.slf4j.LoggerFactory
import kotlin.math.min

object NotificationParser {
    private val LOG = LoggerFactory.getLogger(NotificationParser::class.java)

    private const val MAGIC = 0xaa.toByte()

    // Notifications

    private const val ID_BATTERY_STATUS = 0x25.toByte()

    fun tryParse(support: AbstractDeviceSupport, value: ByteArray): Boolean {
        if (value.size < 3) {
            LOG.error("Received malformed notification (the data was less than 3 bytes long). Ignoring.")
            return false
        }

        if (value[0] != MAGIC) {
            LOG.error("Received malformed notification (it didn't start with the magic byte 0xAA). Ignoring.")
            return false
        }

        val id = value[1]
        val length = value[2].toInt()

        if (length != value.size - 3) {
            LOG.error("Received malformed notification (expected length of {}, got {}). Ignoring.", value.size - 3, length)
            return false
        }

        val data = value.drop(3).toByteArray()

        return when (id) {
            ID_BATTERY_STATUS -> parseBatteryStatus(support, data)
            else -> {
                LOG.warn(
                    "Received unrecognized notification (id={}, data={}). Ignoring.",
                    id.toHexString(),
                    data.toHexString()
                )
                false
            }
        }
    }

    private fun parseBatteryStatus(support: AbstractDeviceSupport, value: ByteArray): Boolean {
        if (value.isEmpty()) {
            LOG.error("Malformed battery status packet (expected at least one byte, got empty data). Ignoring.")
            return false
        }

        val subCommand = value[0]

        if (subCommand != 1.toByte()) {
            LOG.error("Unsupported subcommand for battery status (only 1 is supported, got {}). Ignoring.", subCommand)
            return false
        }

        if (value.size != 13) {
            LOG.error("Malformed battery status packet (expected size of 13, got {}). Ignoring.", value.size)
            return false
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

        support.handleGBDeviceEvent(GBDeviceEventBatteryInfo().apply {
            level = min(rightBatteryLevel.toInt(), 100)
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
        })

        return true
    }
}
