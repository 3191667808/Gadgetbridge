/*  Copyright (C) 2026 Ariel Saghiv

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

package nodomain.freeyourgadget.gadgetbridge.entities;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public abstract class AbstractVRingR26ActivitySample extends AbstractActivitySample {

    public abstract Integer getDistance();
    public abstract Integer getCalories();

    @Override
    public ActivityKind getKind() {
        switch (getRawKind()) {
            case 100:
                return ActivityKind.NOT_WORN;
            case 1:
                return ActivityKind.ACTIVITY;
            default:
                return ActivityKind.UNKNOWN;
        }
    }

    @Override
    public float getIntensity() {
        // rawIntensity is steps/min; cap at 120 for full intensity.
        return Math.min(1.0f, getRawIntensity() / 120.0f);
    }

    @Override
    public int getDistanceCm() {
        Integer d = getDistance();
        if (d == null || d < 0) return -1;
        return d * 100;  // stored in meters
    }

    @Override
    public int getActiveCalories() {
        Integer c = getCalories();
        if (c == null || c < 0) return -1;
        return c;
    }
}
