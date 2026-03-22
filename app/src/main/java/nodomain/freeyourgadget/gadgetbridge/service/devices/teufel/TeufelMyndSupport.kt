package nodomain.freeyourgadget.gadgetbridge.service.devices.teufel

import android.os.Handler
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventVersionInfo
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.BatteryState
import nodomain.freeyourgadget.gadgetbridge.service.AbstractHeadphoneBTBRDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btbr.TransactionBuilder
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedList
import java.util.Queue
import java.util.UUID
import kotlin.experimental.or

class TeufelMyndSupport : AbstractHeadphoneBTBRDeviceSupport(LOG, MAX_MTU) {
    private val packetBuffer: ByteBuffer = ByteBuffer.allocate(MAX_MTU).order(ByteOrder.BIG_ENDIAN)

    private val messageQueue: Queue<TeufelMyndMessage> = LinkedList()
    private var pendingMessage: TeufelMyndMessage? = null
    private var timeoutRetries = 0
    private val timeoutHandler = Handler()
    private val versionEvent = GBDeviceEventVersionInfo()
    private val batteryEvent = GBDeviceEventBatteryInfo()

    init {
        addSupportedService(UUID_SERVICE_SERIAL_PORT)
    }

    override fun useAutoConnect(): Boolean {
        return true
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        packetBuffer.clear()
        timeoutHandler.removeCallbacksAndMessages(null)
        messageQueue.clear()
        pendingMessage = null
        timeoutRetries = 0
        batteryEvent.state = BatteryState.UNKNOWN
        batteryEvent.level = GBDevice.BATTERY_UNKNOWN.toInt()

        builder.setDeviceState(GBDevice.State.INITIALIZING)

        // Send the fw version request directly, but queue everything else, otherwise the device does not respond to all
        val fwGetPacket = TeufelMyndMessage(TeufelMyndCommand.BT_FIRMWARE_GET)
        builder.write(*fwGetPacket.encode())
        pendingMessage = fwGetPacket
        timeoutHandler.postDelayed({ onCommandTimeout() }, COMMAND_TIMEOUT_MS)

        queueCommand(TeufelMyndCommand.BATTERY_LEVEL_GET)

        // Register for all notifications
        for (notification in TeufelMyndNotification.entries) {
            queueCommand(TeufelMyndCommand.NOTIFICATION_REGISTER, byteArrayOf(notification.id))
        }

        return builder
    }

    override fun dispose() {
        synchronized(ConnectionMonitor) {
            timeoutHandler.removeCallbacksAndMessages(null)
            super.dispose()
        }
    }

    override fun onSocketRead(data: ByteArray) {
        packetBuffer.put(data)
        packetBuffer.flip()

        while (packetBuffer.hasRemaining()) {
            packetBuffer.mark()

            if (packetBuffer.remaining() < TeufelMyndMessage.MIN_PACKET_SIZE) {
                packetBuffer.reset()
                break
            }

            val message = TeufelMyndMessage.tryParse(packetBuffer)
            if (message != null) {
                var sendNext: Boolean
                try {
                    sendNext = handleMessage(message)
                } catch (e: Exception) {
                    LOG.error("Failed to handle message", e)
                    sendNext = true
                }
                if (sendNext) {
                    sendNextCommand()
                }
            }
        }

        packetBuffer.compact()
    }

    private fun handleMessage(message: TeufelMyndMessage): Boolean {
        val command = message.originalCommand

        if (message.isAck) {
            if (command != null) {
                if (message.payload[0] == TeufelMyndMessage.STATUS_SUCCESS) {
                    handleAckResponse(command, message.payload)
                } else {
                    LOG.warn(
                        "Command {} failed with status 0x{}", command,
                        String.format("%02x", message.payload[0])
                    )
                }
            } else {
                LOG.warn("Unknown ACK for command ID 0x{}", String.format("%04x", message.commandId))
            }
        } else {
            // This is a notification from the device
            if (command == TeufelMyndCommand.NOTIFICATION_EVENT) {
                handleNotification(message.payload)
                // ACK the notification
                sendAck(TeufelMyndCommand.NOTIFICATION_EVENT.commandId)
            } else {
                LOG.warn("Unexpected non-ACK command: {}", command)
            }
        }

        return pendingMessage?.let { msg ->
            msg.originalCommand == msg.originalCommand
        } ?: true
    }

