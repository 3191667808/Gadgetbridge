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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

    @Test
    public void requiresWriteWithoutResponseForCapturedCommandTransport() {
        assertTrue(YcbtDeviceSupport.supportsWriteWithoutResponse(
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        ));
        assertTrue(YcbtDeviceSupport.supportsWriteWithoutResponse(
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE
        ));
        assertFalse(YcbtDeviceSupport.supportsWriteWithoutResponse(BluetoothGattCharacteristic.PROPERTY_WRITE));
        assertFalse(YcbtDeviceSupport.supportsWriteWithoutResponse(BluetoothGattCharacteristic.PROPERTY_INDICATE));
    }

    @Test
    public void latestRealtimeHeartRateRequestWinsAcrossPendingRepliesAndQuarantine() {
        final YcbtDeviceSupport.RealtimeHeartRateRequest pendingStartDisable =
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(false, false, false, true, true, null);
        assertEquals(YcbtDeviceSupport.RealtimeHeartRateRequestAction.DEFER,
                pendingStartDisable.getAction());
        assertEquals(Boolean.FALSE, pendingStartDisable.getDeferredTarget());

        final YcbtDeviceSupport.RealtimeHeartRateRequest quarantinedEnable =
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(true, false, true, false, false,
                        pendingStartDisable.getDeferredTarget());
        assertEquals(Boolean.TRUE, quarantinedEnable.getDeferredTarget());

        final YcbtDeviceSupport.RealtimeHeartRateRequest latestQuarantinedDisable =
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(false, false, true, false, false,
                        quarantinedEnable.getDeferredTarget());
        assertEquals(Boolean.FALSE, latestQuarantinedDisable.getDeferredTarget());

        final YcbtDeviceSupport.RealtimeHeartRateRequest reconciledDisable =
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(false, false, false, false, false,
                        latestQuarantinedDisable.getDeferredTarget());
        assertEquals(YcbtDeviceSupport.RealtimeHeartRateRequestAction.IGNORE, reconciledDisable.getAction());
        assertNull(reconciledDisable.getDeferredTarget());
    }

    @Test
    public void realtimeHeartRateRequestStartsAndStopsWhenUnblocked() {
        assertEquals(
                YcbtDeviceSupport.RealtimeHeartRateRequestAction.START,
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(true, false, false, false, false, null)
                        .getAction()
        );
        assertEquals(
                YcbtDeviceSupport.RealtimeHeartRateRequestAction.STOP,
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(false, true, false, false, false, null)
                        .getAction()
        );
    }
}
