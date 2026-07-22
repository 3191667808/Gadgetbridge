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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.bluetooth.le.ScanFilter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec;
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;

public class R10MCoordinatorTest extends TestBase {
    private final R10MCoordinator coordinator = new R10MCoordinator();

    @Test
    public void recognizesSupportedRingFamilyNames() {
        final Pattern supportedName = coordinator.getSupportedDeviceName();

        assertTrue(supportedName.matcher("R10M FCF4").matches());
        assertTrue(supportedName.matcher("R10M FCF3").matches());
        assertTrue(supportedName.matcher("R10M_FCF4").matches());
        assertTrue(supportedName.matcher("R11M 123A").matches());
        assertTrue(supportedName.matcher("R11M_ABCD").matches());
        assertTrue(supportedName.matcher("R11M").matches());
        assertFalse(supportedName.matcher("R10M fcf4").matches());
        assertFalse(supportedName.matcher("R12M FCF4").matches());
        assertFalse(supportedName.matcher(" R10M FCF4").matches());
        assertFalse(supportedName.matcher("R10M FCF4 ").matches());
    }

    @Test
    public void registersPermanentDeviceType() {
        assertEquals(DeviceType.YCBT_R10M, DeviceType.valueOf("YCBT_R10M"));
        assertEquals(R10MCoordinator.class, DeviceType.YCBT_R10M.getDeviceCoordinator().getClass());
    }

    @Test
    public void reliesOnSoftwareNameMatchingForVariableSuffixes() {
        final Collection<? extends ScanFilter> filters = coordinator.createBLEScanFilters();

        assertTrue(filters.isEmpty());
    }

    @Test
    public void isAnExperimentalConnectableRing() {
        assertTrue(coordinator.isExperimental());
        assertTrue(coordinator.isConnectable());
        assertEquals(DeviceCoordinator.BONDING_STYLE_NONE, coordinator.getBondingStyle());
        assertEquals(YcbtPairingActivity.class, coordinator.getPairingActivity());
        assertEquals(DeviceCoordinator.DeviceKind.RING, coordinator.getDeviceKind(null));
        assertEquals(1, coordinator.getBatteryCount(null));
        assertEquals(1, coordinator.getBatteryConfig(null).length);
        assertEquals(2, coordinator.getCustomActions().size());
    }

    @Test
    public void exposesOnlySupportedAutomaticMonitoringIntervals() {
        assertEquals(Arrays.asList(
                        HeartRateCapability.MeasurementInterval.OFF,
                        HeartRateCapability.MeasurementInterval.MINUTES_30,
                        HeartRateCapability.MeasurementInterval.HOUR_1
                ),
                coordinator.getHeartRateMeasurementIntervals());

        final DeviceSettingsSpec settings = coordinator.getDeviceSettings(null);
        assertEquals(Set.of(
                DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL,
                DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING,
                DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL
        ), settings.collectAllKeys());
    }
}
