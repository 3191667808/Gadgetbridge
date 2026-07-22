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
package nodomain.freeyourgadget.gadgetbridge.devices.ycbt;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class YcbtActivitySampleProviderTest {
    @Test
    public void mapsPersistedSleepStagesToActivityKinds() {
        assertEquals(ActivityKind.DEEP_SLEEP, YcbtActivitySampleProvider.sleepStageToActivityKind(1));
        assertEquals(ActivityKind.LIGHT_SLEEP, YcbtActivitySampleProvider.sleepStageToActivityKind(2));
        assertEquals(ActivityKind.AWAKE_SLEEP, YcbtActivitySampleProvider.sleepStageToActivityKind(3));
        assertEquals(ActivityKind.REM_SLEEP, YcbtActivitySampleProvider.sleepStageToActivityKind(4));
        assertEquals(ActivityKind.UNKNOWN, YcbtActivitySampleProvider.sleepStageToActivityKind(0));
    }
}
