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
package nodomain.freeyourgadget.gadgetbridge.devices.oneplus

import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.deviceSettings
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice

fun onePlusBuds4DeviceSettings(device: GBDevice): DeviceSettingsSpec = deviceSettings {
    xmlScreen(
        DeviceSpecificSettingsScreen.TOUCH_OPTIONS,
        R.xml.devicesettings_oneplus_buds4_touch_options,
        R.xml.devicesettings_oneplus_buds4_touch_options_anc,
    )
    xmlScreen(
        DeviceSpecificSettingsScreen.AUDIO,
        R.xml.devicesettings_oneplus_buds4_audio,
        R.xml.devicesettings_oneplus_buds4_equalizer,
    )
    xmlScreen(
        DeviceSpecificSettingsScreen.CONNECTION,
        R.xml.devicesettings_oneplus_buds4_connection_features,
    )
    xmlScreen(
        DeviceSpecificSettingsScreen.CALLS_AND_NOTIFICATIONS,
        R.xml.devicesettings_headphones,
    )
}
