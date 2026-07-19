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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothGattCharacteristic;

import org.junit.Test;

public class YcbtDeviceSupportTest {
    @Test
    public void requiresIndicateButAllowsNotifyAndIndicate() {
        assertTrue(YcbtDeviceSupport.supportsIndications(BluetoothGattCharacteristic.PROPERTY_INDICATE));
        assertTrue(YcbtDeviceSupport.supportsIndications(
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE
        ));
        assertFalse(YcbtDeviceSupport.supportsIndications(BluetoothGattCharacteristic.PROPERTY_NOTIFY));
    }
}
