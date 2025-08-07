/*  Copyright (C) 2025 Daniel Giritzer, MSc (giri@nwrk.biz)
    Copyright (C) 2025 De_Coder (de_coder@posteo.de)

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
package nodomain.freeyourgadget.gadgetbridge.devices.keepfit;

import androidx.annotation.NonNull;

import java.util.regex.Pattern;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.List;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettings;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSpecificSettingsScreen;
import nodomain.freeyourgadget.gadgetbridge.activities.CameraActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.capabilities.HeartRateCapability;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitSleepSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples.KeepFitStressSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.SleepScoreSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.model.StressSample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.keepfit.KeepFitDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitSleepSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitStressSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitBloodPressureSampleDao;

public abstract class AbstractKeepFitCoordinator extends AbstractBLEDeviceCoordinator {
    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        Map<AbstractDao<?, ?>, Property> map = new HashMap<>(6);
        map.put(session.getKeepFitActivitySampleDao(), KeepFitActivitySampleDao.Properties.DeviceId);
        map.put(session.getKeepFitHeartRateSampleDao(), KeepFitHeartRateSampleDao.Properties.DeviceId);
        map.put(session.getKeepFitSleepSampleDao(), KeepFitSleepSampleDao.Properties.DeviceId);
        map.put(session.getKeepFitSpo2SampleDao(), KeepFitSpo2SampleDao.Properties.DeviceId);
        map.put(session.getKeepFitStressSampleDao(), KeepFitStressSampleDao.Properties.DeviceId);
        map.put(session.getKeepFitBloodPressureSampleDao(), KeepFitBloodPressureSampleDao.Properties.DeviceId);
        return map;
    }

    @Override
    public String getManufacturer() {
        return "Janyun"; // KeepRapid?
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return KeepFitDeviceSupport.class;
    }

    @Override
    public boolean supportsPowerOff(GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_ASK;
    }


    @Override
    public boolean supportsSpo2(GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateStats(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsFindDevice(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsManualHeartRateMeasurement(GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsRealtimeData(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSleepScore(final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsRemSleep(@NonNull GBDevice device) {
        return false; // does only support, deep sleep, light sleep and awake
    }

    @Override
    public boolean supportsAwakeSleep(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsStressMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supports(GBDeviceCandidate candidate) {
        return super.supports(candidate);
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(GBDevice device) {
        return new int[]{
                R.xml.devicesettings_header_time,
                R.xml.devicesettings_timeformat,
        };
    }

    @Override
    public TimeSampleProvider<? extends HeartRateSample> getHeartRateManualSampleProvider(GBDevice device, DaoSession session) {
        return new KeepFitHeartRateSampleProvider(device, session);
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
        return new KeepFitActivitySampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends SleepScoreSample> getSleepScoreProvider(GBDevice device, DaoSession session) {
        return new KeepFitSleepSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(GBDevice device, DaoSession session) {
        return new KeepFitSpo2SampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends StressSample> getStressSampleProvider(GBDevice device, DaoSession session) {
        return new KeepFitStressSampleProvider(device, session);
    }

    @Override
    public List<HeartRateCapability.MeasurementInterval> getHeartRateMeasurementIntervals() {
        return Arrays.asList(
                HeartRateCapability.MeasurementInterval.OFF,
                HeartRateCapability.MeasurementInterval.MINUTES_5,
                HeartRateCapability.MeasurementInterval.MINUTES_15,
                HeartRateCapability.MeasurementInterval.MINUTES_30,
                HeartRateCapability.MeasurementInterval.MINUTES_45,
                HeartRateCapability.MeasurementInterval.HOUR_1
        );
    }

    @Override
    public DeviceSpecificSettings getDeviceSpecificSettings(final GBDevice device) {
        final DeviceSpecificSettings deviceSpecificSettings = super.getDeviceSpecificSettings(device);
        final List<Integer> health = deviceSpecificSettings.addRootScreen(DeviceSpecificSettingsScreen.HEALTH); 
        health.add(R.xml.devicesettings_keepfit); // interval setting UI


        if(CameraActivity.supportsCamera()) {
            deviceSpecificSettings.addRootScreen(R.xml.devicesettings_camera_remote);
        }
        return deviceSpecificSettings;
    }
}