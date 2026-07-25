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
package nodomain.freeyourgadget.gadgetbridge.devices.ringconn;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2SampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.RingConnActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig;
import nodomain.freeyourgadget.gadgetbridge.model.Spo2Sample;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.ringconn.RingConnDeviceSupport;

public class RingConnCoordinator extends AbstractBLEDeviceCoordinator {

    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("RingConn.*");
    }

    @Override
    public String getManufacturer() {
        return "RingConn";
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(final GBDevice device) {
        return RingConnDeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_ringconn;
    }

    @Override
    public int getDefaultIconResource() {
        return R.drawable.ic_device_smartring;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.RING;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        return new HashMap<>() {{
            put(session.getRingConnActivitySampleDao(), RingConnActivitySampleDao.Properties.DeviceId);
            put(session.getGenericSpo2SampleDao(), GenericSpo2SampleDao.Properties.DeviceId);
        }};
    }

    @Override
    public SampleProvider<? extends ActivitySample> getSampleProvider(final GBDevice device, final DaoSession session) {
        return new RingConnSampleProvider(device, session);
    }

    @Override
    public boolean supportsActivityTracking(@NonNull final GBDevice device) {
        return true;
    }

    /** The ring exposes no gait-filtered step count, so every sample stores zero steps. */
    @Override
    public boolean supportsStepCounter(@NonNull final GBDevice device) {
        return false;
    }

    @Override
    public boolean supportsSleepMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public boolean supportsSpo2(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public TimeSampleProvider<? extends Spo2Sample> getSpo2SampleProvider(final GBDevice device,
                                                                         final DaoSession session) {
        return new GenericSpo2SampleProvider(device, session);
    }

    @Override
    public boolean supportsDataFetching(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public BatteryConfig[] getBatteryConfig(final GBDevice device) {
        return new BatteryConfig[]{
                new BatteryConfig(0, GBDevice.BATTERY_ICON_DEFAULT, GBDevice.BATTERY_LABEL_DEFAULT, 15, 100)
        };
    }
}
