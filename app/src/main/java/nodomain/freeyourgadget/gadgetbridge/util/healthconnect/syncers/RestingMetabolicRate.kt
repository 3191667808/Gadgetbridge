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

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractTimeSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.DefaultRestingMetabolicRateProvider
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.RestingMetabolicRateSample
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger("RestingMetabolicRate")

/** A minute of resting metabolism, as a fraction of the day. */
internal const val MINUTES_PER_DAY = 1440.0

/**
 * The energy the body spends doing nothing.
 *
 * Health Connect consumers (Google Health among them) read TOTAL_CALORIES_BURNED and ignore
 * ActiveCaloriesBurnedRecord, so a device that only reports active calories shows up as a flat
 * formula estimate rather than as its real activity. Gadgetbridge's own Calories chart already
 * closes that gap by adding the resting rate on top of the active figure; reusing the same provider
 * here means Health Connect agrees with what the app displays instead of inventing a second answer.
 *
 * Devices that measure their own basal rate supply it through the coordinator; the rest fall back
 * to Mifflin-St Jeor over the user's profile.
 */
internal object RestingMetabolicRate {
    /** Resting metabolic rate in kcal/day around [atMillis], or null if it cannot be determined. */
    fun kcalPerDay(gbDevice: GBDevice, atMillis: Long): Int? {
        return try {
            GBApplication.acquireDbReadOnly().use { db ->
                val coordinator = gbDevice.deviceCoordinator
                @Suppress("UNCHECKED_CAST")
                val provider = coordinator.getRestingMetabolicRateProvider(gbDevice, db.daoSession)
                        as? AbstractTimeSampleProvider<out RestingMetabolicRateSample>

                val sample = provider?.getLastSampleBefore(atMillis)
                    ?: DefaultRestingMetabolicRateProvider(gbDevice, db.daoSession).getLastSampleBefore(atMillis)

                sample?.restingMetabolicRate?.takeIf { it > 0 }
            }
        } catch (e: Exception) {
            LOG.warn("Could not determine resting metabolic rate for '{}'", gbDevice.aliasOrName, e)
            null
        }
    }

    /** Resting kcal accrued over [durationSeconds] at a rate of [kcalPerDay]. */
    fun kcalOver(kcalPerDay: Int, durationSeconds: Long): Double =
        kcalPerDay * (durationSeconds / 86_400.0)
}
