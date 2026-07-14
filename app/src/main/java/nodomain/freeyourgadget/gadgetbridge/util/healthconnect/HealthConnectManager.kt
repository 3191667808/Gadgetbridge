/*  Copyright (C) 2025 LLan, Gideon Zenz

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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.GlucoseSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSleepSession
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSleepSessionDao
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSyncState
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSyncStateDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectPermissionManager.HealthConnectDataType
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.ActiveCaloriesSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.BloodGlucoseSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.DEFAULT_LATE_SAMPLE_LOOKBACK
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.DistanceSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.HealthConnectSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.HeartRateSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.HrvSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.RecordedWorkoutSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.RespiratoryRateSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.RestingHeartRateSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.SleepRowRegistry
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.SleepSessionRow
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.SleepSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.Spo2Syncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.StepsSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.SyncContext
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.SyncerStatistics
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.TemperatureSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.TotalCaloriesSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.Vo2MaxSyncer
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.syncers.WeightSyncer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

data class SyncStatistics(
    val success: Boolean,
    val dataTypesProcessed: Int,
    val dataTypesSkipped: Int,
    val recordsSyncedByType: Map<String, Int>, // data type name -> count of records synced
    val dataTypesWithErrors: Set<String> = emptySet(), // data types that had errors
    val rateLimited: Boolean = false
)

/**
 * Gadgetbridge's entire Health Connect boundary.
 *
 * This is the only class outside the package that anything may talk to, and the only place a
 * [HealthConnectClient] is ever created. Everything a write has to respect - the user's toggles,
 * the app's Health Connect quota, cancellation, the platform's size limits - is enforced once, in
 * [HealthConnectSupport], which the syncers reach through their [SyncContext]. A syncer cannot get
 * at the client directly, so it cannot get around any of it.
 */
object HealthConnectManager {
    private val LOG = LoggerFactory.getLogger(HealthConnectManager::class.java)
    private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")

    private const val LOOK_BACK_SECONDS: Long = 24 * 60 * 60
    private const val SLEEP_LOOK_FORWARD_SECONDS: Long = 12 * 60 * 60
    private const val DEFAULT_SLICE_SECONDS: Long = 24 * 60 * 60

    // Floor the sync-start at 2015 (Gadgetbridge predates it). A bogus near-epoch sample
    // timestamp otherwise resolves the start to ~1970 and triggers a full historical resync.
    private const val MIN_VALID_SAMPLE_SECONDS = 1420070400L // 2015-01-01T00:00:00Z
    private const val MIN_VALID_SAMPLE_MILLIS = MIN_VALID_SAMPLE_SECONDS * 1000

    // ---------------------------------------------------------------- availability & permissions

    @JvmStatic
    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    @JvmStatic
    fun checkAndRectifyPermissions(context: Context) =
        HealthConnectPermissionManager.checkAndRectifyPermissions(context)

    // ---------------------------------------------------------------------------------- the sync

