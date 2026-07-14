/*  Copyright (C) 2026 Gadgetbridge contributors

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

import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Writes the calories a Health Connect consumer actually reads.
 *
 * Google Health takes its daily energy figure from TOTAL_CALORIES_BURNED and ignores
 * ActiveCaloriesBurnedRecord entirely, so a device that reports only active calories - which is
 * most of them - showed up as a constant formula estimate no matter how much the user moved.
 *
 * Total is active plus resting. The resting share comes from the same provider Gadgetbridge's own
 * Calories chart uses, so the two agree.
 */
internal object TotalCaloriesSyncer : AbstractActivitySampleSyncer<TotalCaloriesBurnedRecord>() {
    override val logger: Logger = LoggerFactory.getLogger(TotalCaloriesSyncer::class.java)
    override val recordClass: KClass<TotalCaloriesBurnedRecord> = TotalCaloriesBurnedRecord::class

    // HC's TotalCaloriesBurnedRecord caps energy at 1_000_000 kcal.
    private const val MAX_KCAL_PER_RECORD = 1_000_000.0

    override fun convertSample(
        sample: ActivitySample,
        offset: ZoneOffset,
        metadata: Metadata,
        deviceName: String,
        version: Long,
        ctx: SyncContext
    ): TotalCaloriesBurnedRecord? {
        val endTs = Instant.ofEpochSecond(sample.timestamp.toLong())
        val startTs = endTs.minus(1, ChronoUnit.MINUTES)

        val restingKcalPerDay = restingRateFor(ctx, endTs)
        val restingKcal = restingKcalPerDay?.let { RestingMetabolicRate.kcalOver(it, 60) } ?: 0.0

        // ActivitySample reports active energy in calories, not kilocalories.
        val activeCalories = sample.activeCalories
        val activeKcal = if (activeCalories > 0) activeCalories / 1000.0 else 0.0

        val totalKcal = activeKcal + restingKcal
        if (totalKcal <= 0.0) {
            // Nothing measured and no resting rate to fall back on: writing a zero here would
            // outrank a later, better value for the same minute.
            return null
        }
        if (totalKcal > MAX_KCAL_PER_RECORD || !totalKcal.isFinite()) {
            logger.skipOutOfRange(deviceName, "TotalCalories", "$totalKcal kcal", "<= $MAX_KCAL_PER_RECORD kcal per record")
            return null
        }

        return TotalCaloriesBurnedRecord(
            startTime = startTs,
            startZoneOffset = offset,
            endTime = endTs,
            endZoneOffset = offset,
            energy = Energy.kilocalories(totalKcal),
            metadata = clientRecordMetadata(metadata, "totalcalories", endTs.epochSecond, version)
        )
    }

    /**
     * The resting rate barely moves within a day, and resolving it hits the database, so resolve it
     * once per device-day rather than once per minute.
     *
     * Concurrent syncs (a device-triggered one and a manual one, say) share this object, so the map
     * has to tolerate concurrent access. A miss under a race just resolves twice, which is harmless.
     */
    private fun restingRateFor(ctx: SyncContext, at: Instant): Int? {
        val key = ctx.gbDevice.address to at.truncatedTo(ChronoUnit.DAYS)
        cache[key]?.let { return it.value }

        val resolved = RestingMetabolicRate.kcalPerDay(ctx.gbDevice, at.toEpochMilli())
        if (cache.size > MAX_CACHED_DEVICE_DAYS) {
            cache.clear()
        }
        cache[key] = CachedRate(resolved)
        return resolved
    }

    private data class CachedRate(val value: Int?)

    private const val MAX_CACHED_DEVICE_DAYS = 64

    private val cache = ConcurrentHashMap<Pair<String, Instant>, CachedRate>()
}
