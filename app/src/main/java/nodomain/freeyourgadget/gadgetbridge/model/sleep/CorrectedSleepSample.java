/*  Copyright (C) 2026 Freeyourgadget

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
package nodomain.freeyourgadget.gadgetbridge.model.sleep;

import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

public class CorrectedSleepSample implements ActivitySample {
    private final int timestamp;
    private final long deviceId;
    private final long userId;
    private final ActivityKind kind;
    private final float intensity;
    private int heartRate;

    public CorrectedSleepSample(final int timestamp,
                                final long deviceId,
                                final long userId,
                                final ActivityKind kind,
                                final float intensity,
                                final int heartRate) {
        this.timestamp = timestamp;
        this.deviceId = deviceId;
        this.userId = userId;
        this.kind = kind;
        this.intensity = intensity;
        this.heartRate = heartRate;
    }

    @Override
    public SampleProvider<?> getProvider() {
        return null;
    }

    @Override
    public ActivityKind getKind() {
        return kind;
    }

    @Override
    public int getRawKind() {
        return kind.getCode();
    }

    @Override
    public float getIntensity() {
        return intensity;
    }

    @Override
    public int getRawIntensity() {
        return intensity >= 0 ? Math.round(intensity) : NOT_MEASURED;
    }

    @Override
    public int getSteps() {
        return NOT_MEASURED;
    }

    @Override
    public int getDistanceCm() {
        return NOT_MEASURED;
    }

    @Override
    public int getActiveCalories() {
        return NOT_MEASURED;
    }

    @Override
    public int getHeartRate() {
        return heartRate;
    }

    @Override
    public void setHeartRate(final int heartRate) {
        this.heartRate = heartRate;
    }

    @Override
    public int getTimestamp() {
        return timestamp;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public long getUserId() {
        return userId;
    }
}
