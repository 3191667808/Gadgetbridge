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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.ListEntry
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings

fun ycbtDeviceSettings(): DeviceSettingsSpec = deviceSettings {
    list(
        key = DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL,
        title = R.string.prefs_title_heartrate_measurement_interval,
        icon = R.drawable.ic_heartrate,
        entries = listOf(
            ListEntry.Res("0", R.string.off),
            ListEntry.Res("1800", R.string.interval_thirty_minutes),
            ListEntry.Res("3600", R.string.interval_1_hour),
        ),
        defaultValue = "0",
    )
    switchSetting(
        key = DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING,
        title = R.string.prefs_spo2_monitoring_title,
        summary = R.string.prefs_spo2_monitoring_description,
        icon = R.drawable.ic_spo2,
        defaultValue = false,
    )
    list(
        key = DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL,
        title = R.string.pref_title_time_interval,
        entries = listOf(
            ListEntry.Res("1800", R.string.interval_thirty_minutes),
            ListEntry.Res("3600", R.string.interval_1_hour),
        ),
        defaultValue = "1800",
        dependency = DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING,
    )
}
