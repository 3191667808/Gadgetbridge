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
package nodomain.freeyourgadget.gadgetbridge.util.sensorcontext

import java.util.ArrayDeque

data class TimestampedDouble(val tsMs: Long, val value: Double)
data class TimestampedLong(val tsMs: Long, val value: Long)
data class GpsPoint(val tsMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

data class PhoneSensorSnapshot(
    val pressureSeries: ArrayDeque<TimestampedDouble>,
    val accelMagnitudeSeries: ArrayDeque<TimestampedDouble>,
    val lightLuxSeries: ArrayDeque<TimestampedDouble>,
    val ambientTempSeries: ArrayDeque<TimestampedDouble>,
    val stepCounterSeries: ArrayDeque<TimestampedLong>,
    val gpsSeries: ArrayDeque<GpsPoint>
)

object PhoneSensorContext {
    private const val DEFAULT_SENSOR_HZ = 5
    private const val DEFAULT_RETENTION_HOURS = 24
    internal const val DEFAULT_CAPACITY = DEFAULT_RETENTION_HOURS * 60 * 60 * DEFAULT_SENSOR_HZ

    private val lock = Any()
    private var capacity = DEFAULT_CAPACITY

    val pressureSeries: ArrayDeque<TimestampedDouble> = ArrayDeque(capacity)
    val accelMagnitudeSeries: ArrayDeque<TimestampedDouble> = ArrayDeque(capacity)
    val lightLuxSeries: ArrayDeque<TimestampedDouble> = ArrayDeque(capacity)
    val ambientTempSeries: ArrayDeque<TimestampedDouble> = ArrayDeque(capacity)
    val stepCounterSeries: ArrayDeque<TimestampedLong> = ArrayDeque(capacity)
    val gpsSeries: ArrayDeque<GpsPoint> = ArrayDeque(capacity)

    fun addPressure(tsMs: Long, hPa: Double) = addTimestampedDouble(pressureSeries, TimestampedDouble(tsMs, hPa))

    fun addAccelMagnitude(tsMs: Long, magnitudeMs2: Double) =
        addTimestampedDouble(accelMagnitudeSeries, TimestampedDouble(tsMs, magnitudeMs2))

    fun addLightLux(tsMs: Long, lux: Double) = addTimestampedDouble(lightLuxSeries, TimestampedDouble(tsMs, lux))

    fun addAmbientTemp(tsMs: Long, celsius: Double) =
        addTimestampedDouble(ambientTempSeries, TimestampedDouble(tsMs, celsius))

    fun addStepCounter(tsMs: Long, total: Long) = synchronized(lock) {
        addEvicting(stepCounterSeries, TimestampedLong(tsMs, total))
    }

    fun addGpsPoint(tsMs: Long, lat: Double, lon: Double, accuracyM: Float) = synchronized(lock) {
        addEvicting(gpsSeries, GpsPoint(tsMs, lat, lon, accuracyM))
    }

    fun snapshot(): PhoneSensorSnapshot = synchronized(lock) {
        PhoneSensorSnapshot(
            pressureSeries = ArrayDeque(pressureSeries),
            accelMagnitudeSeries = ArrayDeque(accelMagnitudeSeries),
            lightLuxSeries = ArrayDeque(lightLuxSeries),
            ambientTempSeries = ArrayDeque(ambientTempSeries),
            stepCounterSeries = ArrayDeque(stepCounterSeries),
            gpsSeries = ArrayDeque(gpsSeries)
        )
    }

    internal fun clearForTests() = synchronized(lock) {
        pressureSeries.clear()
        accelMagnitudeSeries.clear()
        lightLuxSeries.clear()
        ambientTempSeries.clear()
        stepCounterSeries.clear()
        gpsSeries.clear()
        capacity = DEFAULT_CAPACITY
    }

    internal fun setCapacityForTests(newCapacity: Int) = synchronized(lock) {
        require(newCapacity > 0) { "capacity must be positive" }
        capacity = newCapacity
        trimToCapacity(pressureSeries)
        trimToCapacity(accelMagnitudeSeries)
        trimToCapacity(lightLuxSeries)
        trimToCapacity(ambientTempSeries)
        trimToCapacity(stepCounterSeries)
        trimToCapacity(gpsSeries)
    }

    private fun addTimestampedDouble(series: ArrayDeque<TimestampedDouble>, point: TimestampedDouble) = synchronized(lock) {
        addEvicting(series, point)
    }

    private fun <T> addEvicting(series: ArrayDeque<T>, point: T) {
        series.addLast(point)
        trimToCapacity(series)
    }

    private fun <T> trimToCapacity(series: ArrayDeque<T>) {
        while (series.size > capacity) {
            series.removeFirst()
        }
    }
}