    /**
     * Writes everything not yet written for [deviceAddress], or for every selected device when it
     * is null.
     *
     * A plain suspend function: the caller's coroutine scope owns it, and cancelling that scope
     * cancels the sync at the next chunk. Progress arrives through [onProgress] as
     * `(message, inProgress)`.
     */
    suspend fun sync(
        context: Context,
        deviceAddress: String? = null,
        onProgress: (String, Boolean) -> Unit = { _, _ -> }
    ): SyncStatistics {
        updateSyncStatus(context.getString(R.string.health_connect_syncing), true, onProgress)

        val client = HealthConnectClientProvider.healthConnectInit(context)
        if (client == null) {
            LOG.error("$HC_SYNC_TAG Health Connect client unavailable; nothing to sync to.")
            updateSyncStatus(context.getString(R.string.health_connect_failed_init), false, onProgress)
            return SyncStatistics(false, 0, 0, emptyMap())
        }
        val support = HealthConnectSupport({ client }, { HealthConnectSyncGate.isEnabled })

        val prefs = GBApplication.getPrefs()
        val grantedPermissions =
            prefs.preferences.getStringSet(GBPrefs.HEALTH_CONNECT_LAST_GRANTED_PERMISSIONS, emptySet())
                ?: emptySet()

        val selectedDevices = if (!deviceAddress.isNullOrEmpty()) {
            if (HealthConnectSyncGate.isDeviceEnabled(deviceAddress)) {
                setOf(deviceAddress)
            } else {
                LOG.error("$HC_SYNC_TAG Refusing to sync {}: not selected for Health Connect.", deviceAddress)
                emptySet()
            }
        } else {
            HealthConnectSyncGate.selectedDevices()
        }

        if (selectedDevices.isEmpty()) {
            updateSyncStatus(context.getString(R.string.health_connect_no_devices_selected), false, onProgress)
            return SyncStatistics(false, 0, 0, emptyMap())
        }
        if (grantedPermissions.isEmpty()) {
            updateSyncStatus(context.getString(R.string.health_connect_no_permissions), false, onProgress)
            return SyncStatistics(false, 0, 0, emptyMap())
        }

        return try {
            val stats = syncDevices(context, support, selectedDevices, grantedPermissions, onProgress)
            val message = buildSyncCompletionMessage(context, stats)
            LOG.info("$HC_SYNC_TAG Final sync status: {}", message)
            updateSyncStatus(message, false, onProgress)
            stats
        } catch (e: HealthConnectRateLimitException) {
            val minutes = (e.retryAfterMillis / 60_000L).coerceAtLeast(1L)
            LOG.warn("$HC_SYNC_TAG Health Connect quota exhausted; retrying in about {} minute(s).", minutes)
            updateSyncStatus(context.getString(R.string.health_connect_rate_limited, minutes), false, onProgress)
            SyncStatistics(false, 0, 0, emptyMap(), rateLimited = true)
        } catch (e: HealthConnectSyncDisabledException) {
            LOG.info("$HC_SYNC_TAG Health Connect was turned off mid-sync; stopping.")
            updateSyncStatus(context.getString(R.string.health_connect_disabled_mid_sync), false, onProgress)
            SyncStatistics(false, 0, 0, emptyMap())
        } catch (e: SyncException) {
            LOG.error("$HC_SYNC_TAG Sync failed: {}", e.message, e)
            if (e.cause is SecurityException) {
                // A permission went away underneath us; reconcile so the UI can prompt.
                HealthConnectPermissionManager.checkPermissionChange(context)
            }
            updateSyncStatus(e.message ?: context.getString(R.string.health_connect_sync_failed), false, onProgress)
            SyncStatistics(false, 0, 0, emptyMap())
        }
    }

