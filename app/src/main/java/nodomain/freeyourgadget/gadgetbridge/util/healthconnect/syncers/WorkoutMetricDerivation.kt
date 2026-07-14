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

import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Metrics a workout track implies but does not state.
 *
 * Not every device fills in speed or cadence per track point, and not every device puts an ascent
 * figure in the workout summary - but the track still carries the positions and altitudes those
 * numbers are made of. Gadgetbridge draws the graphs from them; Health Connect used to get nothing.
 *
 * Deliberately free of Android types (no `android.location.Location`, whose `distanceTo` returns 0
 * under the unit-test stubs) so the derivations can be tested directly.
 */
internal object WorkoutMetricDerivation {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Ignore altitude wobble below this; barometric noise would otherwise accumulate into a climb. */
    private const val ELEVATION_NOISE_THRESHOLD_METERS = 1.0

    /** Faster than a sprinter, on a wearable, means the fix is wrong. */
    private const val MAX_PLAUSIBLE_SPEED_MPS = 30.0

    data class TimedValue(val time: Instant, val value: Double)

    /** Great-circle distance in metres. */
    fun haversineMeters(from: GPSCoordinate, to: GPSCoordinate): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Speed at each track point, in m/s.
     *
     * Uses the point's own speed where the device recorded one, and otherwise derives it from the
     * distance covered since the previous point - preferring the cumulative distance the track
     * carries, and falling back to the distance between the two fixes.
     */
    fun speedSamples(points: List<ActivityPoint>): List<TimedValue> {
        val samples = mutableListOf<TimedValue>()
        var previous: ActivityPoint? = null

        for (point in points) {
            val time = point.time ?: continue
            val instant = time.toInstant()

            val reported = point.speed.toDouble()
            if (reported > 0) {
                samples.add(TimedValue(instant, reported))
                previous = point
                continue
            }

            val prev = previous
            val prevTime = prev?.time
            if (prev == null || prevTime == null) {
                previous = point
                continue
            }

            val elapsedSeconds = (instant.toEpochMilli() - prevTime.toInstant().toEpochMilli()) / 1000.0
            if (elapsedSeconds <= 0) {
                previous = point
                continue
            }

            val metres = distanceBetween(prev, point)
            if (metres == null) {
                previous = point
                continue
            }

            val speed = metres / elapsedSeconds
            if (speed > 0 && speed <= MAX_PLAUSIBLE_SPEED_MPS) {
                samples.add(TimedValue(instant, speed))
            }
            previous = point
        }

        return samples
    }

    private fun distanceBetween(from: ActivityPoint, to: ActivityPoint): Double? {
        val fromDistance = from.distance
        val toDistance = to.distance
        if (fromDistance >= 0 && toDistance >= fromDistance) {
            return toDistance - fromDistance
        }

        val fromLocation = from.location ?: return null
        val toLocation = to.location ?: return null
        return haversineMeters(fromLocation, toLocation)
    }

    /** Cadence at each track point, for the points where the device recorded one. */
    fun cadenceSamples(points: List<ActivityPoint>): List<TimedValue> =
        points.mapNotNull { point ->
            val time = point.time ?: return@mapNotNull null
            val cadence = point.cadence
            if (cadence <= 0) null else TimedValue(time.toInstant(), cadence.toDouble())
        }

    /**
     * Total ascent over the track, in metres: the sum of the upward altitude changes, ignoring
     * changes smaller than the sensor's noise floor.
     */
    fun elevationGainMeters(points: List<ActivityPoint>): Double {
        var gain = 0.0
        var reference: Double? = null

        for (point in points) {
            val altitude = point.altitude
            if (altitude <= GPSCoordinate.UNKNOWN_ALTITUDE || !altitude.isFinite()) {
                continue
            }
            val previous = reference
            if (previous == null) {
                reference = altitude
                continue
            }
            val delta = altitude - previous
            if (abs(delta) < ELEVATION_NOISE_THRESHOLD_METERS) {
                // Hold the reference: consuming it here would let a slow climb be swallowed one
                // sub-threshold step at a time.
                continue
            }
            if (delta > 0) {
                gain += delta
            }
            reference = altitude
        }

        return gain
    }
}
