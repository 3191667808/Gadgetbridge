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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt;

import android.app.Activity;
import android.bluetooth.le.ScanFilter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt.YcbtDeviceSupport;

public class R10MCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile(Pattern.quote(YcbtConstants.R10M_DEVICE_NAME));
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        return Collections.singletonList(
                new ScanFilter.Builder().setDeviceName(YcbtConstants.R10M_DEVICE_NAME).build()
        );
    }

    @Override
    public boolean isConnectable() {
        return true;
    }

    @Override
    public String getManufacturer() {
        return "YCBT";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return YcbtDeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_ycbt_r10m;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_smartring;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Nullable
    @Override
    public Class<? extends Activity> getPairingActivity() {
        return YcbtPairingActivity.class;
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public int getBatteryCount(final GBDevice device) {
        return 0;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.RING;
    }
}