    private suspend fun syncDevices(
        context: Context,
        support: HealthConnectSupport,
        selectedDevices: Set<String>,
        grantedPermissions: Set<String>,
        onProgress: (String, Boolean) -> Unit
    ): SyncStatistics {
        var totalDataTypesProcessed = 0
        var totalDataTypesSkipped = 0
        val recordsSyncedByType = mutableMapOf<String, Int>()
        val dataTypesWithErrors = mutableSetOf<String>()

        val zoneId = TimeZone.getDefault().toZoneId()
        val manager = GBApplication.app().deviceManager

        for (targetAddress in selectedDevices) {
            currentCoroutineContext().ensureActive()

            val gbDevice = manager.getDeviceByAddress(targetAddress)
            if (gbDevice == null) {
                LOG.error("$HC_SYNC_TAG Device for address {} not found during sync", targetAddress)
                continue
            }

            LOG.info("$HC_SYNC_TAG Starting sync for device: {}", gbDevice.aliasOrName)

            val deviceCoordinator = gbDevice.deviceCoordinator
            val hcDevice = healthConnectDevice(context, gbDevice, deviceCoordinator)

            dataTypeLoop@ for (dataType in HealthConnectDataType.entries) {
                currentCoroutineContext().ensureActive()

                val permsNeeded = HealthConnectPermissionManager.getRequiredPermissionsForDataType(dataType)
                if (permsNeeded.none { it in grantedPermissions }) {
                    LOG.info(
                        "$HC_SYNC_TAG Skipping {} on {}: no required Health Connect permission granted.",
                        dataType.name, gbDevice.aliasOrName
                    )
                    totalDataTypesSkipped++
                    continue@dataTypeLoop
                }

                val range = try {
                    getSyncTimestampRange(context, gbDevice, deviceCoordinator, dataType)
                } catch (e: Exception) {
                    LOG.error("$HC_SYNC_TAG Error determining sync range for {} on {}", dataType.name, gbDevice.aliasOrName, e)
                    dataTypesWithErrors.add(dataType.name)
                    totalDataTypesSkipped++
                    continue@dataTypeLoop
                }

                if (range == null) {
                    LOG.info("$HC_SYNC_TAG No sync range for {} on {}. Skipping.", dataType.name, gbDevice.aliasOrName)
                    totalDataTypesSkipped++
                    continue@dataTypeLoop
                }

                val (rangeStart, rangeEnd) = range
                if (!rangeEnd.isAfter(rangeStart)) {
                    LOG.info("$HC_SYNC_TAG Nothing new for {} on {} ({} to {}).", dataType.name, gbDevice.aliasOrName, rangeStart, rangeEnd)
                    totalDataTypesSkipped++
                    continue@dataTypeLoop
                }

                LOG.info("$HC_SYNC_TAG Syncing {} on {} from {} to {}", dataType.name, gbDevice.aliasOrName, rangeStart, rangeEnd)
                totalDataTypesProcessed++

                val metadata = when (dataType) {
                    HealthConnectDataType.ACTIVITY -> Metadata.activelyRecorded(hcDevice)
                    else -> Metadata.autoRecorded(hcDevice)
                }

                val isSleep = dataType == HealthConnectDataType.SLEEP
                // Sleep carries a per-night registry (frozen clientRecordId + grown span) across
                // slices, persisted once at the end, to decouple HC record identity from the
                // unstable session start.
                val sleepRows = SleepRowRegistry(if (isSleep) loadSleepRows(gbDevice) else emptyList())

                var cursor = rangeStart
                var sliceStart = rangeStart

                while (sliceStart.isBefore(rangeEnd)) {
                    currentCoroutineContext().ensureActive()

                    val sliceEnd = minOf(nextSliceEnd(dataType, sliceStart, zoneId), rangeEnd)
                    if (!sliceEnd.isAfter(sliceStart)) {
                        LOG.warn("$HC_SYNC_TAG Degenerate slice for {}({}): {} to {}", gbDevice.aliasOrName, dataType.name, sliceStart, sliceEnd)
                        break
                    }

                    updateSyncStatus(
                        context.getString(
                            R.string.health_connect_syncing_device_datatype,
                            gbDevice.aliasOrName,
                            dataType.name,
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(zoneId).format(sliceStart),
                            DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(zoneId).format(sliceEnd)
                        ),
                        true,
                        onProgress
                    )

                    val activitySamples = fetchActivitySamples(dataType, gbDevice, sliceStart, sliceEnd, zoneId)

                    val ctx = SyncContext(
                        support = support,
                        androidContext = context,
                        gbDevice = gbDevice,
                        metadata = metadata,
                        zoneId = zoneId,
                        sliceStart = sliceStart,
                        sliceEnd = sliceEnd,
                        grantedPermissions = grantedPermissions,
                        activitySamples = activitySamples,
                        sleepRows = sleepRows
                    )

                    try {
                        val sliceStats = syncersFor(dataType, gbDevice).map { it.sync(ctx) }

                        for (stat in sliceStats) {
                            recordsSyncedByType[stat.recordType] =
                                recordsSyncedByType.getOrDefault(stat.recordType, 0) + stat.recordsSynced
                        }

                        val latest = sliceStats.mapNotNull { it.latestRecordTimestamp }.maxOrNull()
                        if (latest != null && latest.isAfter(cursor)) {
                            cursor = latest
                        }

                        sliceStart = sliceEnd
                    } catch (e: SyncException) {
                        LOG.warn(
                            "$HC_SYNC_TAG Slice failed for {}({}) from {} to {}; skipping the rest of this data type. Reason: {}",
                            gbDevice.aliasOrName, dataType.name, sliceStart, sliceEnd, e.message
                        )
                        dataTypesWithErrors.add(dataType.name)
                        updateSyncStatus(
                            context.getString(
                                R.string.health_connect_sync_error_for_device,
                                gbDevice.aliasOrName, dataType.name, e.message ?: ""
                            ),
                            false,
                            onProgress
                        )
                        break
                    }
                }

                persistCursor(gbDevice, dataType, cursor, dataTypesWithErrors)

                if (isSleep) {
                    // Prune rows unreachable by the next run's scan (end < cursor - lookBack), persist.
                    val pruned = SleepSyncer.pruneSleepRows(sleepRows.rows, cursor.minusSeconds(LOOK_BACK_SECONDS))
                    try {
                        persistSleepRows(gbDevice, pruned)
                    } catch (e: Exception) {
                        LOG.error("$HC_SYNC_TAG Error persisting sleep sessions for {}", gbDevice.aliasOrName, e)
                        dataTypesWithErrors.add(dataType.name)
                    }
                }
            }
        }

        val success = totalDataTypesProcessed > 0 || totalDataTypesSkipped > 0
        LOG.info(
            "$HC_SYNC_TAG Sync complete - success: {}, processed: {}, skipped: {}, errors: {}, records: {}",
            success, totalDataTypesProcessed, totalDataTypesSkipped, dataTypesWithErrors, recordsSyncedByType
        )

        return SyncStatistics(
            success = success,
            dataTypesProcessed = totalDataTypesProcessed,
            dataTypesSkipped = totalDataTypesSkipped,
            recordsSyncedByType = recordsSyncedByType,
            dataTypesWithErrors = dataTypesWithErrors
        )
    }

