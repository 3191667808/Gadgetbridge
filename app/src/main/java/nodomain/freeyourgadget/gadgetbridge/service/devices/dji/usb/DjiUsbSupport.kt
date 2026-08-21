package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.location.Location
import android.os.SystemClock
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventUpdateDeviceState
import nodomain.freeyourgadget.gadgetbridge.devices.dji.DjiConst
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationProviderType
import nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationService
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.DjiPrefs
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlAck
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlAddress
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlCmdSet
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlCodec
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlFrameReassembler
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlModuleType
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacket
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacketType
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.Battery
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.DumlCommand
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.Flyc
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.General
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.HdLink
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.Rc
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.video.DjiVideoStreamAssembler
import nodomain.freeyourgadget.gadgetbridge.service.usb.AbstractUsbDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Date

class DjiUsbSupport : AbstractUsbDeviceSupport(LOG) {
    private val envelopeReassembler = DjiUsbEnvelopeReassembler()
    private val dumlReassembler = DumlFrameReassembler()
    private val videoAssembler = DjiVideoStreamAssembler { DjiVideoFrameBus.publish(it) }

    private var keepaliveThread: Thread? = null

    private var dumlSeq = 0
    private var camcapCounter = 0
    private var gpsSendSeq = 0

    private val commandReceiver = object : DjiCommandReceiver() {
        override fun handleCommand(command: DjiCommandReceiver.Companion.Command) {
            when (command) {
                DjiCommandReceiver.Companion.Command.KEYFRAME_REQUEST -> {
                    LOG.debug("Scheduling keyframe request")
                    keyframeRequestPending = true
                }
            }
        }
    }

    private lateinit var flightRecorder: DjiFlightRecorder

    @Volatile
    private var videoOut: OutputStream? = null

    // Set whenever a video stream consumer was just reset and needs a fresh keyframe. This is
    // handled by the keepalive loop.
    @Volatile
    private var keyframeRequestPending = false

    override fun getDevicePrefs(): DjiPrefs {
        return DjiPrefs(GBApplication.getDeviceSpecificSharedPrefs(gbDevice.address), gbDevice)
    }

    override fun onConnected() {
        envelopeReassembler.reset()
        dumlReassembler.reset()
        videoAssembler.reset()

        dumlSeq = 0
        camcapCounter = 0
        gpsSendSeq = 0

        keyframeRequestPending = false

        if (videoOut == null && devicePrefs.dumpVideoStream()) {
            try {
                val exportDirectory =
                    gbDevice.deviceCoordinator.getWritableExportDirectory(gbDevice, true)
                val targetDir = File(exportDirectory, "videoStreams")
                targetDir.mkdirs()
                val outputFile = File(
                    targetDir,
                    DateTimeUtils.formatIso8601(Date(System.currentTimeMillis())).replace(":", "-") + ".h264"
                )

                LOG.debug("Starting video output stream to {}", outputFile)

                videoOut = BufferedOutputStream(FileOutputStream(outputFile), 128 * 1024)
            } catch (e: Exception) {
                LOG.error("Error starting video output stream", e)
            }
        }

        GBLocationService.start(context, gbDevice, GBLocationProviderType.GPS, GPS_UPDATE_INTERVAL_MS)

        val keepalive = Thread({
            sendInitializationHandshake()
            keepaliveLoop()
        }, "DjiUsbSupport-keepalive")
        keepaliveThread = keepalive
        keepalive.start()

        evaluateGBDeviceEvent(GBDeviceEventUpdateDeviceState(GBDevice.State.INITIALIZED))
    }

    override fun dispose() {
        running = false

        commandReceiver.unregister(context)

        GBLocationService.stop(context, gbDevice)

        try {
            if (keepaliveThread != null) {
                keepaliveThread?.interrupt()
                keepaliveThread?.join(2000)
                keepaliveThread = null
            }
        } catch (e: Exception) {
            LOG.warn("Failed to stop keepalive thread", e)
        }

        flightRecorder.stop()

        try {
            videoOut?.close()
            videoOut = null
        } catch (e: Exception) {
            LOG.error("Failed to close video output", e)
        }

        super.dispose()
    }

    override fun setContext(gbDevice: GBDevice, usbAccessory: UsbAccessory, context: Context) {
        super.setContext(gbDevice, usbAccessory, context)
        flightRecorder = DjiFlightRecorder(gbDevice, context)
        commandReceiver.register(context)
    }

    override fun onUsbRead(data: ByteArray) {
        for (envelope in envelopeReassembler.feed(data)) {
            dispatch(envelope)
        }
    }

    override fun onSetGpsLocation(location: Location) {
        DjiLocationBus.publish(location)
        sendGpsToFlightController(location)
    }

