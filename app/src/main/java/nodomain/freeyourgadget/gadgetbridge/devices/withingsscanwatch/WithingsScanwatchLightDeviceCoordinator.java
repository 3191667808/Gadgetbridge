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
package nodomain.freeyourgadget.gadgetbridge.devices.withingsscanwatch;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.activities.charts.DefaultChartsProvider;
import nodomain.freeyourgadget.gadgetbridge.activities.charts.DeviceChartsProvider;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsCustomizer;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingsscanwatch.WithingsScanwatchLightDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.WithingsUUIDs;

public class WithingsScanwatchLightDeviceCoordinator extends WithingsScanwatchDeviceCoordinator {
    @Override
    public Pattern getSupportedDeviceName() {
        return Pattern.compile("(?i)^ScanWatch Light.*");
    }

    @Override
    public boolean supports(@NonNull final GBDeviceCandidate candidate) {
        return candidate.supportsService(WithingsUUIDs.SCANWATCH_LIGHT.WITHINGS_SERVICE_UUID);
    }

    @Override
    public int getOrderPriority() {
        return -10;
    }

    @Override
    public boolean supportsSpo2(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsRespiratoryRate(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsSleepRespiratoryRate(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsSleepBreathingQuality(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public DeviceChartsProvider getChartsProvider() {
        return DefaultChartsProvider.INSTANCE;
    }

    @Override
    public DeviceSpecificSettingsCustomizer getDeviceSpecificSettingsCustomizer(final GBDevice device) {
        return new WithingsScanwatchLightSettingsCustomizer();
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return WithingsScanwatchLightDeviceSupport.class;
    }
}
