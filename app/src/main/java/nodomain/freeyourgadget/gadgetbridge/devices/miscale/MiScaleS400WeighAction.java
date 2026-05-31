/*  Copyright (C) 2026 Viktor Karpov

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
package nodomain.freeyourgadget.gadgetbridge.devices.miscale;

import android.content.Context;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

public class MiScaleS400WeighAction implements DeviceCardAction {
    @Override
    public int getIcon(final GBDevice device) {
        return R.drawable.ic_refresh;
    }

    @Override
    public String getDescription(final GBDevice device, final Context context) {
        return context.getString(R.string.miscale_s400_action_scan_description);
    }

    @Override
    public String getLabel(final GBDevice device, final Context context) {
        return context.getString(R.string.miscale_s400_action_weigh_now);
    }

    @Override
    public boolean isVisible(final GBDevice device) {
        return true;
    }

    @Override
    public void onClick(final GBDevice device, final Context context) {
        GBApplication.deviceService(device).connect();
    }
}
