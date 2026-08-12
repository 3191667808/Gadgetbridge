/*  Copyright (C) 2025 Vitalii Tomin

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
package nodomain.freeyourgadget.gadgetbridge.devices.honor;

import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiConstants;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiLECoordinator;
import nodomain.freeyourgadget.gadgetbridge.service.devices.honor.HonorLESupport;

/**
 * BTLE coordinator for Honor devices speaking the newer Honor protocol: the GATT service and
 * characteristics of {@link HonorConstants} instead of the Huawei ones, and Honor authentication
 * (simple or PAKE, never HiChain / HiChain Lite).
 * <p>
 * Older Honor bands that speak the plain Huawei protocol stay on {@link HuaweiLECoordinator}.
 */
public abstract class HonorLECoordinator extends HuaweiLECoordinator implements HonorCoordinator {

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return HonorLESupport.class;
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        // Filters are OR'ed: accept the Honor service as well as the Huawei one, as these devices
        // are not consistent about which of the two they advertise.
        final List<ScanFilter> filters = new ArrayList<>(2);
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(HonorConstants.UUID_SERVICE_HONOR_SERVICE))
                .build());
        filters.add(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(HuaweiConstants.UUID_SERVICE_HUAWEI_SERVICE))
                .build());
        return filters;
    }
}
