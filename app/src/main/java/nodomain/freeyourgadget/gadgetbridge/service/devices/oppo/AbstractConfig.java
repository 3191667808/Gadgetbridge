/*  Copyright (C) 2026 NTeditor

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oppo;

import androidx.annotation.NonNull;
import android.content.Context;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.devices.oppo.OppoHeadphonesCoordinator;
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEvent;
import nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs;

public abstract class AbstractConfig {
    private Context mContext;
    private GBDevice mDevice;

    public AbstractConfig(@NonNull final Context context, @NonNull final GBDevice device) {
        mContext = context;
        mDevice = device;
    }

    @NonNull
    protected Context getContext() {
        return mContext;
    }

    protected void setContext(@NonNull final Context context, @NonNull final GBDevice device) {
        mContext = context;
        mDevice = device;
    }

    @NonNull
    protected GBDevice getDevice() {
        return mDevice;
    }

    @NonNull
    protected DevicePrefs getDevicePrefs() {
        return GBApplication.getDevicePrefs(mDevice);
    }

    @NonNull
    public OppoHeadphonesCoordinator getCoordinator() {
        return (OppoHeadphonesCoordinator) getDevice().getDeviceCoordinator();
    }
}
