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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WithingsBaseDeviceSupportTest {
    @Test
    public void smartWakeupIntervalIsClampedToDeviceLimit() {
        assertEquals(60, WithingsBaseDeviceSupport.clampSmartWakeupInterval(90, 60));
        assertEquals(45, WithingsBaseDeviceSupport.clampSmartWakeupInterval(45, 60));
    }

    @Test
    public void smartWakeupIntervalDefaultsToTwentyMinutes() {
        assertEquals(20, WithingsBaseDeviceSupport.clampSmartWakeupInterval(null, 60));
        assertEquals(20, WithingsBaseDeviceSupport.clampSmartWakeupInterval(0, 60));
    }
}
