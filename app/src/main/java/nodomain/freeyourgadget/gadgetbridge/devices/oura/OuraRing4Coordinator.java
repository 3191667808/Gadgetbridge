/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.devices.oura;

import android.app.Activity;
import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.ComputedHrvSummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.oura.samples.OuraActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartRrIntervalSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraRecoverySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.OuraSleepSessionSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.OuraRing4Support;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraAuth;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraUUIDs;

public class OuraRing4Coordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^Oura.*");
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        final ParcelUuid svc = new ParcelUuid(OuraUUIDs.PRIMARY_SERVICE);
        final ScanFilter filter = new ScanFilter.Builder().setServiceUuid(svc).build();
        return Collections.singletonList(filter);
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_REQUIRE_KEY;
    }

    @Override
    public boolean validateAuthKey(final String authKey) {
        if (authKey == null) {
            return false;
        }
        return OuraAuth.parseHexKey(authKey) != null;
    }

    @Nullable
    @Override
    public Class<? extends Activity> getPairingActivity() {
        // The ring uses a Random Resolvable Private Address + app-level AES auth (opcode 0x24),
        // not OS-level BLE pairing keys. Returning a custom activity routes the framework around
        // the default REQUIRE_KEY → device.createBond() path that reverts to BOND_NONE for this ring.
        return OuraPairingActivity.class;
    }

    @Nullable
    @Override
    public String getAuthHelp() {
        return OuraConstants.AUTH_HELP_URL;
    }

    @Override
    public int[] getSupportedDeviceSpecificAuthenticationSettings() {
        return new int[]{R.xml.devicesettings_pairingkey};
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        final Map<AbstractDao<?, ?>, Property> map = new HashMap<>(9);
        map.put(session.getOuraActivitySampleDao(), OuraActivitySampleDao.Properties.DeviceId);
        map.put(session.getGenericHeartRateSampleDao(), GenericHeartRateSampleDao.Properties.DeviceId);
        map.put(session.getHeartRrIntervalSampleDao(), HeartRrIntervalSampleDao.Properties.DeviceId);
        map.put(session.getGenericSpo2SampleDao(), GenericSpo2SampleDao.Properties.DeviceId);
        map.put(session.getGenericStressSampleDao(), GenericStressSampleDao.Properties.DeviceId);
        map.put(session.getOuraSleepSessionSampleDao(), OuraSleepSessionSampleDao.Properties.DeviceId);
        map.put(session.getGenericSleepStageSampleDao(), GenericSleepStageSampleDao.Properties.DeviceId);
        map.put(session.getGenericHrvValueSampleDao(), GenericHrvValueSampleDao.Properties.DeviceId);
        map.put(session.getGenericTemperatureSampleDao(), GenericTemperatureSampleDao.Properties.DeviceId);
        map.put(session.getOuraRecoverySampleDao(), OuraRecoverySampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public String getManufacturer() {
        return "Ōura Health";
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_oura_ring_4;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_smartring;
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return OuraRing4Support.class;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(final GBDevice device, final DaoSession session) {
        return new OuraActivitySampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(final GBDevice device, final DaoSession session) {
        return new GenericSpo2SampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(final GBDevice device, final DaoSession session) {
        return new GenericStressSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvSummarySample> getHrvSummarySampleProvider(final GBDevice device, final DaoSession session) {
        return new ComputedHrvSummarySampleProvider(getHrvValueSampleProvider(device, session), device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(final GBDevice device, final DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends TemperatureSample> getTemperatureSampleProvider(final GBDevice device, final DaoSession session) {
        return new GenericTemperatureSampleProvider(device, session, TemperatureSample.TYPE_SKIN, TemperatureSample.LOCATION_FINGER);
    }

    @Override
    public TimeSampleProvider<? extends HeartRateSample> getHeartRateMaxSampleProvider(final GBDevice device, final DaoSession session) {
        return new GenericHeartRateSampleProvider(device, session);
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsManualHeartRateMeasurement(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsHrvMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsContinuousTemperature(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsTemperatureMeasurement(@NonNull final GBDevice device) {
        // Required to surface the Temperature chart tab in the device card. DefaultChartsProvider
        // gates the tab on this flag (not on supportsContinuousTemperature), so the existing
        // supportsContinuousTemperature=true override only unlocks the per-sample chart, not the
        // tab registration. Returning true here triggers DefaultChartsProvider to add the
        // "temperature" entry to charts_tabs and pick TemperatureDailyFragment because
        // supportsContinuousTemperature is also true.
        return true;
    }

    @Override
    public boolean supportsSpo2(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSpeedzones(@NonNull final GBDevice device) {
        // Default delegates to supportsActivityTracking() which is true for Oura, but the ring
        // exposes no per-pace/speed-zone breakdown — drain has no opcode for it. Hide the tab.
        return false;
    }

    @Override
    public boolean supportsSleepMeasurement(@NonNull final GBDevice device) {
        // Ring firmware never emits sleep-stage events; Oura computes stages on the phone via
        // an on-device PyTorch model (SleepNetBdiPyTorchModel) consuming raw 0x60/0x47/0x46
        // streams. GB drain ends with zero rows in GENERIC_SLEEP_STAGE_SAMPLE +
        // OURA_SLEEP_SESSION_SAMPLE — hiding the sleep tab + the sleep mini-chart on the device
        // card avoids a permanently-empty Sleep widget.
        return false;
    }

    @Override
    public boolean supportsRemSleep(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsAwakeSleep(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsRealtimeData(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsFindDevice(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(final GBDevice device) {
        final DeviceSpecificSettings deviceSpecificSettings = new DeviceSpecificSettings();
        final List<Integer> health = deviceSpecificSettings.addRootScreen(DeviceSpecificSettingsScreen.HEALTH);
        health.add(R.xml.devicesettings_oura_health);
        final List<Integer> developer = deviceSpecificSettings.addRootScreen(DeviceSpecificSettingsScreen.DEVELOPER);
        // Informational on Oura: ring exposes no delete-after-sync opcode in protocol v1.
        developer.add(R.xml.devicesettings_keep_activity_data_on_device);
        developer.add(R.xml.devicesettings_oura_developer);
        return deviceSpecificSettings;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.RING;
    }

    @Override
    public boolean suggestUnbindBeforePair() {
        return false;
    }
}
