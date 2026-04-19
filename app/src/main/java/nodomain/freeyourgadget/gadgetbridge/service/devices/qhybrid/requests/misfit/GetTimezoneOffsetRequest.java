/*  This file is in the Public Domain.

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.misfit;

import android.bluetooth.BluetoothGattCharacteristic;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import nodomain.freeyourgadget.gadgetbridge.service.devices.qhybrid.requests.Request;

public class GetTimezoneOffsetRequest extends Request {
    public short offsetMinutes = 0;

    @Override
    public void handleResponse(BluetoothGattCharacteristic characteristic, byte[] value) {
        // Response: 03 12 01 <offset_LE16>
        if (value.length < 5) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        offsetMinutes = buffer.getShort(3);
    }

    @Override
    public byte[] getStartSequence() {
        return new byte[]{1, 18, 1}; // 01 12 01
    }
}
