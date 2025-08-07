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
package nodomain.freeyourgadget.gadgetbridge.devices.keepfit.samples;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.KeepFitHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class KeepFitActivitySampleProvider extends AbstractSampleProvider<KeepFitActivitySample> {
    public KeepFitActivitySampleProvider(GBDevice device, DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<KeepFitActivitySample, ?> getSampleDao() {
        return getSession().getKeepFitActivitySampleDao();
    }

    @Nullable
    @Override
    protected Property getRawKindSampleProperty() {
        return null;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return KeepFitActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return KeepFitActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(int rawType) {
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(ActivityKind activityKind) {
        return activityKind.getCode();
    }

    @Override
    public float normalizeIntensity(int rawIntensity) {
        return 0;
    }

    @Override
    public KeepFitActivitySample createActivitySample() {
        return new KeepFitActivitySample();
    }

    @Override
    protected List<KeepFitActivitySample> getGBActivitySamples(final int timestamp_from, final int timestamp_to) {
        final long nanoStart = System.nanoTime();

        final List<KeepFitActivitySample> samples = fillGaps(
                super.getGBActivitySamples(timestamp_from, timestamp_to),
                timestamp_from,
                timestamp_to
        );

        final Map<Integer, KeepFitActivitySample> sampleByTs = new HashMap<>();
        for (final KeepFitActivitySample sample : samples) {
            sampleByTs.put(sample.getTimestamp(), sample);
        }

        overlayHeartRate(sampleByTs, timestamp_from, timestamp_to);

        final List<KeepFitActivitySample> finalSamples = new ArrayList<>(sampleByTs.values());
        Collections.sort(finalSamples, (a, b) -> Integer.compare(a.getTimestamp(), b.getTimestamp()));

        final long nanoEnd = System.nanoTime();
        final long executionTime = (nanoEnd - nanoStart) / 1000000;

        return finalSamples;
    }

    private void overlayHeartRate(final Map<Integer, KeepFitActivitySample> sampleByTs, final int timestamp_from, final int timestamp_to) {
        final KeepFitHeartRateSampleProvider heartRateSampleProvider = new KeepFitHeartRateSampleProvider(getDevice(), getSession());
        final List<KeepFitHeartRateSample> hrSamples = heartRateSampleProvider.getAllSamples(timestamp_from * 1000L, timestamp_to * 1000L);

        for (final KeepFitHeartRateSample hrSample : hrSamples) {
            // round to the KeepFitActivitySample minute, we don't need per-second granularity
            final int tsSeconds = (int) ((hrSample.getTimestamp() / 1000) / 60) * 60;
            KeepFitActivitySample sample = sampleByTs.get(tsSeconds);
            if (sample == null) {
                sample = new KeepFitActivitySample();
                sample.setTimestamp(tsSeconds);
                sample.setProvider(this);
                sampleByTs.put(tsSeconds, sample);
            }

            sample.setHeartRate(hrSample.getHeartRate());
        }
    }
}
