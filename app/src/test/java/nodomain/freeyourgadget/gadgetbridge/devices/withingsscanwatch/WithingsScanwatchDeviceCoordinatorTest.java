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
package nodomain.freeyourgadget.gadgetbridge.devices.withingsscanwatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.WithingsUUIDs;

public class WithingsScanwatchDeviceCoordinatorTest {
    @Test
    public void lightServiceSelectsOnlyLightCoordinator() {
        final GBDeviceCandidate light = candidate("ScanWatch Light", WithingsUUIDs.SCANWATCH_LIGHT.WITHINGS_SERVICE_UUID);

        assertTrue(new WithingsScanwatchLightDeviceCoordinator().supports(light));
        assertFalse(new WithingsScanwatchDeviceCoordinator().supports(light));
    }

    @Test
    public void regularServiceSelectsFullCoordinator() {
        final GBDeviceCandidate scanwatch = candidate("ScanWatch", WithingsUUIDs.SCANWATCH.WITHINGS_SERVICE_UUID);

        assertFalse(new WithingsScanwatchLightDeviceCoordinator().supports(scanwatch));
        assertTrue(new WithingsScanwatchDeviceCoordinator().supports(scanwatch));
    }

    @Test
    public void lightDoesNotAdvertiseUnavailableHealthSensors() {
        final WithingsScanwatchLightDeviceCoordinator coordinator = new WithingsScanwatchLightDeviceCoordinator();

        assertFalse(coordinator.supportsSpo2(null));
        assertFalse(coordinator.supportsRespiratoryRate(null));
        assertFalse(coordinator.supportsSleepRespiratoryRate(null));
        assertFalse(coordinator.supportsSleepBreathingQuality(null));
        assertEquals(60, coordinator.getSmartWakeupMaxInterval(null));
    }

    @Test
    public void scanwatchExposesBreathingQualityWithoutRespiratoryRate() {
        final WithingsScanwatchDeviceCoordinator coordinator = new WithingsScanwatchDeviceCoordinator();

        assertFalse(coordinator.supportsRespiratoryRate(null));
        assertFalse(coordinator.supportsSleepRespiratoryRate(null));
        assertTrue(coordinator.supportsSleepBreathingQuality(null));
    }

    private static GBDeviceCandidate candidate(final String name, final UUID service) {
        return new GBDeviceCandidate(null, (short) 0, null, null) {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean supportsService(final UUID requestedService) {
                return service.equals(requestedService);
            }
        };
    }
}
