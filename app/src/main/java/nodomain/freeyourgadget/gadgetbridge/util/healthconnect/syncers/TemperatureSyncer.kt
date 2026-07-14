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
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.SyncException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val LOG = LoggerFactory.getLogger("TemperatureSyncer")

private const val MIN_PLAUSIBLE_BODY_TEMP_C = 25.0
private const val MAX_PLAUSIBLE_BODY_TEMP_C = 45.0
private const val MIN_PLAUSIBLE_SKIN_TEMP_C = 15.0
private const val MAX_PLAUSIBLE_SKIN_TEMP_C = 45.0
private const val DEFAULT_SKIN_BASELINE_C = 33.0

/** See [MAX_HEART_RATE_RECORD_SPAN]: SkinTemperatureRecord is a series record with the same trap. */
private val MAX_SKIN_TEMPERATURE_RECORD_SPAN: Duration = Duration.ofHours(1)

/**
 * Wearables that sit against the skin. A sample from one of these that carries no explicit type is
 * a skin reading; a thermometer or an ambient sensor is not, and must not be guessed at.
 */
private val BODY_WORN_DEVICE_KINDS = setOf(
    DeviceCoordinator.DeviceKind.WATCH,
    DeviceCoordinator.DeviceKind.RING,
    DeviceCoordinator.DeviceKind.FITNESS_BAND,
    DeviceCoordinator.DeviceKind.CHEST_STRAP
)

