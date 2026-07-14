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
import androidx.health.connect.client.records.metadata.Metadata
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectSupport
import java.time.Instant
import java.time.ZoneId

// Deterministic clientRecordId so re-emitting an overlapping window upserts instead of duplicating.
// version is the clientRecordVersion; HC keeps the highest on conflict (newVersion >= existing
// overwrites). Callers pass the sync run's wall-clock so a later run always outranks the value it
// previously wrote for a minute. It must never be the metric value: a downward correction would
// then carry a lower version and be silently ignored, freezing the minute at its stale maximum.
internal fun clientRecordMetadata(
    base: Metadata,
    type: String,
    idKey: Long,
    version: Long
): Metadata {
    val device = base.device ?: return base
    val id = "gb-$type-${device.manufacturer ?: "unknown"}-${device.model ?: "unknown"}-$idKey"
    return when (base.recordingMethod) {
        Metadata.RECORDING_METHOD_ACTIVELY_RECORDED -> Metadata.activelyRecorded(device, id, version)
        else -> Metadata.autoRecorded(device, id, version)
    }
}

/**
 * Statistics returned by a syncer after processing a slice.
 */
data class SyncerStatistics(
    val recordsSynced: Int = 0,
    val recordsSkipped: Int = 0,
    val recordType: String = "",
    val latestRecordTimestamp: Instant? = null
)

/**
 * Per-night Health Connect record identity, carried across the slices of one data type and
 * persisted once at the end. [SleepSyncer] reads and rewrites it in place, so it is mutable state
 * on the context rather than a value threaded through the return type.
 */
internal class SleepRowRegistry(var rows: List<SleepSessionRow>)

/**
 * Everything a syncer needs for one slice of one device.
 *
 * [support] is the only way to reach Health Connect: a syncer never sees a `HealthConnectClient`,
 * so the toggle, the quota and the retry policy cannot be bypassed by accident.
 */
internal data class SyncContext(
    val support: HealthConnectSupport,
    val androidContext: Context,
    val gbDevice: GBDevice,
    val metadata: Metadata,
    val zoneId: ZoneId,
    val sliceStart: Instant,
    val sliceEnd: Instant,
    val grantedPermissions: Set<String>,
    /** Pre-fetched by the manager for ACTIVITY and SLEEP; empty for every other data type. */
    val activitySamples: List<ActivitySample> = emptyList(),
    val sleepRows: SleepRowRegistry = SleepRowRegistry(emptyList())
) {
    val deviceName: String get() = gbDevice.aliasOrName
}

/**
 * Writes one Gadgetbridge data type to Health Connect for one slice.
 *
 * One interface for all of them: the earlier split into activity-sample, contextual and plain
 * syncers forced the orchestrator to branch per syncer kind, and the two syncers that fitted
 * none of the three simply grew their own signatures.
 */
internal interface HealthConnectSyncer {
    suspend fun sync(ctx: SyncContext): SyncerStatistics
}