    private fun sendGpsToFlightController(location: Location) {
        sendCommand(
            DumlCommandSpec(
                receiver = DumlAddress.FLIGHT_CONTROLLER,
                cmdSet = DumlCmdSet.FLYC,
                cmd = Flyc.SendGpsToFlyc.CMD,
                payload = {
                    Flyc.SendGpsToFlyc.Request(
                        fixValid = GPS_FIX_VALID,
                        latitude = location.latitude.toFloat(),
                        longitude = location.longitude.toFloat(),
                        seq = allocateGpsSendSeq(),
                        unknown1 = GPS_SEND_UNKNOWN1,
                        unknown2 = GPS_SEND_UNKNOWN2,
                    ).encode()
                },
            )
        )
    }

    private fun allocateGpsSendSeq(): Int {
        val s = gpsSendSeq
        gpsSendSeq = (gpsSendSeq + 1) and 0xFFFF
        return s
    }

    private fun dispatch(envelope: DjiUsbEnvelope) {
        when (envelope.port) {
            PORT_DUML_CONTROL -> {
                for (packet in dumlReassembler.feed(envelope.payload)) {
                    if (LOG.isTraceEnabled) {
                        LOG.trace("DUML: {}", packet)
                    }

                    val command = DumlCommand.decode(packet)

                    flightRecorder.onTelemetry(packet, command)


                    when (command) {
                        is HdLink.HdLinkState.Request -> {
                            DjiTelemetryBus.publish(command)
                        }

                        is Flyc.OsdGeneral.Request -> {
                            DjiTelemetryBus.publish(command)
                        }

                        is Rc.BatteryInfo.Request -> {
                            evaluateGBDeviceEvent(GBDeviceEventBatteryInfo().apply {
                                batteryIndex = DjiConst.BATTERY_IDX_RC
                                level = command.percent
                            })
                        }

                        is Battery.BatteryDynamicData.Request -> {
                            evaluateGBDeviceEvent(GBDeviceEventBatteryInfo().apply {
                                batteryIndex = DjiConst.BATTERY_IDX_DRONE
                                level = command.percent
                            })
                        }

                        is DumlCommand.Unknown -> {
                            if (LOG.isTraceEnabled) {
                                // We log at warn, but only when trace is enabled, since this is very noisy
                                LOG.warn("Got unknown DUML command: {}", packet)
                            }
                        }

                        else -> {
                            LOG.warn("Got unhandled DUML command: {}", command)
                        }
                    }
                }
            }

            PORT_VIDEO -> {
                if (LOG.isTraceEnabled) {
                    LOG.trace("Got video: {} bytes", envelope.payload.size)
                }
                videoOut?.write(envelope.payload)
                videoAssembler.feed(envelope.payload)
            }

            else -> LOG.warn("Unhandled DJI USB port {}", envelope.port)
        }
    }

