/*  Copyright (C) 2025

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
package nodomain.freeyourgadget.gadgetbridge.devices.marshall;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.marshall.acton3.MarshallActon3Support;

public class MarshallActon3Coordinator extends AbstractMarshallCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile(".*ACTON III \\[LE\\]");
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_marshall_acton_3;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return MarshallActon3Support.class;
    }

    @Override
    public DeviceCoordinator.DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.SPEAKER;
    }
}
