/*  Copyright (C) 2026 The Gadgetbridge Project

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

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.DerivedHealthMetrics
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectUtils
import nodomain.freeyourgadget.gadgetbridge.util.sensorcontext.PhoneSensorContext
import nodomain.freeyourgadget.gadgetbridge.util.sensorcontext.TimestampedDouble
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import kotlin.reflect.KClass

internal object FloorsClimbedSyncer : HealthConnectSyncer {
    private val logger = LoggerFactory.getLogger(FloorsClimbedSyncer::class.java)
    private val recordClass: KClass<FloorsClimbedRecord> = FloorsClimbedRecord::class

    override suspend fun sync(
        healthConnectClient: HealthConnectClient,
        gbDevice: GBDevice,
        metadata: Metadata,
        offset: ZoneId,
        sliceStartBoundary: Instant,
        sliceEndBoundary: Instant,
        grantedPermissions: Set<String>
    ): SyncerStatistics {
        val recordTypeName = recordClass.simpleName ?: "FloorsClimbedRecord"
        if (HealthPermission.getWritePermission(recordClass) !in grantedPermissions) {
            logger.info("Skipping FloorsClimbed sync; Health Connect permission not granted.")
            return SyncerStatistics(recordType = recordTypeName)
        }

        val records = buildRecords(
            pressureSeries = PhoneSensorContext.snapshot().pressureSeries,
            sliceStartBoundary = sliceStartBoundary,
            sliceEndBoundary = sliceEndBoundary,
            offset = offset,
            metadata = metadata
        )
        if (records.isEmpty()) {
            return SyncerStatistics(recordType = recordTypeName)
        }

        HealthConnectUtils.insertRecords(records, healthConnectClient)
        return SyncerStatistics(
            recordsSynced = records.size,
            recordType = recordTypeName,
            latestRecordTimestamp = sliceEndBoundary
        )
    }

    internal fun buildRecords(
        pressureSeries: Collection<TimestampedDouble>,
        sliceStartBoundary: Instant,
        sliceEndBoundary: Instant,
        offset: ZoneId,
        metadata: Metadata
    ): List<FloorsClimbedRecord> {
        val startMs = sliceStartBoundary.toEpochMilli()
        val endMs = sliceEndBoundary.toEpochMilli()
        val values = pressureSeries
            .asSequence()
            .filter { it.tsMs in startMs..endMs }
            .sortedBy { it.tsMs }
            .map { it.value }
            .toList()

        if (values.size < MIN_PRESSURE_SAMPLES) {
            return emptyList()
        }

        val floors = DerivedHealthMetrics.floorsFromPressureSeries(values.toDoubleArray())
        if (floors <= 0) {
            return emptyList()
        }

        return listOf(
            FloorsClimbedRecord(
                startTime = sliceStartBoundary,
                startZoneOffset = offset.rules.getOffset(sliceStartBoundary),
                endTime = sliceEndBoundary,
                endZoneOffset = offset.rules.getOffset(sliceEndBoundary),
                floors = floors.toDouble(),
                metadata = metadata
            )
        )
    }

    private const val MIN_PRESSURE_SAMPLES = 3
}
