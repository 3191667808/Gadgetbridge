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

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public abstract class AbstractMarshallCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    public String getManufacturer() {
        return "Marshall";
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_ASK;
    }

    @Nullable
    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(final GBDevice device) {
        final DeviceSpecificSettings settings = new DeviceSpecificSettings();
        settings.addRootScreen(R.xml.devicesettings_marshall_acton);
        return settings;
    }

    @Override
    public List<DeviceCardAction> getCustomActions() {
        return Arrays.asList(
                new MarshallCardActions.VolumeAction(),
                new MarshallCardActions.SourceAction(),
                new MarshallCardActions.PlacementAction()
        );
    }

    @Override
    public int getBatteryCount(final GBDevice device) {
        return 0;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_speaker;
    }
}
