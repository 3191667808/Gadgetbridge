/*  Copyright (C) 2025 Daniel Giritzer, MSc (giri@nwrk.biz)

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
package nodomain.freeyourgadget.gadgetbridge.devices.keepfit;

import java.util.List;
import java.util.regex.Pattern;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.CameraActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator.DeviceKind;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class Z10Coordinator extends AbstractKeepFitCoordinator {

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_keepfit_z10;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^Z10$");
    }

    @Override
    public boolean supportsWeather(final GBDevice device) {
        return true;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(GBDevice device) {
        return new int[]{
                R.xml.devicesettings_header_time,
                R.xml.devicesettings_timeformat,

                R.xml.devicesettings_inactivity_extended,
                R.xml.devicesettings_hydration_reminder_dnd,
                R.xml.devicesettings_donotdisturb_no_auto,
                R.xml.devicesettings_sleep_time,
                R.xml.devicesettings_vibrations_enable,
        };
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.FITNESS_BAND;
    }
}
