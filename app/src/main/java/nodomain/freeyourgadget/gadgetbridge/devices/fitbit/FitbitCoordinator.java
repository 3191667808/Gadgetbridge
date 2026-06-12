/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.devices.fitbit;

import android.app.Activity;
import android.bluetooth.le.ScanFilter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Collection;
import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit.FitbitDeviceSupport;

public class FitbitCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    public boolean supports(@NonNull final GBDeviceCandidate candidate) {
        if (candidate.getServiceUuids().length > 0) {
            if (candidate.supportsService(FitbitConstants.FITBIT_GATTLINK_ADVERTISING_SERVICE)
                    || candidate.supportsService(FitbitConstants.GATTLINK_SERVICE)) {
                return true;
            }
        }

        final String name = candidate.getName().toLowerCase(Locale.ROOT);
        return name.contains("fitbit")
                || name.startsWith("charge ")
                || name.startsWith("versa ")
                || name.startsWith("sense ")
                || name.startsWith("inspire ")
                || name.startsWith("luxe ")
                || name.startsWith("ace ");
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        return Collections.emptyList();
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Nullable
    @Override
    public Class<? extends Activity> getPairingActivity() {
        return FitbitPairingActivity.class;
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public String getManufacturer() {
        return "Fitbit";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return FitbitDeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_fitbit;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.FITNESS_BAND;
    }
}
