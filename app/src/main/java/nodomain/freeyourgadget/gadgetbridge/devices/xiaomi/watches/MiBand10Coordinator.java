/*  Copyright (C) 2025 José Rebelo

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.watches;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.xiaomi.XiaomiCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class MiBand10Coordinator extends XiaomiCoordinator {
    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_miband10;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^Xiaomi Smart Band 10 [0-9A-F]{4}$");
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_miband6;
    }

    @Override
    public ConnectionType getConnectionType() {
        return ConnectionType.BT_CLASSIC;
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public DeviceCoordinator.DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceCoordinator.DeviceKind.FITNESS_BAND;
    }

    @Override
    public int getAlarmIdOffset() {
        return 0;
    }

    @Override
    public boolean supportsSleepBreathingRateConfig(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsAbnormalCardiacAlert(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public int getStressAllDayTrackingEnabledValue() {
        return 2;
    }

    @Override
    public int getSpo2AllDayTrackingEnabledValue() {
        return 2;
    }
}
