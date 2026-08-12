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

import androidx.annotation.NonNull;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiBRCoordinator;
import nodomain.freeyourgadget.gadgetbridge.service.devices.honor.HonorBRSupport;

/**
 * BT classic coordinator for Honor devices speaking the newer Honor protocol: the Honor SDP
 * service of {@link HonorConstants} instead of the Huawei one, and Honor authentication (simple or
 * PAKE, never HiChain / HiChain Lite).
 * <p>
 * Older Honor watches that speak the plain Huawei protocol stay on {@link HuaweiBRCoordinator}.
 */
public abstract class HonorBRCoordinator extends HuaweiBRCoordinator implements HonorCoordinator {

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return HonorBRSupport.class;
    }
}