internal object TemperatureSyncer : HealthConnectSyncer {
    override suspend fun sync(ctx: SyncContext): SyncerStatistics {
        val gbDevice = ctx.gbDevice
        val deviceName = ctx.deviceName
        val deviceAddress = gbDevice.address

        // Clean up cache for devices that no longer exist
        cleanupStaleBaselines()

        val canWriteBodyTemp = HealthPermission.getWritePermission(BodyTemperatureRecord::class) in ctx.grantedPermissions
        val canWriteSkinTemp = HealthPermission.getWritePermission(SkinTemperatureRecord::class) in ctx.grantedPermissions

        if (!canWriteBodyTemp && !canWriteSkinTemp) {
            LOG.info("Skipping Temperature sync for device '$deviceName'; relevant permissions (Body, Skin) not granted.")
            return SyncerStatistics(recordsSynced = 0, recordsSkipped = 0, recordType = "Temperature")
        }

        val samples: List<TemperatureSample> = try {
            GBApplication.acquireDbReadOnly().use { db ->
                val provider = gbDevice.deviceCoordinator.getTemperatureSampleProvider(gbDevice, db.daoSession)
                if (provider == null) {
                    LOG.warn("TemperatureSampleProvider not found for device '$deviceName'. Skipping Temperature sync for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
                    return@use emptyList()
                }
                provider.getAllSamples(ctx.sliceStart.toEpochMilli(), ctx.sliceEnd.toEpochMilli())
            }
        } catch (e: Exception) {
            throw SyncException("Error fetching temperature samples for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.", e)
        }

        if (samples.isEmpty()) {
            LOG.info("No temperature samples found for device '$deviceName' in slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
            return SyncerStatistics(recordsSynced = 0, recordsSkipped = 0, recordType = "Temperature")
        }

        val bodySamples = samples.filter { it.temperatureType == TemperatureSample.TYPE_BODY }
        val skinSamples = samples.filter { isSkinSample(it, gbDevice) }
        val untypedAsSkin = skinSamples.count { it.temperatureType == TemperatureSample.TYPE_UNKNOWN }
        LOG.info("Found ${samples.size} temperature samples for '$deviceName': ${bodySamples.size} body, ${skinSamples.size} skin, in slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
        if (untypedAsSkin > 0) {
            LOG.info(
                "Treating {} untyped temperature sample(s) from '{}' as skin temperature (device kind {}); the sample source did not record a type.",
                untypedAsSkin, deviceName, gbDevice.deviceCoordinator.getDeviceKind(gbDevice)
            )
        }

        val recordsToInsert = mutableListOf<Record>()

        if (canWriteBodyTemp && bodySamples.isNotEmpty()) {
            LOG.info("Processing ${bodySamples.size} body temperature samples for '$deviceName'.")
            for (sample in bodySamples) {
                val sampleTemp = sample.temperature.toDouble()
                if (sampleTemp !in MIN_PLAUSIBLE_BODY_TEMP_C..MAX_PLAUSIBLE_BODY_TEMP_C || !sampleTemp.isFinite()) {
                    LOG.skipOutOfRange(deviceName, "BodyTemperature", "${sample.temperature}°C", "$MIN_PLAUSIBLE_BODY_TEMP_C..$MAX_PLAUSIBLE_BODY_TEMP_C °C")
                    continue
                }
                val timestamp = Instant.ofEpochMilli(sample.timestamp)
                if (!timestamp.isBefore(ctx.sliceStart) && timestamp.isBefore(ctx.sliceEnd)) {
                    recordsToInsert.add(
                        BodyTemperatureRecord(
                            time = timestamp,
                            zoneOffset = ctx.zoneId.rules.getOffset(timestamp),
                            temperature = Temperature.celsius(sampleTemp),
                            measurementLocation = sample.temperatureLocation,
                            metadata = ctx.metadata
                        )
                    )
                } else {
                    LOG.trace(
                        "Skipping Body Temperature sample for device '{}' at {} (value: {}°C) as it's outside slice {} - {}.",
                        deviceName, timestamp, sample.temperature, ctx.sliceStart, ctx.sliceEnd
                    )
                }
            }
        } else if (bodySamples.isNotEmpty()) {
            LOG.info("Skipping BodyTemperatureRecord sync for ${bodySamples.size} samples for '$deviceName'; specific permission not granted.")
        }

        if (canWriteSkinTemp && skinSamples.isNotEmpty()) {
            LOG.info("Processing ${skinSamples.size} skin temperature samples for '$deviceName'.")

            val baselineHelper = baselineHelperCachePerDevice.getOrPut(deviceAddress) {
                LOG.info("Creating new SkinBaselineHelper for device '$deviceName' ($deviceAddress).")
                SkinBaselineHelper(deviceAddress)
            }
            val baselineForCurrentRecord = baselineHelper.getBaselineForDay(ctx.sliceStart, gbDevice)

            val validSkinSamplesInSlice = skinSamples.filter {
                val ts = Instant.ofEpochMilli(it.timestamp)
                val tempC = it.temperature.toDouble()
                (tempC in MIN_PLAUSIBLE_SKIN_TEMP_C..MAX_PLAUSIBLE_SKIN_TEMP_C) &&
                        (!ts.isBefore(ctx.sliceStart) && ts.isBefore(ctx.sliceEnd))
            }.sortedBy { it.timestamp }

            if (validSkinSamplesInSlice.isNotEmpty()) {
                recordsToInsert.addAll(
                    buildSkinTemperatureRecords(
                        validSkinSamplesInSlice,
                        baselineForCurrentRecord,
                        ctx.zoneId,
                        ctx.metadata,
                        deviceName
                    )
                )
            } else {
                LOG.info("No valid skin temperature samples (plausible and within slice) found for '$deviceName' in slice ${ctx.sliceStart} - ${ctx.sliceEnd}.")
            }
        } else if (skinSamples.isNotEmpty()) {
            LOG.info("Skipping SkinTemperatureRecord sync for ${skinSamples.size} samples for '$deviceName'; specific permission not granted.")
        }

        if (recordsToInsert.isEmpty()) {
            LOG.info("No temperature records (Body/Skin) to insert for '$deviceName' in slice ${ctx.sliceStart} to ${ctx.sliceEnd} after filtering and permission checks.")
            return SyncerStatistics(recordsSynced = 0, recordsSkipped = 0, recordType = "Temperature")
        }

        LOG.info("Attempting to insert ${recordsToInsert.size} TemperatureRecord(s) (Body/Skin) for '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}.")
        ctx.support.insert(recordsToInsert)

        LOG.info("Temperature sync completed for device '$deviceName' for slice ${ctx.sliceStart} to ${ctx.sliceEnd}. Total synced: ${recordsToInsert.size}")
        return buildStatistics(recordsToInsert, 0)
    }

    /**
     * A sample the device recorded without saying where. Colmi rings (and every coordinator that
     * falls back to the default GenericTemperatureSampleProvider) stored temperature with
     * TYPE_UNKNOWN, and the syncer used to drop those on the floor - the reported symptom was a log
     * line reading "20 temperature samples: 0 body, 0 skin" and nothing reaching Health Connect.
     * Rows already in the database still carry the old type, so classifying here rather than at the
     * provider also repairs the history.
     */
    internal fun isSkinSample(sample: TemperatureSample, gbDevice: GBDevice): Boolean {
        if (sample.temperatureType == TemperatureSample.TYPE_SKIN) {
            return true
        }
        if (sample.temperatureType != TemperatureSample.TYPE_UNKNOWN) {
            return false
        }
        return gbDevice.deviceCoordinator.getDeviceKind(gbDevice) in BODY_WORN_DEVICE_KINDS
    }

    /**
     * Turns skin samples into records, each no longer than [MAX_SKIN_TEMPERATURE_RECORD_SPAN].
     *
     * Each delta is the measurement's difference from the record's baseline, which is what Health
     * Connect defines a delta to be ("if baseline is set, deltas are expected to be relative to
     * it"). Differencing against the previous measurement instead - as this did - meant a steady
     * 34 °C skin temperature was written as one sample at 34 °C followed by a run of zero deltas,
     * so every consumer read the rest of the day back as the baseline.
     *
     * The one-hour cap matters for the same reason it does for heart rate: Health Connect matches a
     * series record by the record's own boundary, so a record spanning the whole slice is invisible
     * to anything querying a shorter window.
     */
    internal fun buildSkinTemperatureRecords(
        samples: List<TemperatureSample>,
        baseline: Double,
        zoneId: ZoneId,
        metadata: Metadata,
        deviceName: String
    ): List<SkinTemperatureRecord> {
        val records = mutableListOf<SkinTemperatureRecord>()
        var deltas = mutableListOf<SkinTemperatureRecord.Delta>()
        var windowStart: Instant? = null

        fun flush() {
            if (deltas.isEmpty()) {
                return
            }
            val recordStartTime = deltas.first().time
            val recordEndTime = deltas.last().time.plusSeconds(1)
            records.add(
                SkinTemperatureRecord(
                    startTime = recordStartTime,
                    startZoneOffset = zoneId.rules.getOffset(recordStartTime),
                    endTime = recordEndTime,
                    endZoneOffset = zoneId.rules.getOffset(recordEndTime),
                    deltas = deltas,
                    baseline = Temperature.celsius(baseline),
                    metadata = metadata
                )
            )
            deltas = mutableListOf()
            windowStart = null
        }

        for (sample in samples) {
            val sampleTime = Instant.ofEpochMilli(sample.timestamp)
            val deltaValue = sample.temperature.toDouble() - baseline

            if (deltaValue !in -30.0..30.0 || !deltaValue.isFinite()) {
                LOG.skipOutOfRange(deviceName, "SkinTemperatureDelta", deltaValue, "-30..30 °C")
                continue
            }

            val start = windowStart
            if (start != null && Duration.between(start, sampleTime) >= MAX_SKIN_TEMPERATURE_RECORD_SPAN) {
                flush()
            }
            if (windowStart == null) {
                windowStart = sampleTime
            }

            deltas.add(
                SkinTemperatureRecord.Delta(
                    time = sampleTime,
                    delta = TemperatureDelta.celsius(deltaValue)
                )
            )
        }

        flush()
        return records
    }

    // Builds the slice result, including latestRecordTimestamp. The orchestrator only advances the
    // persisted sync cursor from that field; omitting it freezes the cursor and re-inserts the whole
    // span every run. Body records are point-in-time, skin records are intervals, so the cursor takes
    // the furthest forward edge of either kind.
    internal fun buildStatistics(insertedRecords: List<Record>, recordsSkipped: Int): SyncerStatistics {
        val latest = insertedRecords.mapNotNull {
            when (it) {
                is BodyTemperatureRecord -> it.time
                is SkinTemperatureRecord -> it.endTime
                else -> null
            }
        }.maxOrNull()
        return SyncerStatistics(
            recordsSynced = insertedRecords.size,
            recordsSkipped = recordsSkipped,
            recordType = "Temperature",
            latestRecordTimestamp = latest
        )
    }

    // Helper class to manage dynamic baseline calculation per device
    private class SkinBaselineHelper(private val deviceAddress: String) {
        // Stores averages for up to the last 3 relevant days. Key is the start of the day (Instant).
        private val dailyAverages = LinkedHashMap<Instant, Double>() // Oldest to newest
        var currentCalculatedBaseline: Double = DEFAULT_SKIN_BASELINE_C
            private set

        fun getBaselineForDay(dayToProcess: Instant, gbDevice: GBDevice): Double {
            val targetDayStart = dayToProcess.truncatedTo(ChronoUnit.DAYS)

            // 1. Ensure the average for targetDayStart is in our map if it hasn't been processed yet
            if (!dailyAverages.containsKey(targetDayStart)) {
                try {
                    val dayEnd = targetDayStart.plus(1, ChronoUnit.DAYS)
                    LOG.debug(
                        "SkinBaselineHelper for '{}': Fetching samples for day {} to {}.",
                        deviceAddress, targetDayStart, dayEnd
                    )

                    val samplesForDay: List<TemperatureSample> = GBApplication.acquireDbReadOnly().use { db ->
                        val tempProvider = gbDevice.deviceCoordinator.getTemperatureSampleProvider(gbDevice, db.daoSession)
                        if (tempProvider == null) {
                            LOG.warn("SkinBaselineHelper for '$deviceAddress': TemperatureSampleProvider not found. Cannot calculate daily average for $targetDayStart.")
                            return@use emptyList()
                        }
                        tempProvider.getAllSamples(targetDayStart.toEpochMilli(), dayEnd.toEpochMilli())
                    }

                    val skinSamplesForDay = samplesForDay.filter {
                        val tempC = it.temperature.toDouble()
                        isSkinSample(it, gbDevice) && tempC >= MIN_PLAUSIBLE_SKIN_TEMP_C && tempC <= MAX_PLAUSIBLE_SKIN_TEMP_C
                    }

                    if (skinSamplesForDay.isNotEmpty()) {
                        val avgForDay = skinSamplesForDay.map { it.temperature.toDouble() }.average()
                        dailyAverages[targetDayStart] = avgForDay // Add or update
                        LOG.debug(
                            "SkinBaselineHelper for '{}': Calculated avg for {}: {}°C from {} samples.",
                            deviceAddress, targetDayStart, avgForDay, skinSamplesForDay.size
                        )
                    } else {
                        LOG.debug(
                            "SkinBaselineHelper for '{}': No valid skin samples for {}.",
                            deviceAddress, targetDayStart
                        )
                    }
                } catch (e: Exception) {
                    LOG.error("SkinBaselineHelper for '$deviceAddress': Error fetching/processing samples for day $targetDayStart.", e)
                }
            }

            // 2. Prune dailyAverages to keep only up to the last 3 days ending on or before targetDayStart
            val threeDayWindowStart = targetDayStart.minus(2, ChronoUnit.DAYS)
            val iterator = dailyAverages.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.isBefore(threeDayWindowStart) || entry.key.isAfter(targetDayStart)) {
                    iterator.remove()
                }
            }
            val sortedKeys = dailyAverages.keys.sortedDescending().take(3)
            val tempMap = LinkedHashMap<Instant, Double>()
            for (key in sortedKeys.sorted()) {
                dailyAverages[key]?.let { tempMap[key] = it }
            }
            dailyAverages.clear()
            dailyAverages.putAll(tempMap)

            // 3. Calculate the new baseline using up to 3 components
            val actualValues = dailyAverages.values.toList()

            currentCalculatedBaseline = if (actualValues.isEmpty()) {
                DEFAULT_SKIN_BASELINE_C
            } else {
                val sumActuals = actualValues.sum()
                val countActuals = actualValues.size
                (sumActuals + DEFAULT_SKIN_BASELINE_C * (3 - countActuals)) / 3.0
            }
            LOG.debug(
                "SkinBaselineHelper for '{}': New baseline for {}: {}°C (based on {} daily avgs: {})",
                deviceAddress, targetDayStart, currentCalculatedBaseline, actualValues.size, actualValues
            )
            return currentCalculatedBaseline
        }
    }

    // Cache baseline helpers per device address
    private val baselineHelperCachePerDevice = mutableMapOf<String, SkinBaselineHelper>()

    /**
     * Cleans up baseline cache entries for devices that no longer exist.
     * This prevents memory leaks from accumulating helpers for removed devices.
     */
    private fun cleanupStaleBaselines() {
        val currentDeviceAddresses = GBApplication.app().deviceManager.devices
            .map { it.address }
            .toSet()

        val iterator = baselineHelperCachePerDevice.keys.iterator()
        while (iterator.hasNext()) {
            val cachedAddress = iterator.next()
            if (cachedAddress !in currentDeviceAddresses) {
                iterator.remove()
                LOG.debug("Cleaned up baseline cache for removed device: {}", cachedAddress)
            }
        }
    }
}
