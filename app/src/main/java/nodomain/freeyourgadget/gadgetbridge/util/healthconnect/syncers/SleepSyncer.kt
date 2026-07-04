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

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.model.sleep.CorrectedSleepSession
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepSessionService
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepStage
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectUtils
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val LOG = LoggerFactory.getLogger("SleepSyncer")

private const val IN_PROGRESS_THRESHOLD_HOURS = 6L

internal fun sleepClientRecordMetadata(
    base: Metadata,
    sleepSession: CorrectedSleepSession,
    recordFinalStartTime: Instant,
    recordVersion: Long
): Metadata {
    val recordIdTimestamp = if (sleepSession.isEdited && sleepSession.sourceStartTs > 0) {
        sleepSession.sourceStartTs
    } else {
        recordFinalStartTime.epochSecond
    }
    val startHourEpoch = recordIdTimestamp / 3600 * 3600
    return clientRecordMetadata(base, "sleep", startHourEpoch, recordVersion.coerceAtLeast(1L))
}

internal object SleepSyncer : ContextualActivitySampleSyncer {

    override suspend fun sync(
        healthConnectClient: HealthConnectClient,
        gbDevice: GBDevice,
        metadata: Metadata,
        offset: ZoneId,
        sliceStartBoundary: Instant,
        sliceEndBoundary: Instant,
        grantedPermissions: Set<String>,
        deviceSamples: List<ActivitySample>,
        context: Context
    ): SyncerStatistics {

        val deviceName = gbDevice.aliasOrName


        if (HealthPermission.getWritePermission(SleepSessionRecord::class) !in grantedPermissions) {
            LOG.info("Skipping Sleep sync for device '$deviceName'; SleepSessionRecord permission not granted.")
            return SyncerStatistics(recordType = "Sleep")
        }

        val sortedDeviceSamples = deviceSamples.sortedBy { it.timestamp }

        val allIdentifiedSessions = GBApplication.acquireDbReadOnly().use { db ->
            SleepSessionService.getSessions(
                db.daoSession,
                gbDevice,
                sortedDeviceSamples,
                sliceStartBoundary.epochSecond.toInt(),
                sliceEndBoundary.epochSecond.toInt()
            )
        }

        if (allIdentifiedSessions.isEmpty()) {
            LOG.info("No corrected sleep sessions identified for device '$deviceName' for slice $sliceStartBoundary to $sliceEndBoundary.")
            return SyncerStatistics(recordType = "Sleep")
        }

        LOG.info("Identified ${allIdentifiedSessions.size} corrected sleep sessions for device '$deviceName'. Filtering by slice: $sliceStartBoundary to $sliceEndBoundary.")

        val recordVersion = System.currentTimeMillis()

        // Convert all sessions to records, filtering out invalid ones
        val sleepSessionRecordList = allIdentifiedSessions.mapNotNull { sleepSession ->
            sleepSessionToRecord(
                sleepSession = sleepSession,
                sliceStartBoundary = sliceStartBoundary,
                sliceEndBoundary = sliceEndBoundary,
                offset = offset,
                metadata = metadata,
                recordVersion = recordVersion,
                context = context,
                deviceName = deviceName
            )
        }

        val skippedCount = allIdentifiedSessions.size - sleepSessionRecordList.size

        if (skippedCount > 0) {
            LOG.info("Skipped $skippedCount sleep session(s) for device '$deviceName' (outside slice, no samples, no valid stages, or invalid timings).")
        }

        LOG.info("Finished processing ${sleepSessionRecordList.size} valid sleep session(s) for device '$deviceName' for slice $sliceStartBoundary to $sliceEndBoundary.")

        if (sleepSessionRecordList.isEmpty()) {
            LOG.info("No valid SleepSessionRecord(s) created for device '$deviceName' for slice $sliceStartBoundary to $sliceEndBoundary.")
            return SyncerStatistics(recordType = "Sleep", recordsSkipped = skippedCount)
        }

        LOG.info("Attempting to insert ${sleepSessionRecordList.size} SleepSessionRecord(s) for device '$deviceName' for slice $sliceStartBoundary to $sliceEndBoundary.")
        HealthConnectUtils.insertRecords(sleepSessionRecordList, healthConnectClient)
        LOG.info("Successfully inserted SleepSessionRecord(s) for device '$deviceName' for slice $sliceStartBoundary to $sliceEndBoundary.")

        val now = Instant.now()
        var latestTs: Instant? = null
        for (record in sleepSessionRecordList) {
            val sessionEnd = record.endTime
            val sessionStart = record.startTime
            val effectiveTs = if (sessionEnd.isAfter(now.minus(IN_PROGRESS_THRESHOLD_HOURS, ChronoUnit.HOURS))) {
                LOG.info("Sleep session ending at $sessionEnd may still be in progress — holding cursor at $sessionStart for re-processing.")
                sessionStart.minusSeconds(1)
            } else {
                sessionEnd
            }
            if (latestTs == null || effectiveTs.isAfter(latestTs)) {
                latestTs = effectiveTs
            }
        }
        return SyncerStatistics(recordsSynced = sleepSessionRecordList.size, recordsSkipped = skippedCount, recordType = "Sleep", latestRecordTimestamp = latestTs)
    }

