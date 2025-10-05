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
    private const val ID_BATTERY_STATUS = 0x25.toByte()

    fun tryParse(support: AbstractDeviceSupport, value: ByteArray): Boolean {
        if (value.size < 2) {
            LOG.error("Received malformed notification (the data was less than 2 bytes long). Ignoring.")
            return false
        }

        if (value[0] != MAGIC) {
            LOG.error("Received malformed notification (it didn't start with the magic byte 0xAA). Ignoring.")
            return false
        }

        when (value[1]) {
            ID_BATTERY_STATUS -> {
                val length = value[2]
                val subCommand = value[3]

                val leftChargeStatus = value[4]
                val rightChargeStatus = value[5]
                val leftBatteryLevel = value[6]
                val rightBatteryLevel = value[7]
                val boxChargeStatus = value[8]
                val boxBatteryLevel = value[9]
                val leftVoltage = value.beShortAt(10)
                val rightVoltage = value.beShortAt(12)
                val boxVoltage = value.beShortAt(14)

                LOG.debug(
                    "Received ID_BATTERY_STATUS\n" +
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
            }

            else -> {
                LOG.warn(
                    "Received unrecognized notification (id={}, value={}). Ignoring.",
                    value[1].toHexString(),
                    GB.hexdump(value, 2, -1)
                )
                return false
            }
        }

        return true
    }
}
