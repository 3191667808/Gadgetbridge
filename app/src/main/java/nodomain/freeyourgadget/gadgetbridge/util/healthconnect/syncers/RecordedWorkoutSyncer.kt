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

import android.annotation.SuppressLint
import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.permission.HealthPermission.Companion.PERMISSION_WRITE_EXERCISE_ROUTE
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Power
import androidx.health.connect.client.units.Velocity
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectAbortException
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectSyncGate
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.SyncException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

private val LOG = LoggerFactory.getLogger("RecordedWorkoutSyncer")

/**
 * Syncs workouts from BaseActivitySummary for devices that support activity tracks.
 * This provides accurate workout data with GPS, detailed metrics, etc.
 *
 * Use this syncer for devices where coordinator.supportsActivityTracks() returns true.
 */
@SuppressLint("RestrictedApi")
internal object RecordedWorkoutSyncer : HealthConnectSyncer {

    override suspend fun sync(ctx: SyncContext): SyncerStatistics {
        val gbDevice = ctx.gbDevice
        val deviceName = ctx.deviceName
        val context = ctx.androidContext
        val useDetailedSync = HealthConnectSyncGate.isDetailedWorkoutSyncEnabled

        // Permission Check for ExerciseSessionRecord
        if (HealthPermission.getWritePermission(ExerciseSessionRecord::class) !in ctx.grantedPermissions) {
            LOG.info("Skipping Recorded Workout sync for device '$deviceName'; ExerciseSessionRecord permission not granted.")
            return SyncerStatistics(recordType = "Workout")
        }

        // Query BaseActivitySummary from database
        val workouts = queryWorkoutsFromDatabase(gbDevice, ctx.sliceStart, ctx.sliceEnd)

        if (workouts.isEmpty()) {
            LOG.info("No workouts found in database for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
            return SyncerStatistics(recordType = "Workout")
        }

        LOG.info("Found ${workouts.size} workout(s) from BaseActivitySummary for device '$deviceName' in slice: ${ctx.sliceStart} to ${ctx.sliceEnd}.")

        parseWorkoutSummaries(workouts, gbDevice, context, deviceName)

        var workoutsProcessedInThisSlice = 0
        val workoutRecordList = mutableListOf<Record>()
        var latestWorkoutEndTs: Instant? = null

        for (workout in workouts) {
            try {
                val workoutStartInstant = workout.startTime.toInstant()
                val workoutEndInstant = workout.endTime.toInstant()

                if (workoutEndInstant.isBefore(workoutStartInstant)) {
                    LOG.warn("Skipping invalid workout for device '$deviceName' (Type: ${workout.activityKind}): End time $workoutEndInstant is before start time $workoutStartInstant.")
                    continue
                }

                workoutsProcessedInThisSlice++
                LOG.info("Processing workout for device '$deviceName' (Type: ${workout.activityKind}, Start: $workoutStartInstant, End: $workoutEndInstant).")

                val startOffset = ctx.zoneId.rules.getOffset(workoutStartInstant)
                val endOffset = ctx.zoneId.rules.getOffset(workoutEndInstant)

                val recordsToInsert = mutableListOf<Record>()
                val activityKind = ActivityKind.fromCode(workout.activityKind)
                val exerciseType = WorkoutSyncerUtils.mapActivityKindToExerciseType(activityKind)

                // Skip non-exercise activities (but allow UNKNOWN since it's in BaseActivitySummary - was explicitly recorded)
                if (activityKind == ActivityKind.NOT_MEASURED ||
                    activityKind == ActivityKind.NOT_WORN ||
                    ActivityKind.isSleep(activityKind)
                ) {
                    LOG.debug("Skipping non-exercising or sleep-related ActivityKind {} for device '{}'.", activityKind, deviceName)
                    workoutsProcessedInThisSlice--
                    continue
                }

                var activityPoints: List<ActivityPoint>? = null

                if (useDetailedSync) {
                    activityPoints = loadActivityPoints(workout, gbDevice, context)
                }

                if (activityPoints != null && activityPoints.isNotEmpty()) {
                    LOG.info("Using detailed sync with ${activityPoints.size} activity points for workout (Type: ${activityKind}, Start: $workoutStartInstant).")
                    processDetailedWorkout(
                        ctx, workout, activityPoints, workoutStartInstant, workoutEndInstant,
                        startOffset, endOffset, recordsToInsert, activityKind, exerciseType
                    )
                } else {
                    if (useDetailedSync) {
                        if (activityPoints == null) {
                            LOG.info("No track file available for workout, falling back to aggregate data.")
                        } else {
                            LOG.info("Track file contains no activity points, falling back to aggregate data.")
                        }
                    }
                    processAggregateWorkout(
                        ctx, workout, workoutStartInstant, workoutEndInstant,
                        startOffset, endOffset, recordsToInsert, activityKind, exerciseType
                    )
                }

                if (recordsToInsert.isEmpty()) {
                    LOG.warn("No records were created for workout (Type: ${activityKind}, Start: $workoutStartInstant) for device '$deviceName'. This should not happen.")
                    continue
                }

                ctx.support.insert(recordsToInsert)
                LOG.info("Successfully inserted ${recordsToInsert.size} record(s) for workout (Type: ${activityKind}, Start: $workoutStartInstant) for device '$deviceName'.")
                workoutRecordList.addAll(recordsToInsert)
                val currentEnd = workoutEndInstant
                if (latestWorkoutEndTs == null || currentEnd.isAfter(latestWorkoutEndTs)) {
                    latestWorkoutEndTs = currentEnd
                }
            } catch (e: HealthConnectAbortException) {
                // The toggle went off, or the Health Connect quota is gone. Neither is this
                // workout's fault and neither gets better by trying the next one.
                throw e
            } catch (e: SyncException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Error processing workout for device '$deviceName'", e)
                // Continue with next workout instead of failing entire sync
            }
        }

        if (workoutsProcessedInThisSlice == 0 && workouts.isNotEmpty()) {
            LOG.info("No workouts were processed for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd} (e.g., all invalid).")
        } else if (workoutsProcessedInThisSlice > 0) {
            LOG.info("Finished processing $workoutsProcessedInThisSlice workout(s) for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
        }

        if (workoutRecordList.isEmpty()) {
            LOG.info("No valid ExerciseSessionRecord(s) created for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
            return SyncerStatistics(recordType = "Workout")
        }

        LOG.info("Successfully inserted ${workoutRecordList.size} record(s) for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
        return SyncerStatistics(recordsSynced = workoutRecordList.size, recordType = "Workout", latestRecordTimestamp = latestWorkoutEndTs)
    }

    private fun queryWorkoutsFromDatabase(
        gbDevice: GBDevice,
        sliceStartBoundary: Instant,
        sliceEndBoundary: Instant
    ): List<BaseActivitySummary> {
        try {
            GBApplication.acquireDbReadOnly().use { db ->
                val device = DBHelper.getDevice(gbDevice, db.daoSession)
                if (device == null) {
                    LOG.warn("Device not found in database for '{}'", gbDevice.aliasOrName)
                    return emptyList()
                }

                val startDate = Date.from(sliceStartBoundary)
                val endDate = Date.from(sliceEndBoundary)

                return db.daoSession.baseActivitySummaryDao.queryBuilder()
                    .where(
                        BaseActivitySummaryDao.Properties.DeviceId.eq(device.id),
                        BaseActivitySummaryDao.Properties.StartTime.ge(startDate),
                        BaseActivitySummaryDao.Properties.StartTime.lt(endDate)
                    )
                    .build()
                    .list()
            }
        } catch (e: Exception) {
            LOG.error("Error querying workouts from database for device '{}'", gbDevice.aliasOrName, e)
            return emptyList()
        }
    }

    private fun parseWorkoutSummaries(
        workouts: List<BaseActivitySummary>,
        gbDevice: GBDevice,
        context: Context,
        deviceName: String
    ) {
        val coordinator = gbDevice.deviceCoordinator
        val activitySummaryParser = coordinator.getActivitySummaryParser(gbDevice, context)

        if (activitySummaryParser == null) {
            LOG.warn("No ActivitySummaryParser available for device '{}'", deviceName)
            return
        }

        for (workout in workouts) {
            try {
                activitySummaryParser.parseWorkout(workout, true)
                LOG.debug("Parsed workout summary for device '{}' at {}", deviceName, workout.startTime)
            } catch (e: Exception) {
                LOG.error("Error parsing workout summary for device '{}' at {}", deviceName, workout.startTime, e)
            }
        }
    }

    private fun loadActivityPoints(workout: BaseActivitySummary, device: GBDevice, context: Context): List<ActivityPoint>? {
        val activityTrackProvider = device.deviceCoordinator.getActivityTrackProvider(device, context)
        if (activityTrackProvider == null) {
            LOG.debug("No activity track provider available device '{}'.", device)
            return null
        }

        val points = activityTrackProvider.getActivityTrack(workout)?.allPoints
        if (points.isNullOrEmpty()) {
            LOG.debug("Track file for workout {} contains no activity points", workout.id)
            return null
        }
        return points
    }

    private fun processDetailedWorkout(
        ctx: SyncContext,
        workout: BaseActivitySummary,
        activityPoints: List<ActivityPoint>,
        workoutStartInstant: Instant,
        workoutEndInstant: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        recordsToInsert: MutableList<Record>,
        activityKind: ActivityKind,
        exerciseType: Int
    ) {
        val device = ctx.gbDevice
        val deviceName = ctx.deviceName
        val metadata = ctx.metadata
        val grantedPermissions = ctx.grantedPermissions

        val exerciseRoute = if (PERMISSION_WRITE_EXERCISE_ROUTE in grantedPermissions) {
            buildSanitisedRoute(activityPoints, workoutStartInstant, workoutEndInstant, deviceName)
        } else {
            null
        }

        recordsToInsert.add(
            ExerciseSessionRecord(
                startTime = workoutStartInstant,
                startZoneOffset = startOffset,
                endTime = workoutEndInstant,
                endZoneOffset = endOffset,
                exerciseType = exerciseType,
                title = workout.name ?: activityKind.getLabel(ctx.androidContext),
                exerciseRoute = exerciseRoute,
                metadata = metadata
            )
        )

        addDetailedHeartRateRecords(activityPoints, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
        addDetailedSpeedRecords(activityPoints, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
        addDetailedPowerRecords(activityPoints, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)

        val summaryData = parseSummaryData(workout.summaryData)
        if (summaryData != null) {
            if (!device.deviceCoordinator.supportsActivityDistance(device)) {
                addDistanceRecord(summaryData, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
            }
            if (!device.deviceCoordinator.supportsActiveCalories(device)) {
                addCaloriesRecords(summaryData, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
            }
            addElevationGainedRecord(summaryData, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
            addCadenceRecords(summaryData, activityKind, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
        }
    }

    private fun processAggregateWorkout(
        ctx: SyncContext,
        workout: BaseActivitySummary,
        workoutStartInstant: Instant,
        workoutEndInstant: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        recordsToInsert: MutableList<Record>,
        activityKind: ActivityKind,
        exerciseType: Int
    ) {
        val gbDevice = ctx.gbDevice
        val deviceName = ctx.deviceName
        val metadata = ctx.metadata
        val grantedPermissions = ctx.grantedPermissions

        recordsToInsert.add(
            ExerciseSessionRecord(
                startTime = workoutStartInstant,
                startZoneOffset = startOffset,
                endTime = workoutEndInstant,
                endZoneOffset = endOffset,
                exerciseType = exerciseType,
                title = workout.name ?: activityKind.getLabel(ctx.androidContext),
                metadata = metadata
            )
        )

        val summaryData = parseSummaryData(workout.summaryData)
        if (summaryData != null) {
            if (!gbDevice.deviceCoordinator.supportsActivityDistance(gbDevice)) {
                addDistanceRecord(summaryData, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
            }
            addSpeedRecord(summaryData, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
            if (!gbDevice.deviceCoordinator.supportsActiveCalories(gbDevice)) {
                addCaloriesRecords(summaryData, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
            }
            addElevationGainedRecord(summaryData, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
            addCadenceRecords(summaryData, activityKind, workoutStartInstant, workoutEndInstant, startOffset, endOffset, metadata, grantedPermissions, recordsToInsert, deviceName)
        } else {
            LOG.warn("No summary data available for workout on device '{}' at {}", deviceName, workoutStartInstant)
        }
    }

    internal fun buildSanitisedRoute(
        activityPoints: List<ActivityPoint>,
        workoutStartInstant: Instant,
        workoutEndInstant: Instant,
        deviceName: String
    ): ExerciseRoute? {
        return try {
            val locations = ArrayList<ExerciseRoute.Location>()
            val seenInstants = HashSet<Instant>()
            var droppedOutOfWindow = 0
            var droppedInvalidCoords = 0
            var droppedDuplicateTime = 0

            for (point in activityPoints) {
                val time = point.time ?: continue
                val location = point.location ?: continue
                val pointInstant = time.toInstant()
                if (pointInstant.isBefore(workoutStartInstant) || pointInstant.isAfter(workoutEndInstant)) {
                    droppedOutOfWindow++
                    continue
                }
                val lat = location.latitude
                val lng = location.longitude
                if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                    droppedInvalidCoords++
                    continue
                }
                if (!seenInstants.add(pointInstant)) {
                    droppedDuplicateTime++
                    continue
                }
                val altitude = if (location.hasAltitude() && location.altitude.isFinite()) {
                    Length.meters(location.altitude)
                } else null
                val hdop = if (location.hasHdop() && location.hdop.isFinite() && location.hdop >= 0) {
                    Length.meters(location.hdop)
                } else null
                val vdop = if (location.hasVdop() && location.vdop.isFinite() && location.vdop >= 0) {
                    Length.meters(location.vdop)
                } else null
                locations.add(
                    ExerciseRoute.Location(
                        time = pointInstant,
                        latitude = lat,
                        longitude = lng,
                        altitude = altitude,
                        horizontalAccuracy = hdop,
                        verticalAccuracy = vdop
                    )
                )
            }

            if (droppedOutOfWindow > 0 || droppedInvalidCoords > 0 || droppedDuplicateTime > 0) {
                LOG.info(
                    "[HC_SYNC] GPS route sanitisation for device '{}': dropped {} out-of-window, {} invalid coords, {} duplicate timestamps; kept {}.",
                    deviceName, droppedOutOfWindow, droppedInvalidCoords, droppedDuplicateTime, locations.size
                )
            }

            if (locations.size < 2) {
                if (locations.isNotEmpty()) {
                    LOG.info("[HC_SYNC] GPS route for device '{}' has only {} usable point(s); skipping route.", deviceName, locations.size)
                }
                return null
            }

            LOG.info("Adding GPS route with ${locations.size} location points for workout on device '$deviceName'.")
            ExerciseRoute(locations)
        } catch (e: IllegalArgumentException) {
            LOG.error("[HC_SYNC] Failed to build GPS route for device '{}': {}. Skipping route.", deviceName, e.message)
            null
        }
    }

    private fun addDetailedHeartRateRecords(
        activityPoints: List<ActivityPoint>,
        startTime: Instant,
        endTime: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        metadata: Metadata,
        grantedPermissions: Set<String>,
        recordsToInsert: MutableList<Record>,
        deviceName: String
    ) {
        if (HealthPermission.getWritePermission(HeartRateRecord::class) !in grantedPermissions) {
            return
        }

        val hrSamples = activityPoints
            .filter { it.heartRate > 0 && it.time != null }
            .mapNotNull { point ->
                val pointInstant = point.time.toInstant()
                if (pointInstant.isBefore(startTime) || pointInstant.isAfter(endTime)) {
                    null
                } else {
                    HeartRateRecord.Sample(
                        time = pointInstant,
                        beatsPerMinute = point.heartRate.toLong()
                    )
                }
            }

        if (hrSamples.isNotEmpty()) {
            recordsToInsert.add(
                HeartRateRecord(
                    startTime = startTime,
                    startZoneOffset = startOffset,
                    endTime = endTime,
                    endZoneOffset = endOffset,
                    samples = hrSamples,
                    metadata = metadata
                )
            )
            LOG.debug("Added detailed HeartRateRecord with ${hrSamples.size} samples for workout on device '$deviceName'.")
        }
    }

    private fun addDetailedSpeedRecords(
        activityPoints: List<ActivityPoint>,
        startTime: Instant,
        endTime: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        metadata: Metadata,
        grantedPermissions: Set<String>,
        recordsToInsert: MutableList<Record>,
        deviceName: String
    ) {
        if (HealthPermission.getWritePermission(SpeedRecord::class) !in grantedPermissions) {
            return
        }

        val speedSamples = activityPoints
            .filter { it.speed > 0 && it.time != null }
            .mapNotNull { point ->
                val pointInstant = point.time.toInstant()
                if (pointInstant.isBefore(startTime) || pointInstant.isAfter(endTime)) {
                    null
                } else {
                    SpeedRecord.Sample(
                        time = pointInstant,
                        speed = Velocity.metersPerSecond(point.speed.toDouble())
                    )
                }
            }

        if (speedSamples.isNotEmpty()) {
            recordsToInsert.add(
                SpeedRecord(
                    startTime = startTime,
                    startZoneOffset = startOffset,
                    endTime = endTime,
                    endZoneOffset = endOffset,
                    samples = speedSamples,
                    metadata = metadata
                )
            )
            LOG.debug("Added detailed SpeedRecord with ${speedSamples.size} samples for workout on device '$deviceName'.")
        }
    }

    private fun addDetailedPowerRecords(
        activityPoints: List<ActivityPoint>,
        startTime: Instant,
        endTime: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        metadata: Metadata,
        grantedPermissions: Set<String>,
        recordsToInsert: MutableList<Record>,
        deviceName: String
    ) {
        if (HealthPermission.getWritePermission(PowerRecord::class) !in grantedPermissions) {
            return
        }

        val powerSamples = activityPoints
            .filter { it.power > 0 && it.time != null }
            .mapNotNull { point ->
                val pointInstant = point.time.toInstant()
                if (pointInstant.isBefore(startTime) || pointInstant.isAfter(endTime)) {
                    null
                } else {
                    PowerRecord.Sample(
                        time = pointInstant,
                        power = Power.watts(point.power.toDouble())
                    )
                }
            }

        if (powerSamples.isNotEmpty()) {
            recordsToInsert.add(
                PowerRecord(
                    startTime = startTime,
                    startZoneOffset = startOffset,
                    endTime = endTime,
                    endZoneOffset = endOffset,
                    samples = powerSamples,
                    metadata = metadata
                )
            )
            LOG.debug("Added detailed PowerRecord with ${powerSamples.size} samples for workout on device '$deviceName'.")
        }
    }

    private fun parseSummaryData(summaryDataJson: String?): ActivitySummaryData? {
        if (summaryDataJson.isNullOrBlank()) {
            return null
        }
        return try {
            ActivitySummaryData.fromJson(summaryDataJson)
        } catch (e: Exception) {
            LOG.warn("Failed to parse summaryData JSON", e)
            null
        }
    }

    private fun addDistanceRecord(
        summaryData: ActivitySummaryData,
        startTime: Instant,
        endTime: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        metadata: Metadata,
        grantedPermissions: Set<String>,
        recordsToInsert: MutableList<Record>,
        deviceName: String
    ) {
        if (HealthPermission.getWritePermission(DistanceRecord::class) !in grantedPermissions) {
            return
        }

        val distanceMeters = summaryData.getNumber(ActivitySummaryEntries.DISTANCE_METERS, 0.0)
        if (distanceMeters.toDouble() > 0) {
            recordsToInsert.add(
                DistanceRecord(
                    startTime = startTime,
                    startZoneOffset = startOffset,
                    endTime = endTime,
                    endZoneOffset = endOffset,
                    distance = Length.meters(distanceMeters.toDouble()),
                    metadata = metadata
                )
            )
            LOG.debug("Added DistanceRecord ({} meters) for workout at {} for device '{}'.", distanceMeters, startTime, deviceName)
        }
    }

    private fun addSpeedRecord(
        summaryData: ActivitySummaryData,
        startTime: Instant,
        endTime: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        metadata: Metadata,
        grantedPermissions: Set<String>,
        recordsToInsert: MutableList<Record>,
        deviceName: String
    ) {
        if (HealthPermission.getWritePermission(SpeedRecord::class) !in grantedPermissions) {
            return
        }

        val speedAvg = summaryData.getNumber(ActivitySummaryEntries.SPEED_AVG, 0.0)
        if (speedAvg.toDouble() > 0) {
            val midTime = startTime.plusSeconds((endTime.epochSecond - startTime.epochSecond) / 2)
            recordsToInsert.add(
                SpeedRecord(
                    startTime = midTime,
                    startZoneOffset = startOffset,
                    endTime = midTime,
                    endZoneOffset = startOffset,
                    samples = listOf(
                        SpeedRecord.Sample(
                            time = midTime,
                            speed = Velocity.metersPerSecond(speedAvg.toDouble())
                        )
                    ),
                    metadata = metadata
                )
            )
            LOG.debug("Added SpeedRecord (avg: {} m/s) for workout at {} for device '{}'.", speedAvg, startTime, deviceName)
        }
    }

    private fun addCaloriesRecords(
        summaryData: ActivitySummaryData,
        startTime: Instant,
        endTime: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        metadata: Metadata,
        grantedPermissions: Set<String>,
        recordsToInsert: MutableList<Record>,
        deviceName: String
    ) {
        // Most fitness trackers report "caloriesBurnt" as active calories (exercise calories)
        val activeCalories = summaryData.getNumber(ActivitySummaryEntries.CALORIES_BURNT, 0.0).toDouble()

        if (HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class) in grantedPermissions) {
            if (activeCalories > 0) {
                recordsToInsert.add(
                    ActiveCaloriesBurnedRecord(
                        startTime = startTime,
                        startZoneOffset = startOffset,
                        endTime = endTime,
                        endZoneOffset = endOffset,
                        energy = Energy.kilocalories(activeCalories),
                        metadata = metadata
                    )
                )
                LOG.debug("Added ActiveCaloriesBurnedRecord ({} kcal) for workout at {} for device '{}'.", activeCalories, startTime, deviceName)
            } else {
                LOG.debug("No active calories data in workout summary for device '{}' at {}.", deviceName, startTime)
            }
        }

        // Only sync TotalCaloriesBurnedRecord if we have both active AND resting calories,
        // otherwise syncing the same value as both active and total would be misleading.
        if (HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class) !in grantedPermissions) {
            return
        }

        val restingCalories = summaryData.getNumber(ActivitySummaryEntries.CALORIES_RESTING, 0.0).toDouble()
        if (activeCalories > 0 && restingCalories > 0) {
            val totalCalories = activeCalories + restingCalories
            recordsToInsert.add(
                TotalCaloriesBurnedRecord(
                    startTime = startTime,
                    startZoneOffset = startOffset,
                    endTime = endTime,
                    endZoneOffset = endOffset,
                    energy = Energy.kilocalories(totalCalories),
                    metadata = metadata
                )
            )
            LOG.debug(
                "Added TotalCaloriesBurnedRecord ({} kcal = {} active + {} resting) for workout at {} for device '{}'.",
                totalCalories, activeCalories, restingCalories, startTime, deviceName
            )
        } else {
            LOG.debug(
                "Not syncing TotalCaloriesBurnedRecord - need both active and resting calories (have: active={}, resting={}) for device '{}'",
                activeCalories, restingCalories, deviceName
            )
        }
    }

    private fun addElevationGainedRecord(
        summaryData: ActivitySummaryData,
        startTime: Instant,
        endTime: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        metadata: Metadata,
        grantedPermissions: Set<String>,
        recordsToInsert: MutableList<Record>,
        deviceName: String
    ) {
        if (HealthPermission.getWritePermission(ElevationGainedRecord::class) !in grantedPermissions) {
            return
        }

        var elevationGain = summaryData.getNumber(ActivitySummaryEntries.ELEVATION_GAIN, 0.0).toDouble()
        if (elevationGain == 0.0) {
            elevationGain = summaryData.getNumber(ActivitySummaryEntries.TOTAL_ASCENT, 0.0).toDouble()
        }
        if (elevationGain == 0.0) {
            elevationGain = summaryData.getNumber(ActivitySummaryEntries.ASCENT_METERS, 0.0).toDouble()
        }

        if (elevationGain > 0) {
            recordsToInsert.add(
                ElevationGainedRecord(
                    startTime = startTime,
                    startZoneOffset = startOffset,
                    endTime = endTime,
                    endZoneOffset = endOffset,
                    elevation = Length.meters(elevationGain),
                    metadata = metadata
                )
            )
            LOG.debug("Added ElevationGainedRecord ({} m) for workout at {} for device '{}'.", elevationGain, startTime, deviceName)
        }
    }

    private fun addCadenceRecords(
        summaryData: ActivitySummaryData,
        activityKind: ActivityKind,
        startTime: Instant,
        endTime: Instant,
        startOffset: ZoneOffset,
        endOffset: ZoneOffset,
        metadata: Metadata,
        grantedPermissions: Set<String>,
        recordsToInsert: MutableList<Record>,
        deviceName: String
    ) {
        val cadenceAvg = summaryData.getNumber(ActivitySummaryEntries.CADENCE_AVG, 0.0)
        if (cadenceAvg.toDouble() <= 0) {
            return
        }

        val midTime = startTime.plusSeconds((endTime.epochSecond - startTime.epochSecond) / 2)

        when (ActivityKind.getCycleUnit(activityKind)) {
            ActivityKind.CycleUnit.STEPS -> {
                if (HealthPermission.getWritePermission(StepsCadenceRecord::class) in grantedPermissions) {
                    recordsToInsert.add(
                        StepsCadenceRecord(
                            startTime = midTime,
                            startZoneOffset = startOffset,
                            endTime = midTime,
                            endZoneOffset = startOffset,
                            samples = listOf(
                                StepsCadenceRecord.Sample(
                                    time = midTime,
                                    rate = cadenceAvg.toDouble()
                                )
                            ),
                            metadata = metadata
                        )
                    )
                    LOG.debug("Added StepsCadenceRecord (avg: {} steps/min) for workout at {} for device '{}'.", cadenceAvg, startTime, deviceName)
                }
            }

            ActivityKind.CycleUnit.REVOLUTIONS -> {
                if (HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class) in grantedPermissions) {
                    recordsToInsert.add(
                        CyclingPedalingCadenceRecord(
                            startTime = midTime,
                            startZoneOffset = startOffset,
                            endTime = midTime,
                            endZoneOffset = startOffset,
                            samples = listOf(
                                CyclingPedalingCadenceRecord.Sample(
                                    time = midTime,
                                    revolutionsPerMinute = cadenceAvg.toDouble()
                                )
                            ),
                            metadata = metadata
                        )
                    )
                    LOG.debug("Added CyclingPedalingCadenceRecord (avg: {} rpm) for workout at {} for device '{}'.", cadenceAvg, startTime, deviceName)
                }
            }

            else -> {
                // Strokes, jumps, reps, swings: Health Connect has no cadence record for these.
                LOG.debug("Skipping cadence sync for {} activity - no appropriate Health Connect record type.", activityKind)
            }
        }
    }
}