    private fun handleAckResponse(command: TeufelMyndCommand, payload: ByteArray) {
        LOG.debug("Handling ACK for {} payload={}", command, payload.toHexString())

        when (command) {
            TeufelMyndCommand.BT_FIRMWARE_GET -> {
                val fwVersion = String(payload, Charsets.US_ASCII)
                LOG.info("Bluetooth firmware: {}", fwVersion)
                versionEvent.fwVersion = fwVersion
                evaluateGBDeviceEvent(versionEvent)
            }

            TeufelMyndCommand.BATTERY_LEVEL_GET -> {
                val level = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                LOG.info("Battery level: {}%", level)
                batteryEvent.level = level
                evaluateGBDeviceEvent(batteryEvent)
                gbDevice.setUpdateState(GBDevice.State.INITIALIZED, context)
            }

            else -> LOG.warn("Unhandled ACK for command {}", command)
        }
    }

    private fun handleNotification(payload: ByteArray) {
        val notificationId = payload[0]
        val notificationData = if (payload.size > 1) payload.copyOfRange(1, payload.size) else byteArrayOf()
        val notification = TeufelMyndNotification.fromId(notificationId)

        LOG.debug(
            "Notification: {} data={}",
            notification ?: String.format("0x%02x", notificationId),
            notificationData.toHexString()
        )

        when (notification) {
            TeufelMyndNotification.BATTERY_LEVEL -> {
                val level = notificationData[0].toInt() and 0xFF
                LOG.info("Battery level notification: {}%", level)
                batteryEvent.level = level
                evaluateGBDeviceEvent(batteryEvent)
            }

            TeufelMyndNotification.BATTERY_LOW -> {
                LOG.info("Battery low notification")
                val batteryEvent = GBDeviceEventBatteryInfo()
                batteryEvent.state = BatteryState.BATTERY_LOW
                evaluateGBDeviceEvent(batteryEvent)
            }

            TeufelMyndNotification.POWER_ADAPTER -> {
                if (notificationData.isNotEmpty()) {
                    val connected = notificationData[0].toInt() != 0
                    LOG.info("Power adapter notification: connected={}", connected)
                    val batteryEvent = GBDeviceEventBatteryInfo()
                    batteryEvent.state = if (connected) BatteryState.BATTERY_CHARGING else BatteryState.BATTERY_NORMAL
                    evaluateGBDeviceEvent(batteryEvent)
                }
            }

            else -> LOG.warn("Unknown notification ID 0x{}", String.format("%02x", notificationId))
        }
    }

    override fun onSendConfiguration(config: String) {
        when (config) {
            // TODO send configs

            else -> super.onSendConfiguration(config)
        }
    }

    private fun queueCommand(command: TeufelMyndCommand, payload: ByteArray = byteArrayOf()) {
        messageQueue.add(TeufelMyndMessage(command, payload))

        if (pendingMessage == null) {
            sendNextCommand()
        }
    }

    private fun onCommandTimeout() {
        if (timeoutRetries++ < MAX_RETRIES) {
            LOG.warn("Timed out waiting for response to {}, retrying (attempt {})", pendingMessage, timeoutRetries)
            pendingMessage?.let { sendNextCommand() }
        } else {
            LOG.warn("Timed out waiting for response to {}, giving up", pendingMessage)
            sendNextCommand()
        }
    }

    private fun sendNextCommand() {
        timeoutHandler.removeCallbacksAndMessages(null)
        timeoutRetries = 0

        val next = messageQueue.poll()
        if (next == null) {
            pendingMessage = null
            LOG.debug("No more commands in queue")
            return
        }

        pendingMessage = next
        LOG.debug("Sending command: {} payload={}", next.originalCommand, next.payload.toHexString())

        val builder = createTransactionBuilder(next.originalCommand?.name ?: next.commandId.toString())
        builder.write(*next.encode())
        builder.queue()

        timeoutHandler.postDelayed({ onCommandTimeout() }, COMMAND_TIMEOUT_MS)
    }

    private fun sendAck(commandId: Short) {
        val builder = createTransactionBuilder("ack $commandId")
        builder.write(*TeufelMyndMessage(
            commandId or TeufelMyndMessage.ACK_MASK.toShort(),
            byteArrayOf(TeufelMyndMessage.STATUS_SUCCESS)
        ).encode())
        builder.queue()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(TeufelMyndSupport::class.java)
        private const val MAX_MTU: Int = 2048
        private const val COMMAND_TIMEOUT_MS = 2000L
        private const val MAX_RETRIES = 3

        private val UUID_SERVICE_SERIAL_PORT: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    }
}
