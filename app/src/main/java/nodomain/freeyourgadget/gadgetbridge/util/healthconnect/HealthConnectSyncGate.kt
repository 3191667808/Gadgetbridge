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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs

/**
 * Aborts the current sync run outright, as opposed to failing one data type. Syncers that catch
 * broad exceptions to keep going after a bad record must rethrow this.
 */
sealed class HealthConnectAbortException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The user turned Health Connect writing off. */
class HealthConnectSyncDisabledException(message: String) : HealthConnectAbortException(message)

/**
 * The single authority on whether Gadgetbridge may write to Health Connect.
 *
 * The gate is consulted inside [HealthConnectSupport.insert], not at the entry points, so a toggle
 * flipped off mid-run stops the sync at the next chunk instead of at the next sync. It reads the
 * preferences directly and is the only owner of that decision: callers must not re-implement the
 * check, or the two copies drift and one of them ends up unwired.
 */
object HealthConnectSyncGate {
    val isEnabled: Boolean
        get() = GBApplication.getPrefs().getBoolean(GBPrefs.HEALTH_CONNECT_ENABLED, false)

    val isSyncOnEventEnabled: Boolean
        get() = GBApplication.getPrefs().getBoolean(GBPrefs.HEALTH_CONNECT_SYNC_ON_EVENT, false)

    val isDetailedWorkoutSyncEnabled: Boolean
        get() = GBApplication.getPrefs().getBoolean(GBPrefs.HEALTH_CONNECT_DETAILED_WORKOUT_SYNC, true)

    fun selectedDevices(): Set<String> =
        GBApplication.getPrefs().getStringSet(GBPrefs.HEALTH_CONNECT_DEVICE_SELECTION, emptySet())
            ?: emptySet()

    @JvmStatic
    fun isDeviceEnabled(address: String): Boolean =
        selectedDevices().any { it.equals(address, ignoreCase = true) }

    fun requireEnabled() {
        if (!isEnabled) {
            throw HealthConnectSyncDisabledException("Health Connect writing is disabled in Gadgetbridge")
        }
    }

    fun requireDeviceEnabled(address: String) {
        requireEnabled()
        if (!isDeviceEnabled(address)) {
            throw HealthConnectSyncDisabledException("Device $address is not selected for Health Connect")
        }
    }
}