    private fun syncersFor(dataType: HealthConnectDataType, gbDevice: GBDevice): List<HealthConnectSyncer> {
        val coordinator = gbDevice.deviceCoordinator
        return when (dataType) {
            HealthConnectDataType.ACTIVITY -> buildList {
                add(StepsSyncer)
                add(HeartRateSyncer)
                if (coordinator.supportsActiveCalories(gbDevice)) {
                    add(ActiveCaloriesSyncer)
                    // Google Health reads only TOTAL_CALORIES_BURNED and ignores the active figure,
                    // so writing active alone leaves it showing a flat formula estimate. Write both -
                    // but only for a device that actually measures calories, rather than inventing a
                    // basal-rate-only figure for one that does not.
                    add(TotalCaloriesSyncer)
                }
                if (coordinator.supportsActivityDistance(gbDevice)) {
                    add(DistanceSyncer)
                }
            }

            HealthConnectDataType.SLEEP -> listOf(SleepSyncer)
            HealthConnectDataType.VO2MAX -> listOf(Vo2MaxSyncer)
            HealthConnectDataType.HRV -> listOf(HrvSyncer)
            HealthConnectDataType.WEIGHT -> listOf(WeightSyncer)
            HealthConnectDataType.SPO2 -> listOf(Spo2Syncer)
            HealthConnectDataType.TEMPERATURE -> listOf(TemperatureSyncer)
            HealthConnectDataType.RESPIRATORY_RATE -> listOf(RespiratoryRateSyncer)
            HealthConnectDataType.RESTING_HEART_RATE -> listOf(RestingHeartRateSyncer)
            HealthConnectDataType.BLOOD_GLUCOSE -> listOf(BloodGlucoseSyncer)
            HealthConnectDataType.WORKOUTS ->
                if (coordinator.supportsRecordedActivities(gbDevice)) listOf(RecordedWorkoutSyncer) else emptyList()
        }
    }

    // ------------------------------------------------------------------------------------ slices

    /**
     * Activity slices end at local midnight; everything else takes a flat 24 hours from wherever
     * the cursor happens to sit.
     *
     * The distinction matters because several sample providers store steps, distance and calories
     * cumulatively and convert them to per-minute deltas *relative to the window they are asked
     * for* (see AbstractSampleProvider.convertCumulativeSteps). Gadgetbridge's own charts always
     * ask from local midnight, so they get it right; a window starting at an arbitrary cursor and
     * straddling midnight differently on every run does not. Asking the same question the charts
     * ask is what makes Health Connect agree with them.
     */
    private fun nextSliceEnd(dataType: HealthConnectDataType, sliceStart: Instant, zoneId: ZoneId): Instant =
        if (dataType == HealthConnectDataType.ACTIVITY) {
            startOfNextLocalDay(sliceStart, zoneId)
        } else {
            sliceStart.plusSeconds(DEFAULT_SLICE_SECONDS)
        }

