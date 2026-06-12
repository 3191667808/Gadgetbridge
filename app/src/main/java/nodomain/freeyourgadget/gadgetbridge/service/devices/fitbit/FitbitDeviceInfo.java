/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import java.nio.charset.StandardCharsets;

import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

final class FitbitDeviceInfo {
    boolean hasOnCharger;
    boolean hasVoltage;
    boolean hasBatteryLevel;
    boolean hasProductId;
    int onCharger = ActivitySample.NOT_MEASURED;
    int voltage = ActivitySample.NOT_MEASURED;
    int batteryLevel = ActivitySample.NOT_MEASURED;
    int productId = ActivitySample.NOT_MEASURED;
    String gitDescribe = "";

    private FitbitDeviceInfo() {
    }

    static FitbitDeviceInfo parse(final byte[] payload) {
        final FitbitDeviceInfo info = new FitbitDeviceInfo();
        int pos = 0;
        while (pos < payload.length) {
            final FitbitProtobufReader.Varint tag = FitbitProtobufReader.readVarint(payload, pos);
            pos = tag.nextOffset;

            final int fieldNumber = (int) (tag.value >> 3);
            final int wireType = (int) (tag.value & 0x07);
            if (wireType == 0) {
                final FitbitProtobufReader.Varint value = FitbitProtobufReader.readVarint(payload, pos);
                pos = value.nextOffset;
                info.setVarintField(fieldNumber, value.value);
                continue;
            }

            if (wireType == 2) {
                final FitbitProtobufReader.Varint length = FitbitProtobufReader.readVarint(payload, pos);
                final int valueOffset = length.nextOffset;
                final int nextOffset = FitbitProtobufReader.checkedOffset(payload, valueOffset, (int) length.value);
                if (fieldNumber == 15) {
                    info.gitDescribe = new String(payload, valueOffset, (int) length.value, StandardCharsets.UTF_8);
                }
                pos = nextOffset;
                continue;
            }

            pos = FitbitProtobufReader.skipField(payload, pos, wireType);
        }
        return info;
    }

    private void setVarintField(final int fieldNumber, final long value) {
        final int intValue = (int) value;
        switch (fieldNumber) {
            case 10:
                hasOnCharger = true;
                onCharger = intValue;
                break;
            case 12:
                hasVoltage = true;
                voltage = intValue;
                break;
            case 13:
                hasBatteryLevel = true;
                batteryLevel = intValue;
                break;
            case 17:
                hasProductId = true;
                productId = intValue;
                break;
            default:
                break;
        }
    }

    @Override
    public String toString() {
        return "onCharger=" + fieldValue(hasOnCharger, onCharger)
                + ", voltage=" + fieldValue(hasVoltage, voltage)
                + ", batteryLevel=" + fieldValue(hasBatteryLevel, batteryLevel)
                + ", productId=" + fieldValue(hasProductId, productId)
                + ", gitDescribe=" + gitDescribe;
    }

    private static String fieldValue(final boolean hasValue, final int value) {
        return hasValue ? Integer.toString(value) : "(missing)";
    }
}
