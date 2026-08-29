/*  Copyright (C) 2023-2024 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.devices.xiaomi;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class XiaomiSampleProvider extends AbstractSampleProvider<XiaomiActivitySample> {

    public XiaomiSampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<XiaomiActivitySample, ?> getSampleDao() {
        return getSession().getXiaomiActivitySampleDao();
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return XiaomiActivitySampleDao.Properties.RawKind;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return XiaomiActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return XiaomiActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(final int rawType) {
        // TODO
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(final ActivityKind activityKind) {
        // TODO
        return activityKind.getCode();
    }

    @Override
    public float normalizeIntensity(final int rawIntensity) {
        return rawIntensity / 100f;
    }

    @Override
    public XiaomiActivitySample createActivitySample() {
        return new XiaomiActivitySample();
    }

    @Override
    protected List<XiaomiActivitySample> getGBActivitySamples(final int timestamp_from, final int timestamp_to) {
        final List<XiaomiActivitySample> samples = super.getGBActivitySamples(timestamp_from, timestamp_to);

        convertCalories(samples);
        final XiaomiSleepSessionProvider sleepSessionProvider = new XiaomiSleepSessionProvider(getDevice(), getSession());
        overlaySleep(sleepSessionProvider, samples, timestamp_from, timestamp_to);

        return samples;
    }

    private void convertCalories(final List<XiaomiActivitySample> samples) {
        for (XiaomiActivitySample sample : samples) {
            sample.setActiveCalories(sample.getActiveCalories() * 1000);
        }
    }

    /**
     * See {@link nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.SleepDetailsParser}
     */
    public static ActivityKind getActivityKindForSample(final XiaomiSleepStageSample sample) {
        return switch (sample.getStage()) {
            case 2 -> ActivityKind.DEEP_SLEEP;
            case 3 -> ActivityKind.LIGHT_SLEEP;
            case 4 -> ActivityKind.REM_SLEEP;
            case 5 -> ActivityKind.AWAKE_SLEEP;
            default -> ActivityKind.UNKNOWN;
        };
    }
}