    private fun startOfLocalDay(instant: Instant, zoneId: ZoneId): Instant =
        ZonedDateTime.ofInstant(instant, zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()

    private fun startOfNextLocalDay(instant: Instant, zoneId: ZoneId): Instant =
        ZonedDateTime.ofInstant(instant, zoneId).toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant()

    private fun fetchActivitySamples(
        dataType: HealthConnectDataType,
        gbDevice: GBDevice,
        sliceStart: Instant,
        sliceEnd: Instant,
        zoneId: ZoneId
    ): List<ActivitySample> {
        if (dataType != HealthConnectDataType.ACTIVITY && dataType != HealthConnectDataType.SLEEP) {
            return emptyList()
        }

        val queryStart: Instant
        val queryEnd: Instant
        if (dataType == HealthConnectDataType.SLEEP) {
            // A night straddles midnight in both directions; give SleepAnalysis room either side.
            queryStart = sliceStart.minusSeconds(LOOK_BACK_SECONDS)
            queryEnd = sliceEnd.plusSeconds(SLEEP_LOOK_FORWARD_SECONDS)
        } else {
            // From local midnight, so the cumulative-to-delta conversion sees the same window the
            // charts do; never less than the syncers' late-sample look-back, or there would be
            // nothing for that look-back to recover.
            queryStart = minOf(
                startOfLocalDay(sliceStart, zoneId),
                sliceStart.minus(DEFAULT_LATE_SAMPLE_LOOKBACK)
            )
            queryEnd = sliceEnd
        }

        LOG.info(
            "$HC_SYNC_TAG Querying Gadgetbridge DB for {}({}) from {} to {}",
            gbDevice.aliasOrName, dataType.name, queryStart, queryEnd
        )

        val samples = GBApplication.acquireDbReadOnly().use { db ->
            getActivitySamples(db, gbDevice, queryStart.epochSecond.toInt(), queryEnd.epochSecond.toInt())
        }

        if (dataType == HealthConnectDataType.ACTIVITY) {
            logStepTotal(gbDevice, samples, sliceStart, sliceEnd)
        }

        return samples
    }

    /**
     * The number Gadgetbridge itself would show for this window. Step counts reaching Health
     * Connect have diverged from the app's own charts before; logging the source total makes the
     * next such report answerable from a log file instead of a debugging session.
     */
    private fun logStepTotal(
        gbDevice: GBDevice,
        samples: List<ActivitySample>,
        sliceStart: Instant,
        sliceEnd: Instant
    ) {
        val steps = samples
            .filter {
                val ts = it.timestamp.toLong()
                ts >= sliceStart.epochSecond && ts <= sliceEnd.epochSecond
            }
            .sumOf { if (it.steps > 0) it.steps else 0 }
        LOG.info(
            "$HC_SYNC_TAG Gadgetbridge reports {} step(s) for {} between {} and {}; Health Connect should show the same.",
            steps, gbDevice.aliasOrName, sliceStart, sliceEnd
        )
    }

    // -------------------------------------------------------------------------------- persistence

    private fun persistCursor(
        gbDevice: GBDevice,
        dataType: HealthConnectDataType,
        cursor: Instant,
        dataTypesWithErrors: MutableSet<String>
    ) {
        try {
            GBApplication.acquireDB().use { db ->
                val deviceFromDb = DBHelper.getDevice(gbDevice, db.daoSession)
                LOG.info(
                    "$HC_SYNC_TAG Updating sync state for {} ({}) to {}",
                    gbDevice.aliasOrName, dataType.name, cursor
                )
                db.daoSession.healthConnectSyncStateDao.insertOrReplace(
                    HealthConnectSyncState(deviceFromDb.id!!, dataType.name, cursor.epochSecond)
                )
            }
        } catch (e: Exception) {
            LOG.error("$HC_SYNC_TAG Error updating sync state for {} ({})", gbDevice.aliasOrName, dataType.name, e)
            dataTypesWithErrors.add(dataType.name)
        }
    }

    private fun loadSleepRows(gbDevice: GBDevice): List<SleepSessionRow> {
        return GBApplication.acquireDbReadOnly().use { db ->
            val deviceFromDb = DBHelper.getDevice(gbDevice, db.daoSession) ?: return@use emptyList()
            db.daoSession.healthConnectSleepSessionDao.queryBuilder()
                .where(HealthConnectSleepSessionDao.Properties.DeviceId.eq(deviceFromDb.id))
                .list()
                .map {
                    SleepSessionRow(
                        clientRecordId = it.clientRecordId,
                        startTime = Instant.ofEpochSecond(it.startTime),
                        endTime = Instant.ofEpochSecond(it.endTime)
                    )
                }
        }
    }

    private fun persistSleepRows(gbDevice: GBDevice, rows: List<SleepSessionRow>) {
        GBApplication.acquireDB().use { db ->
            val deviceFromDb = DBHelper.getDevice(gbDevice, db.daoSession) ?: return@use
            val dao = db.daoSession.healthConnectSleepSessionDao
            val entities = rows.map {
                HealthConnectSleepSession(
                    null,
                    deviceFromDb.id!!,
                    it.clientRecordId,
                    it.startTime.epochSecond,
                    it.endTime.epochSecond
                )
            }
            // Atomic replace: a failed insert must not leave the delete committed, else ids
            // re-mint next run and duplicate HC records.
            db.daoSession.runInTx {
                dao.queryBuilder()
                    .where(HealthConnectSleepSessionDao.Properties.DeviceId.eq(deviceFromDb.id))
                    .buildDelete()
                    .executeDeleteWithoutDetachingEntities()
                dao.insertInTx(entities)
            }
        }
    }

    // ------------------------------------------------------------------------------- sync windows

    private fun getSyncTimestampRange(
        context: Context,
        gbDevice: GBDevice,
        deviceCoordinator: DeviceCoordinator,
        dataType: HealthConnectDataType
    ): Pair<Instant, Instant>? {
        return GBApplication.acquireDbReadOnly().use { db ->
            val deviceFromDb = DBHelper.getDevice(gbDevice, db.daoSession)
            if (deviceFromDb == null) {
                LOG.error("$HC_SYNC_TAG Device not found in database for address: {}", gbDevice.address)
                return@use null
            }

            val syncState = db.daoSession.healthConnectSyncStateDao.queryBuilder()
                .where(
                    HealthConnectSyncStateDao.Properties.DeviceId.eq(deviceFromDb.id),
                    HealthConnectSyncStateDao.Properties.DataType.eq(dataType.name)
                )
                .unique()

            val startTs = getStartTimestamp(context, gbDevice, deviceCoordinator, db, dataType, syncState)
                ?: run {
                    LOG.info("$HC_SYNC_TAG No starting point for {} on {}.", dataType.name, gbDevice.aliasOrName)
                    return@use null
                }

            val endTs = getLastSampleTimestamp(deviceCoordinator, gbDevice, db, dataType)
                ?: run {
                    LOG.info("$HC_SYNC_TAG No latest sample for {} on {}.", dataType.name, gbDevice.aliasOrName)
                    return@use null
                }

            LOG.info("$HC_SYNC_TAG Determined sync range for {}({}): {} to {}", gbDevice.aliasOrName, dataType.name, startTs, endTs)
            Pair(startTs, endTs)
        }
    }

    private fun getStartTimestamp(
        context: Context,
        gbDevice: GBDevice,
        deviceCoordinator: DeviceCoordinator,
        db: DBHandler,
        dataType: HealthConnectDataType,
        syncState: HealthConnectSyncState?
    ): Instant? {
        if (syncState != null) {
            LOG.info(
                "$HC_SYNC_TAG Resuming {}({}) from {}",
                gbDevice.aliasOrName, dataType.name, Instant.ofEpochSecond(syncState.lastSyncTimestamp)
            )
            return Instant.ofEpochSecond(syncState.lastSyncTimestamp)
        }

        val initialSyncPrefs = context.getSharedPreferences(GBPrefs.HEALTH_CONNECT_SETTINGS, Context.MODE_PRIVATE)
        val initialSyncStartTs = initialSyncPrefs.getLong(GBPrefs.HEALTH_CONNECT_INITIAL_SYNC_START_TS, -1L)
        if (initialSyncStartTs != -1L) {
            LOG.info("$HC_SYNC_TAG Using initial sync start for {}({}): {}", gbDevice.aliasOrName, dataType.name, Instant.ofEpochSecond(initialSyncStartTs))
            return Instant.ofEpochSecond(initialSyncStartTs)
        }

        return getFirstSampleTimestamp(deviceCoordinator, gbDevice, db, dataType)
    }

    internal fun getActivitySamples(db: DBHandler, device: GBDevice, tsFrom: Int, tsTo: Int): List<ActivitySample> {
        val provider = device.deviceCoordinator.getSampleProvider(device, db.daoSession)
        if (provider == null) {
            LOG.error("$HC_SYNC_TAG getSampleProvider returned null for device {}", device.name)
            return emptyList()
        }
        return provider.getAllActivitySamples(tsFrom, tsTo)
    }

    private fun getFirstSampleTimestamp(
        deviceCoordinator: DeviceCoordinator,
        device: GBDevice,
        db: DBHandler,
        dataType: HealthConnectDataType
    ): Instant? {
        return when (val provider = getProviderForDataType(deviceCoordinator, device, db, dataType)) {
            is TimeSampleProvider<*> ->
                provider.firstSample?.timestamp?.takeIf { it > MIN_VALID_SAMPLE_MILLIS }?.let { Instant.ofEpochMilli(it) }

            is SampleProvider<*> ->
                provider.getFirstActivitySample(MIN_VALID_SAMPLE_SECONDS.toInt())?.timestamp
                    ?.takeIf { it > MIN_VALID_SAMPLE_SECONDS }
                    ?.let { Instant.ofEpochSecond(it.toLong()) }

            is BaseActivitySummaryDao -> {
                val deviceEntity = DBHelper.getDevice(device, db.daoSession) ?: return null
                db.daoSession.baseActivitySummaryDao?.queryBuilder()
                    ?.where(BaseActivitySummaryDao.Properties.DeviceId.eq(deviceEntity.id))
                    ?.orderAsc(BaseActivitySummaryDao.Properties.StartTime)
                    ?.limit(1)
                    ?.list()
                    ?.firstOrNull()
                    ?.startTime?.toInstant()
            }

            else -> {
                LOG.error("$HC_SYNC_TAG No provider for first-sample lookup, dataType {}, device {}", dataType, device.name)
                null
            }
        }
    }

    private fun getLastSampleTimestamp(
        deviceCoordinator: DeviceCoordinator,
        device: GBDevice,
        db: DBHandler,
        dataType: HealthConnectDataType
    ): Instant? {
        return when (val provider = getProviderForDataType(deviceCoordinator, device, db, dataType)) {
            is TimeSampleProvider<*> ->
                provider.latestSample?.timestamp?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }

            is SampleProvider<*> ->
                provider.latestActivitySample?.timestamp?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it.toLong()) }

