/*  Copyright (C) 2025 LiJu09

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
package nodomain.freeyourgadget.gadgetbridge.devices.keephealth;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthHeartRateSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeephealthSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.keephealth.C60DeviceSupport;

public class C60DeviceCoordinator extends AbstractDeviceCoordinator  {
    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_c60;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_default;
    }

    @Override
    public String getManufacturer() {
        return "Brandless";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return C60DeviceSupport.class;
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^C60-.*");
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.FITNESS_BAND;
    }

    @Override
    public int[] getSupportedDeviceSpecificSettings(GBDevice device) {
        return new int[]{
                R.xml.devicesettings_timeformat,
                R.xml.devicesettings_language_generic,
                R.xml.devicesettings_donotdisturb_no_auto,
                R.xml.devicesettings_liftwrist_display_noshed,
                R.xml.devicesettings_hydration_reminder, // TODO implement
                R.xml.devicesettings_inactivity_sheduled, // TODO implement
                R.xml.devicesettings_goal_notification,  // TODO implement this and goal setting
        };
    }

    @Override
    public String[] getSupportedLanguageSettings(GBDevice device) {
        return new String[]{
                "en_US",
                "zh_CN",
                "ru_RU",
                "fr_FR",
                "es_ES",
                "de_DE",
                "ja_JP",
                "pl_PL",
                "it_IT",
                "zh_TW",
                "nl_NL"
        };
    }

    @Override
    public boolean supportsWeather(GBDevice device) {
        // TODO implement
        return false;
    }

    @Override
    public boolean supportsFindDevice(GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityDataFetching(final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsActivityTracking(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateMeasurement(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsTemperatureMeasurement(@NonNull final GBDevice device) {
        // TODO implement
        return false;
    }

    @Override
    public boolean supportsSpo2(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateStats(@NonNull GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsHeartRateRestingMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public int getAlarmSlotCount(@NonNull GBDevice device) {
        // TODO implement
        return 0;
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(GBDevice device, DaoSession session) {
        return new KeephealthSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends HeartRateSample> getHeartRateRestingSampleProvider(GBDevice device, DaoSession session) {
        return new KeephealthHeartRateSampleProvider(device, session);
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(GBDevice device, DaoSession session) {
        return new KeephealthSpo2SampleProvider(device, session);
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        Map<AbstractDao<?, ?>, Property> map = new HashMap<>(2);
        map.put(session.getKeephealthActivitySampleDao(), KeephealthActivitySampleDao.Properties.DeviceId);
        map.put(session.getKeephealthHeartRateSampleDao(), KeephealthHeartRateSampleDao.Properties.DeviceId);
        map.put(session.getKeephealthSpo2SampleDao(), KeephealthSpo2SampleDao.Properties.DeviceId);
        return map;
    }
}
