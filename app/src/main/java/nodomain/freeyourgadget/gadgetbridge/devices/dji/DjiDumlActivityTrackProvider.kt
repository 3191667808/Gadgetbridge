package nodomain.freeyourgadget.gadgetbridge.devices.dji

import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrackProvider
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLETypeConversions
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.DecodeResult
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.DumlCodec
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.DumlCommand
import nodomain.freeyourgadget.gadgetbridge.service.devices.dji.duml.messages.Flyc
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils
import org.slf4j.LoggerFactory
import java.util.Date
import kotlin.math.hypot

class DjiDumlActivityTrackProvider : ActivityTrackProvider {
    override fun getActivityTrack(summary: BaseActivitySummary): ActivityTrack? {
        val file = FileUtils.tryFixPath(summary.rawDetailsPath)
        if (file == null) {
            LOG.debug("Telemetry file not found in {}", summary.rawDetailsPath)
            return null
        }

        LOG.debug("Loading telemetry file from {}", file)

        val data = try {
            file.readBytes()
        } catch (e: Exception) {
            LOG.error("Failed to read telemetry file", e)
            return null
        }

        val activityTrack = ActivityTrack()
        activityTrack.name = file.name
        activityTrack.baseTime = summary.startTime

        var offset = 0
        while (offset < data.size) {
            val timeMillis = BLETypeConversions.toUint64(data, offset)
            offset += 8

            when (val result = DumlCodec.decodeOne(data, offset)) {
                is DecodeResult.Success -> {
                    val command = DumlCommand.decode(result.content)
                    if (command is Flyc.OsdGeneral.Request) {
                        val point = ActivityPoint(Date(timeMillis))

                        val latitude = Math.toDegrees(command.latitude)
                        val longitude = Math.toDegrees(command.longitude)
                        if (latitude != 0.0 || longitude != 0.0) {
                            point.location = GPSCoordinate(longitude, latitude, command.relativeHeight.toDouble())
                        }

                        point.speed = hypot(command.vgx.toDouble(), command.vgy.toDouble()).toFloat()

                        activityTrack.addTrackPoint(point)
                    }
                    offset += result.bytesConsumed
                }

                is DecodeResult.Invalid -> {
                    LOG.warn("Invalid DUML frame at offset {}: {}", offset, result.reason)
                    offset++
                }

                is DecodeResult.NeedMoreData -> {
                    // Should never happen unless the stream got cut-off?
                    LOG.warn("Partial DUML frame at offset {}", offset)
                    break
                }
            }
        }

        return activityTrack
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DjiDumlActivityTrackProvider::class.java)
    }
}
