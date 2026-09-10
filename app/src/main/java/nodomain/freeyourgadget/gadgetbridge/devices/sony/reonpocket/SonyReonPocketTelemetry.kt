/*  Copyright (C) 2026 David Giron

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
package nodomain.freeyourgadget.gadgetbridge.devices.sony.reonpocket

/**
 * Sensor fields used by the official app's device information screen.
 *
 * Verified against REON POCKET 2.3.0: NotifyDeviceSensorDataEntity decodes TEC1 at offset 3
 * and fin temperature at offset 5; DeviceInfoPresenter passes those two fields to the view.
 * DeviceTemperatureView.setFinTemperature applies the thresholds below. The indicator is
 * therefore a temperature-derived level, not a measurement of dissipated power in watts.
 */
object SonyReonPocketTelemetry {
    data class Reading(val panelTemperatureCelsius: Double?, val heatDissipationLevel: Int?)

    fun parse(value: ByteArray): Reading? {
        // Read responses (0) and device notifications (1) share the same 18-byte layout.
        // Tag frames (2) must never overwrite the device readings.
        if (value.size < 18 || value[0].toInt() !in 0..1) return null
        val panel = temperature(value, 3) // TEC1, not the surface sensor at offset 1.
        val fin = temperature(value, 5)
        return Reading(panel, fin?.let { heatDissipationLevel(it) })
    }

    private fun temperature(value: ByteArray, offset: Int): Double? {
        val raw = ((value[offset].toInt() and 0xff) shl 8) or (value[offset + 1].toInt() and 0xff)
        if (raw == 0x8000 || raw == 0xffff) return null
        return raw.toShort() / 100.0
    }

    // The app derives its five ventilation indicators from fin temperature, not fan speed
    // or the selected cooling intensity. Thresholds are lower-inclusive.
    private fun heatDissipationLevel(finCelsius: Double): Int = when {
        finCelsius < 32 -> 1
        finCelsius < 36 -> 2
        finCelsius < 40 -> 3
        finCelsius < 43 -> 4
        else -> 5
    }
}
