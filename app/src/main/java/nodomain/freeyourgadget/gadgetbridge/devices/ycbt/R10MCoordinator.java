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

import android.app.Activity;
import android.bluetooth.le.ScanFilter;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction;
import nodomain.freeyourgadget.gadgetbridge.devices.ComputedHrvSummarySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHrvValueSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericTemperatureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHrvValueSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericTemperatureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.GlucoseSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.YcbtActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.YcbtActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvSummarySample;
import nodomain.freeyourgadget.gadgetbridge.model.HrvValueSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.model.TemperatureSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt.YcbtDeviceSupport;

public class R10MCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile(YcbtConstants.SUPPORTED_DEVICE_NAME_PATTERN);
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        return Collections.emptyList();
    }

    @Override
    public boolean isConnectable() {
        return true;
    }

    @Override
    public String getManufacturer() {
        return "YCBT";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return YcbtDeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_ycbt_r10m;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_smartring;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Nullable
    @Override
    public Class<? extends Activity> getPairingActivity() {
        return YcbtPairingActivity.class;
    }

    @Override
    public boolean isExperimental() {
        return true;
    }

    @Override
    public int getBatteryCount(final GBDevice device) {
        return 1;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull final GBDevice device) {
        return DeviceKind.RING;
    }

    @Override
    public boolean supportsBloodPressureMeasurement(@NonNull final GBDevice device) {
        return capability(device, YcbtConstants.PREF_CAPABILITY_BLOOD_PRESSURE);
    }

    @Override
    public GenericBloodPressureSampleProvider getBloodPressureSampleProvider(
            @NonNull final GBDevice device,
            @NonNull final DaoSession session) {
        return new GenericBloodPressureSampleProvider(device, session);
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        final Map<AbstractDao<?, ?>, Property> daoMap = new HashMap<>();
        daoMap.put(session.getGenericBloodPressureSampleDao(), GenericBloodPressureSampleDao.Properties.DeviceId);
        daoMap.put(session.getGenericHeartRateSampleDao(), GenericHeartRateSampleDao.Properties.DeviceId);
        daoMap.put(session.getGenericHrvValueSampleDao(), GenericHrvValueSampleDao.Properties.DeviceId);
        daoMap.put(session.getGenericSleepStageSampleDao(), GenericSleepStageSampleDao.Properties.DeviceId);
        daoMap.put(session.getGenericSpo2SampleDao(), GenericSpo2SampleDao.Properties.DeviceId);
        daoMap.put(session.getGenericStressSampleDao(), GenericStressSampleDao.Properties.DeviceId);
        daoMap.put(session.getGenericTemperatureSampleDao(), GenericTemperatureSampleDao.Properties.DeviceId);
        daoMap.put(session.getGlucoseSampleDao(), GlucoseSampleDao.Properties.DeviceId);
        daoMap.put(session.getYcbtActivitySampleDao(), YcbtActivitySampleDao.Properties.DeviceId);
        return daoMap;
    }

    @Override
    public List<DeviceCardAction> getCustomActions() {
        return Arrays.asList(
                capabilityAction(
                        R.drawable.baseline_bloodtype_24,
                        R.string.measure_blood_pressure,
                        YcbtConstants.CONFIG_MEASURE_BLOOD_PRESSURE,
                        YcbtConstants.PREF_CAPABILITY_MANUAL_BLOOD_PRESSURE
                ),
                capabilityAction(
                        R.drawable.ic_spo2,
                        R.string.measure_spo2,
                        YcbtConstants.CONFIG_MEASURE_SPO2,
                        YcbtConstants.PREF_CAPABILITY_MANUAL_SPO2
                )
        );
    }

    @Override
    public TimeSampleProvider<? extends HeartRateSample> getHeartRateMaxSampleProvider(
            @NonNull final GBDevice device,
            @NonNull final DaoSession session) {
        return new GenericHeartRateSampleProvider(device, session);
    }

    @Override
    public SampleProvider<YcbtActivitySample> getSampleProvider(
            @NonNull final GBDevice device,
            @NonNull final DaoSession session) {
        return new YcbtActivitySampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(
            @NonNull final GBDevice device,
            @NonNull final DaoSession session) {
        return new GenericSpo2SampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvValueSample> getHrvValueSampleProvider(
            @NonNull final GBDevice device,
            @NonNull final DaoSession session) {
        return new GenericHrvValueSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HrvSummarySample> getHrvSummarySampleProvider(
            @NonNull final GBDevice device,
            @NonNull final DaoSession session) {
        return new ComputedHrvSummarySampleProvider(getHrvValueSampleProvider(device, session), device, session);
    }

    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(
            @NonNull final GBDevice device,
            @NonNull final DaoSession session) {
        return new GenericStressSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends TemperatureSample> getTemperatureSampleProvider(
            @NonNull final GBDevice device,
            @NonNull final DaoSession session) {
        return new GenericTemperatureSampleProvider(
                device,
                session,
                TemperatureSample.TYPE_SKIN,
                TemperatureSample.LOCATION_FINGER
        );
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsStepCounter(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateStats(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsManualHeartRateMeasurement(@NonNull final GBDevice device) {
        return capability(device, YcbtConstants.PREF_CAPABILITY_MANUAL_HEART_RATE);
    }

    @Override
    public boolean supportsRealtimeData(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsFindDevice(@NonNull final GBDevice device) {
        return capability(device, YcbtConstants.PREF_CAPABILITY_FIND_DEVICE);
    }

    @Override
    public boolean supportsSpo2(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public List<HeartRateCapability.MeasurementInterval> getHeartRateMeasurementIntervals() {
        return Arrays.asList(
                HeartRateCapability.MeasurementInterval.OFF,
                HeartRateCapability.MeasurementInterval.MINUTES_30,
                HeartRateCapability.MeasurementInterval.HOUR_1
        );
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(final GBDevice device) {
        final DeviceSpecificSettings settings = new DeviceSpecificSettings();
        settings.addRootScreen(DeviceSpecificSettingsScreen.HEALTH).add(R.xml.devicesettings_ycbt_health);
        return settings;
    }

    @Override
    public boolean supportsHrvMeasurement(@NonNull final GBDevice device) {
        return capability(device, YcbtConstants.PREF_CAPABILITY_HRV);
    }

    @Override
    public boolean supportsTemperatureMeasurement(@NonNull final GBDevice device) {
        return capability(device, YcbtConstants.PREF_CAPABILITY_TEMPERATURE);
    }

    @Override
    public boolean supportsContinuousTemperature(@NonNull final GBDevice device) {
        return supportsTemperatureMeasurement(device);
    }

    @Override
    public boolean supportsStressMeasurement(@NonNull final GBDevice device) {
        return capability(device, YcbtConstants.PREF_CAPABILITY_STRESS);
    }

    @Override
    public boolean supportsRemSleep(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsAwakeSleep(@NonNull final GBDevice device) {
        return true;
    }

    private static boolean capability(final GBDevice device, final String preference) {
        return device != null && GBApplication.getDeviceSpecificSharedPrefs(device.getAddress())
                .getBoolean(preference, false);
    }

    private static DeviceCardAction capabilityAction(final int icon,
                                                     final int description,
                                                     final String config,
                                                     final String capabilityPreference) {
        return new DeviceCardAction() {
            @Override
            public int getIcon(@NonNull final GBDevice device) {
                return icon;
            }

            @NonNull
            @Override
            public String getDescription(@NonNull final GBDevice device, @NonNull final Context context) {
                return context.getString(description);
            }

            @Override
            public boolean isVisible(@NonNull final GBDevice device) {
                return device.getState() == GBDevice.State.INITIALIZED
                        && capability(device, capabilityPreference);
            }

            @Override
            public void onClick(@NonNull final GBDevice device, @NonNull final Context context) {
                GBApplication.deviceService(device).onSendConfiguration(config);
            }
        };
    }
}
