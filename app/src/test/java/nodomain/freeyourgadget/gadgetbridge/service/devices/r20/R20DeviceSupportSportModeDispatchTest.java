/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.r20;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class R20DeviceSupportSportModeDispatchTest {

    @Test
    public void mapsKnownSportModesToActivityKinds() {
        assertEquals(ActivityKind.WALKING.getCode(), R20DeviceSupport.mapSportModeActivityKind(1));
        assertEquals(ActivityKind.RUNNING.getCode(), R20DeviceSupport.mapSportModeActivityKind(2));
        assertEquals(ActivityKind.CYCLING.getCode(), R20DeviceSupport.mapSportModeActivityKind(3));
        assertEquals(ActivityKind.CLIMBING.getCode(), R20DeviceSupport.mapSportModeActivityKind(4));
        assertEquals(ActivityKind.HIKING.getCode(), R20DeviceSupport.mapSportModeActivityKind(5));
        assertEquals(ActivityKind.SWIMMING.getCode(), R20DeviceSupport.mapSportModeActivityKind(6));
        assertEquals(ActivityKind.TREADMILL.getCode(), R20DeviceSupport.mapSportModeActivityKind(7));
        assertEquals(ActivityKind.INDOOR_CYCLING.getCode(), R20DeviceSupport.mapSportModeActivityKind(8));
        assertEquals(ActivityKind.YOGA.getCode(), R20DeviceSupport.mapSportModeActivityKind(9));
        assertEquals(ActivityKind.ROWING_MACHINE.getCode(), R20DeviceSupport.mapSportModeActivityKind(10));
        assertEquals(ActivityKind.ELLIPTICAL_TRAINER.getCode(), R20DeviceSupport.mapSportModeActivityKind(11));
        assertEquals(ActivityKind.FITNESS_EXERCISES.getCode(), R20DeviceSupport.mapSportModeActivityKind(12));
    }

    @Test
    public void mapsUnknownSportModeToGenericExercise() {
        assertEquals(ActivityKind.EXERCISE.getCode(), R20DeviceSupport.mapSportModeActivityKind(0));
        assertEquals(ActivityKind.EXERCISE.getCode(), R20DeviceSupport.mapSportModeActivityKind(99));
    }
}
