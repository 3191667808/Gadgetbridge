/*  Copyright (C) 2025 Gideon Zenz

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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectSupport
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

private val LOG = LoggerFactory.getLogger("HeartRateSyncer")

internal object HeartRateSyncer : HealthConnectSyncer {
    override suspend fun sync(ctx: SyncContext): SyncerStatistics {
        val deviceName = ctx.deviceName

        // 1. Permission Check
        if (HealthPermission.getWritePermission(HeartRateRecord::class) !in ctx.grantedPermissions) {
            LOG.info("Skipping Heart Rate sync for device '$deviceName'; HeartRateRecord permission not granted.")
            return SyncerStatistics(recordType = "HeartRate")
        }

        // 2. Relevant Input Data Check. HC enforces 1..300 bpm; 255 is GB's documented
        // "illegal value" sentinel (ActivitySample.getHeartRate) and lands inside that range.
        var droppedOutOfRange = 0
        val validHRSamples = ctx.activitySamples
            .filter {
                val inRange = it.heartRate in 1..300 && it.heartRate != 255
                // 0 means "not measured" - common, don't count as out-of-range
                if (!inRange && it.heartRate != 0) {
                    droppedOutOfRange++
                }
                inRange
            }
            .sortedBy { it.timestamp }
        if (droppedOutOfRange > 0) {
            LOG.info(
                "${HealthConnectSupport.HC_SYNC_TAG} Dropped {} out-of-range HeartRate sample(s) for device '{}' in slice {} to {} (HC requires 1..300 bpm, 255 excluded as bad-measurement sentinel).",
                droppedOutOfRange, deviceName, ctx.sliceStart, ctx.sliceEnd
            )
        }

        if (validHRSamples.isEmpty()) {
            LOG.info("No valid heart rate samples found for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
            return SyncerStatistics(recordType = "HeartRate")
        }

        LOG.info("Processing ${validHRSamples.size} valid heart rate samples for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")

        val heartRateRecordList = mutableListOf<Record>()
        val currentHcSamples = mutableListOf<HeartRateRecord.Sample>()
        var previousSampleTimestamp: Instant? = null
        var skippedCount = 0

        fun flush() {
            if (currentHcSamples.isEmpty()) {
                return
            }
            val recordStartTime = currentHcSamples.first().time
            var recordEndTime = currentHcSamples.last().time
            if (recordEndTime == recordStartTime) { // Ensure duration is positive for HC
                recordEndTime = recordEndTime.plusSeconds(1)
            }

            if (recordEndTime.isAfter(recordStartTime)) {
                LOG.debug(
                    "Creating HeartRateRecord for device '{}' from {} to {} with {} samples.",
                    deviceName, recordStartTime, recordEndTime, currentHcSamples.size
                )
                heartRateRecordList.add(
                    HeartRateRecord(
                        recordStartTime,
                        ctx.zoneId.rules.getOffset(recordStartTime),
                        recordEndTime,
                        ctx.zoneId.rules.getOffset(recordEndTime),
                        ArrayList(currentHcSamples),
                        ctx.metadata
                    )
                )
            } else {
                LOG.warn("Skipping HeartRateRecord for device '$deviceName' from $recordStartTime to $recordEndTime due to invalid duration even after adjustment.")
            }
            currentHcSamples.clear()
        }

        for (gbSample in validHRSamples) {
            val currentSampleTimestamp = Instant.ofEpochSecond(gbSample.timestamp.toLong())

            // Use inclusive boundaries [sliceStart, sliceEnd] for the slice
            if (currentSampleTimestamp.isBefore(ctx.sliceStart) || currentSampleTimestamp.isAfter(ctx.sliceEnd)) {
                skippedCount++
                continue
            }

            previousSampleTimestamp?.let { prevTs ->
                val newDay = ZonedDateTime.ofInstant(prevTs, ctx.zoneId).toLocalDate() !=
                        ZonedDateTime.ofInstant(currentSampleTimestamp, ctx.zoneId).toLocalDate()
                val gapTooLong = currentSampleTimestamp.epochSecond - prevTs.epochSecond > 15 * 60 // 15 min gap
                val samplesFull = currentHcSamples.size >= HealthConnectSupport.MAX_SAMPLES_PER_HEART_RATE_RECORD

                if (newDay || gapTooLong || samplesFull) {
                    flush()
                }
            }
            currentHcSamples.add(HeartRateRecord.Sample(currentSampleTimestamp, gbSample.heartRate.toLong()))
            previousSampleTimestamp = currentSampleTimestamp
        }

        flush()

        if (heartRateRecordList.isEmpty()) {
            LOG.info("No valid HeartRateRecord(s) created for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd} after processing ${validHRSamples.size} samples.")
            return SyncerStatistics(recordsSkipped = skippedCount, recordType = "HeartRate")
        }

        LOG.info("Attempting to insert ${heartRateRecordList.size} HeartRateRecord(s) for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
        ctx.support.insert(heartRateRecordList)

        LOG.info("Successfully inserted ${heartRateRecordList.size} HeartRateRecord(s) for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
        return SyncerStatistics(
            recordsSynced = heartRateRecordList.size,
            recordsSkipped = skippedCount,
            recordType = "HeartRate",
            latestRecordTimestamp = previousSampleTimestamp
        )
    }
}
