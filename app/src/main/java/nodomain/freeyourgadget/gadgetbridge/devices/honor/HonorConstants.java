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

import java.util.UUID;

/**
 * Transport UUIDs of the newer Honor protocol variant. Honor devices speaking it do not use the
 * Huawei FE86/FE01/FE02 GATT set nor the Huawei SDP service from {@code HuaweiConstants}, but the
 * vendor-specific ones below.
 * <p>
 * Devices are routed onto these UUIDs by extending {@link HonorLECoordinator} (BTLE) or
 * {@link HonorBRCoordinator} (BT classic), which select {@code HonorLESupport} /
 * {@code HonorBRSupport} and with them {@code HonorSupportProvider}. Older Honor devices that
 * speak the plain Huawei protocol keep extending the Huawei coordinators.
 */
public final class HonorConstants {

    private HonorConstants() {
    }

    public static final UUID UUID_SERVICE_HONOR_SERVICE = UUID.fromString("c5f0ad48-cc27-48fd-9f67-8dc8e8f4a9cb");
    public static final UUID UUID_CHARACTERISTIC_HONOR_WRITE = UUID.fromString("2a5fc8bd-3de4-43cb-9449-354bcdeccc70");
    public static final UUID UUID_CHARACTERISTIC_HONOR_READ = UUID.fromString("a563d757-b216-48bc-8a0f-e8dde1f916aa");

    public static final UUID UUID_SERVICE_HONOR_SDP = UUID.fromString("C9770A18-4C3D-453A-8AAF-D7EC7BBD2785");
}