    /**
     * Sends the one-time connect handshake from [handshakeCommands]. Runs on the keepalive thread,
     * before [keepaliveLoop] starts, so it never blocks the read thread or [onConnected]'s
     * caller.
     */
    private fun sendInitializationHandshake() {
        for (spec in handshakeCommands) {
            if (!running) return
            try {
                sendCommand(spec)
            } catch (e: IOException) {
                LOG.error(
                    "Failed to send handshake command cmdSet=0x{} cmd=0x{}",
                    spec.cmdSet.toHexString(),
                    spec.cmd.toHexString(),
                    e
                )
                return
            }
            try {
                Thread.sleep(HANDSHAKE_COMMAND_SPACING_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /**
     * Drives every entry in [periodicCommands] from a single thread, each on its own interval.
     */
    private fun keepaliveLoop() {
        val nextDueAt = LongArray(periodicCommands.size)
        while (running) {
            val now = System.currentTimeMillis()
            for (i in periodicCommands.indices) {
                if (now < nextDueAt[i]) continue
                val periodic = periodicCommands[i]
                try {
                    sendCommand(periodic)
                } catch (e: IOException) {
                    if (running) LOG.warn(
                        "Failed to send periodic command cmdSet=0x{} cmd=0x{}",
                        periodic.cmdSet.toHexString(),
                        periodic.cmd.toHexString(),
                        e
                    )
                }
                nextDueAt[i] = now + periodic.intervalMs
            }

            if (keyframeRequestPending) {
                keyframeRequestPending = false
                try {
                    sendCommand(
                        DumlCommandSpec(
                            DumlAddress(DumlModuleType.Camera, 0),
                            DumlCmdSet.CAMERA,
                            CMD_REQUEST_IFRAME,
                        )
                    )
                } catch (e: IOException) {
                    if (running) LOG.warn("Failed to request IFrame", e)
                }
            }

            try {
                Thread.sleep(KEEPALIVE_TICK_INTERVAL_MS)
            } catch (_: InterruptedException) {
                LOG.warn("Keepalive loop interrupted")
                break
            }
        }
    }

    private fun sendCommand(spec: DumlCommandSpec) {
        val packet = DumlPacket(
            sender = DumlAddress.APP,
            receiver = spec.receiver,
            seq = allocateDumlSeq(),
            ack = spec.ack,
            packetType = DumlPacketType.REQUEST,
            cmdSet = spec.cmdSet,
            cmd = spec.cmd,
            payload = spec.payload(),
        )
        val envelope = DjiUsbEnvelope(PORT_DUML_CONTROL, DumlCodec.encode(packet))
        write(
            "cmdset=0x%02x cmd=0x%02x receiver=%s".format(spec.cmdSet, spec.cmd, spec.receiver),
            DjiUsbEnvelopeCodec.encode(envelope)
        )
    }

    private fun allocateDumlSeq(): Int {
        val s = dumlSeq
        dumlSeq = (dumlSeq + 1) and 0xFFFF
        return s
    }

    /**
     * Set RTC time - `cmdset=General cmd=0x4a` to module 14, one of the handshake commands.
     */
    private fun buildSetTimePayload(): ByteArray {
        val cal = java.util.Calendar.getInstance()
        return General.SetTime.Request(
            year = cal.get(java.util.Calendar.YEAR),
            month = cal.get(java.util.Calendar.MONTH) + 1,
            day = cal.get(java.util.Calendar.DAY_OF_MONTH),
            hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
            minute = cal.get(java.util.Calendar.MINUTE),
            second = cal.get(java.util.Calendar.SECOND),
        ).encode()
    }

    /**
     * The one-time connect handshake. Sender is always [DumlAddress.APP].
     */
    private val handshakeCommands: List<DumlCommandSpec> = listOf(
        // Set Date/Time to HD transmission MCU gnd side
        DumlCommandSpec(
            receiver = DumlAddress(DumlModuleType.HdLinkGround, 0),
            cmdSet = DumlCmdSet.GENERAL,
            cmd = 0x4a,
            payload = { buildSetTimePayload() }
        ),
    )

    /**
     * Periodic commands that run for the whole connected session.
     */
    private val periodicCommands: List<DumlCommandSpec> = listOf(
        // Video start / keep-alive
        DumlCommandSpec(
            receiver = DumlAddress(DumlModuleType.DM36xTranscoderAir, 1),
            cmdSet = DumlCmdSet.GENERAL,
            cmd = CMD_CAMCAP_COMMON,
            payload = { buildCamcapCommonPayload(camcapCounter++) },
            intervalMs = 1000L,
        ),

        // "Ping" to HD transmission MCU gnd side - only entry observed
        // with ack=NONE (flags=0x00) rather than ACK_AFTER_EXEC, matching a fire-and-forget ping.
        // Needed to keep the video stream alive.
        DumlCommandSpec(
            DumlAddress(DumlModuleType.HdLinkGround, 0),
            DumlCmdSet.GENERAL,
            0x00,
            ack = DumlAck.NONE,
            intervalMs = 80L
        )
    )

    /**
     * Payload layout: fixed `02 02 00 00` prefix, a 16-bit LE counter that increments every send,
     * 5 zero bytes, a 2-byte LE wrapper length (2 + string length + 4), a 2-byte LE string length,
     * the ASCII string itself, and 4 trailing zero bytes.
     */
    private fun buildCamcapCommonPayload(counter: Int): ByteArray {
        val name = "camcap_common".toByteArray(Charsets.US_ASCII)
        val buf = ByteArray(15 + name.size + 4)
        buf[0] = 0x02
        buf[1] = 0x02
        buf[2] = 0x00
        buf[3] = 0x00
        buf[4] = (counter and 0xFF).toByte()
        buf[5] = ((counter shr 8) and 0xFF).toByte()
        val wrapperLength = 2 + name.size + 4
        buf[11] = (wrapperLength and 0xFF).toByte()
        buf[12] = ((wrapperLength shr 8) and 0xFF).toByte()
        buf[13] = (name.size and 0xFF).toByte()
        buf[14] = ((name.size shr 8) and 0xFF).toByte()
        System.arraycopy(name, 0, buf, 15, name.size)
        return buf
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DjiUsbSupport::class.java)

        private const val PORT_DUML_CONTROL = 22345
        private const val PORT_VIDEO = 22346

        private const val CMD_CAMCAP_COMMON = 0x99

        // Camera cmdset - Request IFrame. The camera only emits a fresh SPS/PPS/IDR set on its
        // own schedule, so a client that attaches mid-stream can be stuck on P-slices with no
        // parameter sets for an undefined amount of time.
        private const val CMD_REQUEST_IFRAME = 0xB3

        // Connect handshake / periodic keepalive
        private const val HANDSHAKE_COMMAND_SPACING_MS = 20L
        private const val KEEPALIVE_TICK_INTERVAL_MS = 20L

        // Phone GPS -> drone
        private const val GPS_UPDATE_INTERVAL_MS = 1000
        private const val GPS_FIX_VALID = 3
        private const val GPS_SEND_UNKNOWN1 = 0x7a
        private const val GPS_SEND_UNKNOWN2 = 0x6a
    }
}

/**
 * One command to send to [receiver], app-originated. See [DjiUsbSupport.handshakeCommands]/[DjiUsbSupport.periodicCommands].
 */
private data class DumlCommandSpec(
    val receiver: DumlAddress,
    val cmdSet: Int,
    val cmd: Int,
    val ack: DumlAck = DumlAck.ACK_AFTER_EXEC,
    val payload: () -> ByteArray = { ByteArray(0) },
    val intervalMs: Long = -1L,
)
