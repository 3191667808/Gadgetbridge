/*  Copyright (C) 2026 Ariel Saghiv

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
package nodomain.freeyourgadget.gadgetbridge.devices.vring.samples;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.VRingR26ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.VRingR26ActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class VRingR26ActivitySampleProvider extends AbstractSampleProvider<VRingR26ActivitySample> {

    public static final int RAW_KIND_UNKNOWN = 0;
    public static final int RAW_KIND_ACTIVITY = 1;
    public static final int RAW_KIND_NOT_WORN = 100;

    public VRingR26ActivitySampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @NonNull
    @Override
    public AbstractDao<VRingR26ActivitySample, ?> getSampleDao() {
        return getSession().getVRingR26ActivitySampleDao();
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return VRingR26ActivitySampleDao.Properties.RawKind;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return VRingR26ActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return VRingR26ActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(int rawType) {
        switch (rawType) {
            case RAW_KIND_NOT_WORN:
                return ActivityKind.NOT_WORN;
            case RAW_KIND_ACTIVITY:
                return ActivityKind.ACTIVITY;
            default:
                return ActivityKind.UNKNOWN;
        }
    }

    @Override
    public int toRawActivityKind(ActivityKind activityKind) {
        switch (activityKind) {
            case NOT_WORN:
                return RAW_KIND_NOT_WORN;
            case ACTIVITY:
                return RAW_KIND_ACTIVITY;
            default:
                return RAW_KIND_UNKNOWN;
        }
    }

    @Override
    public float normalizeIntensity(int rawIntensity) {
        // Steps-per-minute scaled to 0..1; cap at 120 steps/min for full intensity.
        return Math.min(1.0f, rawIntensity / 120.0f);
    }

    @Override
    public VRingR26ActivitySample createActivitySample() {
        return new VRingR26ActivitySample();
    }
}
