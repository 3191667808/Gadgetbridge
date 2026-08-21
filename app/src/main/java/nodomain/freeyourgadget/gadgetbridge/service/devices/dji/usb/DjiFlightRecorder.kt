package nodomain.freeyourgadget.gadgetbridge.service.devices.dji.usb

import android.content.Context
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.BaseActivitySummaryProvider
import nodomain.freeyourgadget.gadgetbridge.devices.dji.DjiDumlActivityTrackProvider
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.export.AutoFitExporter
import nodomain.freeyourgadget.gadgetbridge.export.AutoGpxExporter
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryParser
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlCodec
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlPacket
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.DumlCommand
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.Flyc
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Date
import kotlin.use

class DjiFlightRecorder(
    private val gbDevice: GBDevice,
    private val context: Context
) {
    @Volatile
    private var currentActivity: BaseActivitySummary? = null

    @Volatile
    private var telemetryOutput: OutputStream? = null

    fun stop() {
        LOG.debug("Stopping flight recorder")

        val currentTimeMillis = System.currentTimeMillis()

        telemetryOutput?.let {
            LOG.debug("Closing telemetry output stream")

            synchronized(it) {
                try {
                    it.close()
                } catch (e: Exception) {
                    LOG.error("Error closing telemetry output stream", e)
                } finally {
                    telemetryOutput = null
                }
            }
        }

        currentActivity?.let {
            try {
                LOG.debug("Persisting activity to database")

                GBApplication.acquireDB().use { dbHandler ->
                    val session = dbHandler.daoSession

                    it.endTime = Date(currentTimeMillis)
                    BaseActivitySummaryProvider(gbDevice, session).persistSamples(it, context)

                    // FIXME this should be done asynchronously
                    val trackProvider = DjiDumlActivityTrackProvider()
                    val activityTrack = trackProvider.getActivityTrack(it)
                    AutoGpxExporter.doExport(context, gbDevice, it, activityTrack)
                    AutoFitExporter.doExport(context, gbDevice, it, activityTrack)
                }
            } catch (e: Exception) {
                LOG.error("Error persisting activity", e)
            }

            currentActivity = null
        }
    }

    fun onTelemetry(packet: DumlPacket, command: DumlCommand) {
        val currentTimeMillis = System.currentTimeMillis()

        write(currentTimeMillis, packet)

        if (command is Flyc.OsdGeneral.Request) {
            if (command.motorOn && currentActivity == null) {
                LOG.debug("Starting activity at {}", currentTimeMillis)
                try {
                    GBApplication.acquireDB().use { dbHandler ->
                        val session = dbHandler.daoSession
                        val device = DBHelper.getDevice(gbDevice, session)

                        val activity = ActivitySummaryParser.createBaseActivitySummary(
                            session,
                            device.id!!,
                            currentTimeMillis / 1000L
                        )
                        activity.activityKind = ActivityKind.FLYING.code

                        currentActivity = activity
                    }
                } catch (e: Exception) {
                    LOG.error("Error starting activity", e)
                }

                if (telemetryOutput == null) {
                    try {
                        val exportDirectory =
                            gbDevice.deviceCoordinator.getWritableExportDirectory(gbDevice, true)
                        val targetDir = File(exportDirectory, "telemetryRecordings")
                        targetDir.mkdirs()
                        val outputFile = File(targetDir, DateTimeUtils.formatIso8601(Date(currentTimeMillis)).replace(":", "-") + ".bin")

                        LOG.debug("Starting telemetry output to {}", outputFile)

                        currentActivity?.rawDetailsPath = outputFile.path

                        telemetryOutput = BufferedOutputStream(FileOutputStream(outputFile))
                        // Make sure this packet is also written
                        write(currentTimeMillis, packet)
                    } catch (e: Exception) {
                        LOG.error("Error starting telemetry file", e)
                    }
                }
            } else if (!command.motorOn && currentActivity != null) {
                stop()
            }
        }
    }

    private fun write(currentTimeMillis: Long, packet: DumlPacket) {
        telemetryOutput?.let {
            synchronized(it) {
                it.write(BLETypeConversions.fromUint64(currentTimeMillis))
                it.write(DumlCodec.encode(packet))
            }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DjiFlightRecorder::class.java)
    }
}