    /**
     * Converts a corrected sleep session to a SleepSessionRecord.
     * Returns null if the session is invalid or should be skipped (e.g., outside slice, no valid stages).
     */
    private fun sleepSessionToRecord(
        sleepSession: CorrectedSleepSession,
        sliceStartBoundary: Instant,
        sliceEndBoundary: Instant,
        offset: ZoneId,
        metadata: Metadata,
        recordVersion: Long,
        context: Context,
        deviceName: String
    ): SleepSessionRecord? {
        val sessionBoundaryStart = Instant.ofEpochSecond(sleepSession.startTs)
        val sessionBoundaryEnd = Instant.ofEpochSecond(sleepSession.endTs)

        // Only process this session if its START falls within the current slice [sliceStart, sliceEnd).
        // The look-back query ensures full session data is available even for sessions starting near the
        // previous slice boundary. The look-forward query ensures full session data for sessions starting
        // near the current slice end. This ownership rule prevents duplicate records when the same session
        // is discovered across multiple slices due to the look-back overlap.
        if (sessionBoundaryStart.isBefore(sliceStartBoundary) || !sessionBoundaryStart.isBefore(sliceEndBoundary)) {
            LOG.debug(
                "Skipping corrected sleep session for device '{}' (Timeframe: {} to {}) as its start does not fall within current slice [{} to {}).",
                deviceName,
                sessionBoundaryStart,
                sessionBoundaryEnd,
                sliceStartBoundary,
                sliceEndBoundary
            )
            return null
        }

        LOG.info("Processing corrected sleep session for device '$deviceName' (range: $sessionBoundaryStart to $sessionBoundaryEnd) as it overlaps with slice $sliceStartBoundary to $sliceEndBoundary.")

        val stages = buildSleepStages(sleepSession.stages, deviceName)

        if (stages.isEmpty()) {
            LOG.warn("No valid sleep stages derived for corrected session (range: $sessionBoundaryStart to $sessionBoundaryEnd) for device '$deviceName'. Skipping this session.")
            return null
        }

        val recordFinalStartTime = stages.first().startTime
        val recordFinalEndTime = stages.last().endTime

        if (!recordFinalEndTime.isAfter(recordFinalStartTime)) {
            LOG.warn("Skipping sleep session for device '$deviceName' due to invalid overall stage timings after processing (End: $recordFinalEndTime, Start: $recordFinalStartTime). Stages: ${stages.size}")
            return null
        }

        LOG.info("Prepared SleepSessionRecord for device '$deviceName' (Session: $recordFinalStartTime to $recordFinalEndTime). Stages: ${stages.size}")

        val sessionMetadata = sleepClientRecordMetadata(
            metadata,
            sleepSession,
            recordFinalStartTime,
            recordVersion
        )
        LOG.info("Sleep session clientRecordId=${sessionMetadata.clientRecordId}, clientRecordVersion=${sessionMetadata.clientRecordVersion}")

        return SleepSessionRecord(
            startTime = recordFinalStartTime,
            startZoneOffset = offset.rules.getOffset(recordFinalStartTime),
            endTime = recordFinalEndTime,
            endZoneOffset = offset.rules.getOffset(recordFinalEndTime),
            title = context.getString(nodomain.freeyourgadget.gadgetbridge.R.string.health_connect_sleep_session_title, deviceName),
            notes = context.getString(nodomain.freeyourgadget.gadgetbridge.R.string.health_connect_sleep_session_notes, deviceName),
            stages = stages,
            metadata = sessionMetadata
        )
    }

    /**
     * Builds sleep stages from corrected Gadgetbridge sleep stages.
     */
    private fun buildSleepStages(
        sleepStages: List<SleepStage>,
        deviceName: String
    ): List<SleepSessionRecord.Stage> {
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        for (sleepStage in sleepStages) {
            val stageType = mapActivityKindToSleepStage(sleepStage.kind)

            if (stageType == SleepSessionRecord.STAGE_TYPE_UNKNOWN) {
                continue
            }

            val stageStartTime = Instant.ofEpochSecond(sleepStage.startTs)
            val stageEndTime = Instant.ofEpochSecond(sleepStage.endTs)

            if (stageEndTime.isAfter(stageStartTime)) {
                stages.add(SleepSessionRecord.Stage(stageStartTime, stageEndTime, stageType))
            } else {
                LOG.trace(
                    "Skipping zero or negative duration stage for device '{}' at {} (type {}), proposed end {}.",
                    deviceName,
                    stageStartTime,
                    stageType,
                    stageEndTime
                )
            }
        }

        return stages
    }

    private fun mapActivityKindToSleepStage(activityKind: ActivityKind): Int {
        return when (activityKind) {
            ActivityKind.DEEP_SLEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
            ActivityKind.LIGHT_SLEEP -> SleepSessionRecord.STAGE_TYPE_LIGHT
            ActivityKind.REM_SLEEP -> SleepSessionRecord.STAGE_TYPE_REM
            ActivityKind.AWAKE_SLEEP -> SleepSessionRecord.STAGE_TYPE_AWAKE
            else -> SleepSessionRecord.STAGE_TYPE_UNKNOWN
        }
    }
}
