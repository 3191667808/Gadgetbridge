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

import java.util.regex.Pattern;

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator.DeviceKind;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class SR08RingCoordinator extends AbstractKeepFitCoordinator {

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_keepfit_sr08;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_smartring;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^(SR08|SR16)$");
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.RING;
    }
}
