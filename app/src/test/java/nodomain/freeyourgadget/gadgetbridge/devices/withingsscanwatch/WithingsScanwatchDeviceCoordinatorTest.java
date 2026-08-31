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

import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.ShortcutAction;

public class WithingsScanwatchDeviceCoordinatorTest {
    @Test
    public void nameOnlyDiscoverySelectsOnlyTheMatchingCoordinator() {
        final WithingsScanwatchDeviceCoordinator scanwatchCoordinator = new WithingsScanwatchDeviceCoordinator();
        final WithingsScanwatchLightDeviceCoordinator lightCoordinator = new WithingsScanwatchLightDeviceCoordinator();
        final GBDeviceCandidate light = candidate("ScanWatch Light");
        final GBDeviceCandidate scanwatch = candidate("ScanWatch");
        final GBDeviceCandidate scanwatchCa = candidate("ScanWatch CA");

        assertTrue(lightCoordinator.supports(light));
        assertFalse(scanwatchCoordinator.supports(light));
        assertFalse(lightCoordinator.supports(scanwatch));
        assertTrue(scanwatchCoordinator.supports(scanwatch));
        assertFalse(lightCoordinator.supports(scanwatchCa));
        assertTrue(scanwatchCoordinator.supports(scanwatchCa));
    }

    @Test
    public void lightDoesNotAdvertiseUnavailableHealthSensors() {
        final WithingsScanwatchLightDeviceCoordinator coordinator = new WithingsScanwatchLightDeviceCoordinator();

        assertFalse(coordinator.supportsSpo2(null));
        assertFalse(coordinator.supportsEcgMeasurement(null));
        assertFalse(coordinator.supportsRespiratoryScan(null));
        assertFalse(coordinator.supportsRespiratoryRate(null));
        assertFalse(coordinator.supportsSleepRespiratoryRate(null));
        assertFalse(coordinator.supportsSleepBreathingQuality(null));
        assertEquals(60, coordinator.getSmartWakeupMaxInterval(null));
    }

    @Test
    public void scanwatchExposesBreathingQualityWithoutRespiratoryRate() {
        final WithingsScanwatchDeviceCoordinator coordinator = new WithingsScanwatchDeviceCoordinator();

        assertTrue(coordinator.supportsEcgMeasurement(null));
        assertTrue(coordinator.supportsSpo2(null));
        assertTrue(coordinator.supportsRespiratoryScan(null));
        assertFalse(coordinator.supportsRespiratoryRate(null));
        assertFalse(coordinator.supportsSleepRespiratoryRate(null));
        assertTrue(coordinator.supportsSleepBreathingQuality(null));
    }

    @Test
    public void lightNormalisesPersistedUnsupportedScreenAndShortcutValues() {
        assertEquals("date,sleep,steps", WithingsScanwatchLightSettingsCustomizer.normaliseLightScreens("ecg,sleep,spo2,ecg,steps,spo2"));
        assertEquals("date,sleep", WithingsScanwatchLightSettingsCustomizer.normaliseLightScreens("date,sleep,ecg,date,spo2"));
        assertEquals(Byte.toString(ShortcutAction.ACTION_NONE),
                WithingsScanwatchLightSettingsCustomizer.normaliseLightShortcut(Byte.toString(ShortcutAction.ACTION_ECG_MEAS)));
        assertEquals(Byte.toString(ShortcutAction.ACTION_NONE),
                WithingsScanwatchLightSettingsCustomizer.normaliseLightShortcut(Byte.toString(ShortcutAction.ACTION_SPO2_MEAS)));
        assertEquals(Byte.toString(ShortcutAction.ACTION_WORKOUT_START),
                WithingsScanwatchLightSettingsCustomizer.normaliseLightShortcut(Byte.toString(ShortcutAction.ACTION_WORKOUT_START)));
    }

    private static GBDeviceCandidate candidate(final String name) {
        return new GBDeviceCandidate(null, (short) 0, null, null) {
            @Override
            public String getName() {
                return name;
            }
        };
    }
}