            is BaseActivitySummaryDao -> {
                val deviceEntity = DBHelper.getDevice(device, db.daoSession) ?: return null
                db.daoSession.baseActivitySummaryDao?.queryBuilder()
                    ?.where(BaseActivitySummaryDao.Properties.DeviceId.eq(deviceEntity.id))
                    ?.orderDesc(BaseActivitySummaryDao.Properties.EndTime)
                    ?.limit(1)
                    ?.list()
                    ?.firstOrNull()
                    ?.endTime?.toInstant()
            }

            else -> {
                LOG.error("$HC_SYNC_TAG No provider for last-sample lookup, dataType {}, device {}", dataType, device.name)
                null
            }
        }
    }

    private fun getProviderForDataType(
        coordinator: DeviceCoordinator,
        device: GBDevice,
        db: DBHandler,
        dataType: HealthConnectDataType
    ): Any? {
        return when (dataType) {
            HealthConnectDataType.ACTIVITY, HealthConnectDataType.SLEEP -> coordinator.getSampleProvider(device, db.daoSession)
            HealthConnectDataType.VO2MAX -> coordinator.getVo2MaxSampleProvider(device, db.daoSession)
            HealthConnectDataType.HRV -> coordinator.getHrvValueSampleProvider(device, db.daoSession)
            HealthConnectDataType.RESPIRATORY_RATE -> coordinator.getRespiratoryRateSampleProvider(device, db.daoSession)
            HealthConnectDataType.RESTING_HEART_RATE -> coordinator.getHeartRateRestingSampleProvider(device, db.daoSession)
            HealthConnectDataType.BLOOD_GLUCOSE -> GlucoseSampleProvider(device, db.daoSession)
            HealthConnectDataType.WEIGHT -> coordinator.getWeightSampleProvider(device, db.daoSession)
            HealthConnectDataType.SPO2 -> coordinator.getSpo2SampleProvider(device, db.daoSession)
            HealthConnectDataType.TEMPERATURE -> coordinator.getTemperatureSampleProvider(device, db.daoSession)
            HealthConnectDataType.WORKOUTS -> db.daoSession.baseActivitySummaryDao
        }
    }

    // ------------------------------------------------------------------------------------ origins

    private fun healthConnectDevice(
        context: Context,
        gbDevice: GBDevice,
        coordinator: DeviceCoordinator
    ): Device {
        val manufacturer = coordinator.manufacturer
        var deviceName = context.getString(coordinator.deviceNameResource)
        if (deviceName.startsWith(manufacturer) && deviceName != manufacturer) {
            deviceName = deviceName.replace(manufacturer, "").trim()
        }
        return Device(
            type = when (coordinator.getDeviceKind(gbDevice)) {
                DeviceCoordinator.DeviceKind.WATCH -> Device.TYPE_WATCH
                DeviceCoordinator.DeviceKind.PHONE -> Device.TYPE_PHONE
                DeviceCoordinator.DeviceKind.SCALE -> Device.TYPE_SCALE
                DeviceCoordinator.DeviceKind.RING -> Device.TYPE_RING
                DeviceCoordinator.DeviceKind.HEAD_MOUNTED -> Device.TYPE_HEAD_MOUNTED
                DeviceCoordinator.DeviceKind.FITNESS_BAND -> Device.TYPE_FITNESS_BAND
                DeviceCoordinator.DeviceKind.CHEST_STRAP -> Device.TYPE_CHEST_STRAP
                DeviceCoordinator.DeviceKind.SMART_DISPLAY -> Device.TYPE_SMART_DISPLAY
                else -> Device.TYPE_UNKNOWN
            },
            manufacturer = manufacturer,
            model = deviceName
        )
    }

    // ------------------------------------------------------------------------------------- status

    private fun updateSyncStatus(message: String, inProgress: Boolean, onProgress: (String, Boolean) -> Unit) {
        val summary = TIME_FORMATTER.format(ZonedDateTime.now()) + ": " + message
        GBApplication.getPrefs().preferences.edit {
            putString(GBPrefs.HEALTH_CONNECT_SYNC_STATUS, summary)
        }
        onProgress(summary, inProgress)
    }

    private fun buildSyncCompletionMessage(context: Context, stats: SyncStatistics): String {
        if (!stats.success) {
            val errorDetails = if (stats.dataTypesWithErrors.isNotEmpty()) {
                " (${stats.dataTypesWithErrors.joinToString(", ")})"
            } else {
                ""
            }
            return context.getString(R.string.health_connect_finished_with_errors) + errorDetails
        }

        if (stats.dataTypesProcessed == 0) {
            return context.getString(R.string.health_connect_finished_no_data)
        }

        val details = if (stats.recordsSyncedByType.isNotEmpty()) {
            stats.recordsSyncedByType.entries
                .sortedByDescending { it.value }
                .joinToString(", ") { "${it.key}: ${it.value}" }
        } else {
            ""
        }

        return if (stats.dataTypesWithErrors.isNotEmpty()) {
            val errorList = stats.dataTypesWithErrors.joinToString(", ")
            if (details.isNotEmpty()) {
                context.getString(R.string.health_connect_finished_with_stats_and_errors, details, errorList)
            } else {
                context.getString(R.string.health_connect_finished_with_errors)
            }
        } else {
            if (details.isNotEmpty()) {
                context.getString(R.string.health_connect_finished_with_stats, details)
            } else {
                context.getString(R.string.health_connect_finished)
            }
        }
    }

    private const val HC_SYNC_TAG = "[HC_SYNC]"
}
