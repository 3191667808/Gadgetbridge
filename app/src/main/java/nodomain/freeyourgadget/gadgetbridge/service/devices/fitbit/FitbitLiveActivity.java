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

import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

final class FitbitLiveActivity {
    int timestamp = ActivitySample.NOT_MEASURED;
    int steps = ActivitySample.NOT_MEASURED;
    int distance = ActivitySample.NOT_MEASURED;
    int calories = ActivitySample.NOT_MEASURED;
    int elevation = ActivitySample.NOT_MEASURED;
    int vaMinutes = ActivitySample.NOT_MEASURED;
    int heartRate = ActivitySample.NOT_MEASURED;
    int heartRateConfidence = ActivitySample.NOT_MEASURED;
    int dailyZoneMinutes = ActivitySample.NOT_MEASURED;
    int weeklyZoneMinutes = ActivitySample.NOT_MEASURED;

    private FitbitLiveActivity() {
    }

    static FitbitLiveActivity parse(final byte[] payload) {
        final FitbitLiveActivity activity = new FitbitLiveActivity();
        int pos = 0;
        while (pos < payload.length) {
            final FitbitProtobufReader.Varint tag = FitbitProtobufReader.readVarint(payload, pos);
            pos = tag.nextOffset;

            final int fieldNumber = (int) (tag.value >> 3);
            final int wireType = (int) (tag.value & 0x07);
            if (wireType == 0) {
                final FitbitProtobufReader.Varint value = FitbitProtobufReader.readVarint(payload, pos);
                pos = value.nextOffset;
                activity.setVarintField(fieldNumber, value.value);
                continue;
            }

            pos = FitbitProtobufReader.skipField(payload, pos, wireType);
        }
        return activity;
    }

    private void setVarintField(final int fieldNumber, final long value) {
        final int intValue = (int) value;
        switch (fieldNumber) {
            case 1:
                timestamp = intValue;
                break;
            case 2:
                steps = intValue;
                break;
            case 3:
                distance = intValue;
                break;
            case 4:
                calories = intValue;
                break;
            case 5:
                elevation = intValue;
                break;
            case 6:
                vaMinutes = intValue;
                break;
            case 7:
                heartRate = intValue;
                break;
            case 8:
                heartRateConfidence = intValue;
                break;
            case 9:
                dailyZoneMinutes = intValue;
                break;
            case 10:
                weeklyZoneMinutes = intValue;
                break;
            default:
                break;
        }
    }

    @Override
    public String toString() {
        return "timestamp=" + timestamp
                + ", steps=" + steps
                + ", distance=" + distance
                + ", calories=" + calories
                + ", elevation=" + elevation
                + ", vaMinutes=" + vaMinutes
                + ", heartRate=" + heartRate
                + ", heartRateConfidence=" + heartRateConfidence
                + ", dailyZoneMinutes=" + dailyZoneMinutes
                + ", weeklyZoneMinutes=" + weeklyZoneMinutes;
    }
}
