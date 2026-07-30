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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.bluetooth.le.ScanFilter;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSetting;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.dsl.DeviceSettingsSpec;
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericRespiratoryRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericRespiratoryRateSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.test.TestBase;
import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

public class R10MCoordinatorTest extends TestBase {
    private final R10MCoordinator coordinator = new R10MCoordinator();

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
    public void isAConnectableRing() {
        assertTrue(coordinator.isConnectable());
        assertEquals(DeviceCoordinator.BONDING_STYLE_NONE, coordinator.getBondingStyle());
        assertEquals(YcbtPairingActivity.class, coordinator.getPairingActivity());
        assertEquals(DeviceCoordinator.DeviceKind.RING, coordinator.getDeviceKind(null));
        assertEquals(1, coordinator.getBatteryCount(null));
        assertEquals(1, coordinator.getBatteryConfig(null).length);
        assertEquals(2, coordinator.getCustomActions().size());
    }

    @Test
    public void exposesRecordedDistanceAndActiveCalories() {
        final GBDevice device = new GBDevice(
                "00:11:22:33:44:55", "R10M", "R10M", "", DeviceType.YCBT_R10M
        );
        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress())
                .edit()
                .putBoolean(YcbtConstants.PREF_CAPABILITY_STEPS, true)
                .apply();

        assertTrue(coordinator.supportsActivityDistance(device));
        assertTrue(coordinator.supportsActiveCalories(device));
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

    @Test
    public void hidesAutomaticMonitoringSettingsWithoutCapabilities() {
        final GBDevice device = new GBDevice(
                "00:11:22:33:44:66", "R10M", "R10M", "", DeviceType.YCBT_R10M
        );
        final Prefs prefs = new Prefs(GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()));
        final DeviceSettingsSpec settings = coordinator.getDeviceSettings(device);

        assertFalse(isVisible(settings, DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL, prefs));
        assertFalse(isVisible(settings, DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, prefs));
        assertFalse(isVisible(settings, DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL, prefs));

        GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).edit()
                .putBoolean(YcbtConstants.PREF_CAPABILITY_HEART_RATE, true)
                .putBoolean(YcbtConstants.PREF_CAPABILITY_SPO2, true)
                .apply();

        assertTrue(isVisible(settings, DeviceSettingsPreferenceConst.PREF_HEARTRATE_MEASUREMENT_INTERVAL, prefs));
        assertTrue(isVisible(settings, DeviceSettingsPreferenceConst.PREF_SPO2_ALL_DAY_MONITORING, prefs));
        assertTrue(isVisible(settings, DeviceSettingsPreferenceConst.PREF_SPO2_MEASUREMENT_INTERVAL, prefs));
    }

    @Test
    public void registersAndExposesRespiratoryRateProvider() {
        final GBDevice device = new GBDevice(
                "00:11:22:33:44:55", "R10M", "R10M", "", DeviceType.YCBT_R10M
        );

        assertTrue(coordinator.supportsRespiratoryRate(device));
        assertTrue(coordinator.supportsDayRespiratoryRate(device));
        assertTrue(coordinator.getRespiratoryRateSampleProvider(device, daoSession)
                instanceof GenericRespiratoryRateSampleProvider);
        assertEquals(
                GenericRespiratoryRateSampleDao.Properties.DeviceId,
                coordinator.getAllDeviceDao(daoSession).get(daoSession.getGenericRespiratoryRateSampleDao())
        );

        final GenericRespiratoryRateSampleProvider provider =
                (GenericRespiratoryRateSampleProvider) coordinator
                        .getRespiratoryRateSampleProvider(device, daoSession);
        DBHelper.getDevice(device, daoSession);
        final GenericRespiratoryRateSample first = new GenericRespiratoryRateSample();
        first.setTimestamp(1_700_000_000_123L);
        first.setRespiratoryRate(13.0f);
        assertTrue(provider.persistSamples(List.of(first), getContext()));

        final GenericRespiratoryRateSample replay = new GenericRespiratoryRateSample();
        replay.setTimestamp(first.getTimestamp());
        replay.setRespiratoryRate(14.0f);
        assertTrue(provider.persistSamples(List.of(replay), getContext()));

        final List<GenericRespiratoryRateSample> stored = provider.getAllSamples(
                first.getTimestamp(), first.getTimestamp()
        );
        assertEquals(1, stored.size());
        assertEquals(14.0f, stored.get(0).getRespiratoryRate(), 0.0f);
    }

    @Test
    public void exposesIndividualHeartRateThroughItsDedicatedProvider() {
        final GBDevice device = new GBDevice(
                "00:11:22:33:44:55", "R10M", "R10M", "", DeviceType.YCBT_R10M
        );

        assertTrue(coordinator.getHeartRateSampleProvider(device, daoSession)
                instanceof GenericHeartRateSampleProvider);
        assertNull(coordinator.getHeartRateMaxSampleProvider(device, daoSession));
    }

    private static boolean isVisible(final DeviceSettingsSpec settings, final String key, final Prefs prefs) {
        for (final DeviceSetting setting : settings.getItems()) {
            if (key.equals(setting.getKey())) {
                return setting.getVisibleWhen() == null || setting.getVisibleWhen().invoke(prefs);
            }
        }
        return false;
    }

}
