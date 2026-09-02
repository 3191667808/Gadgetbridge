/*
    Copyright (C) 2026 Jonathan Styles

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
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package nodomain.freeyourgadget.gadgetbridge.devices.ihealthtrack;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericBloodPressureSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericBloodPressureSampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.ihealthtrack.IHealthTrackDeviceSupport;

/**
 * iHealth Track (model number KN-550BT, OEM'd by Jiuan Medical / Andon).
 * Protocol reverse engineered live against a real unit - see
 * https://gist.github.com/jontyms/293edf1d088b07e9cdd051f4525be9fa for the
 * write-up.
 */
public class IHealthTrackDeviceCoordinator extends AbstractBLEDeviceCoordinator {

    @Nullable
    @Override
    protected Pattern getSupportedDeviceName() {
        return Pattern.compile("^KN-550BT$");
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass(GBDevice device) {
        return IHealthTrackDeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_ihealth_track;
    }

    @Override
    public DeviceKind getDeviceKind(@NonNull GBDevice device) {
        return DeviceKind.BLOOD_PRESSURE_METER;
    }

    @Override
    public int getBondingStyle() {
        return BONDING_STYLE_NONE;
    }

    @Override
    public boolean suggestUnbindBeforePair() {
        return false;
    }

    @Override
    public boolean supportsBloodPressureMeasurement(@NonNull final GBDevice device) {
        return true;
    }

    @Override
    public GenericBloodPressureSampleProvider getBloodPressureSampleProvider(
            final GBDevice device, final DaoSession session) {
        return new GenericBloodPressureSampleProvider(device, session);
    }

    @Override
    public String getManufacturer() {
        return "iHealth";
    }

    @Override
    public Map<AbstractDao<?, ?>, Property> getAllDeviceDao(@NonNull final DaoSession session) {
        final Map<AbstractDao<?, ?>, Property> daoMap = new HashMap<>();
        daoMap.put(session.getGenericBloodPressureSampleDao(), GenericBloodPressureSampleDao.Properties.DeviceId);
        return daoMap;
    }

    @Override
    public GBDevice createDevice(final GBDeviceCandidate candidate, final DeviceType deviceType) {
        final GBDevice device = super.createDevice(candidate, deviceType);
        device.setAlias(GBApplication.getContext().getString(R.string.devicetype_ihealth_track));
        return device;
    }
}
