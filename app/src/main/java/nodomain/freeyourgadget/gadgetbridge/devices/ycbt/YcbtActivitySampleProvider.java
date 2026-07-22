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

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.greenrobot.dao.AbstractDao;
import de.greenrobot.dao.Property;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericHeartRateSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericHeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.YcbtActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.YcbtActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

public class YcbtActivitySampleProvider extends AbstractSampleProvider<YcbtActivitySample> {
    public YcbtActivitySampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
    }

    @Override
    public AbstractDao<YcbtActivitySample, ?> getSampleDao() {
        return getSession().getYcbtActivitySampleDao();
    }

    @NonNull
    @Override
    protected Property getRawKindSampleProperty() {
        return YcbtActivitySampleDao.Properties.RawKind;
    }

    @NonNull
    @Override
    protected Property getTimestampSampleProperty() {
        return YcbtActivitySampleDao.Properties.Timestamp;
    }

    @NonNull
    @Override
    protected Property getDeviceIdentifierSampleProperty() {
        return YcbtActivitySampleDao.Properties.DeviceId;
    }

    @Override
    public ActivityKind normalizeType(final int rawType) {
        return ActivityKind.fromCode(rawType);
    }

    @Override
    public int toRawActivityKind(final ActivityKind activityKind) {
        return activityKind.getCode();
    }

    @Override
    public float normalizeIntensity(final int rawIntensity) {
        return rawIntensity == ActivitySample.NOT_MEASURED ? ActivitySample.NOT_MEASURED : rawIntensity;
    }

    @Override
    public YcbtActivitySample createActivitySample() {
        return new YcbtActivitySample();
    }

    @Override
    protected List<YcbtActivitySample> getGBActivitySamples(final int timestampFrom, final int timestampTo) {
        final Map<Integer, YcbtActivitySample> samplesByTimestamp = new HashMap<>();
        for (final YcbtActivitySample sample : super.getGBActivitySamples(timestampFrom, timestampTo)) {
            samplesByTimestamp.put(sample.getTimestamp(), sample);
        }
        overlayHeartRate(samplesByTimestamp, timestampFrom, timestampTo);
        overlaySleep(samplesByTimestamp, timestampFrom, timestampTo);

        final List<YcbtActivitySample> samples = new ArrayList<>(samplesByTimestamp.values());
        Collections.sort(samples, (left, right) -> Integer.compare(left.getTimestamp(), right.getTimestamp()));
        return fillGaps(samples, timestampFrom, timestampTo);
    }

    private void overlayHeartRate(final Map<Integer, YcbtActivitySample> samplesByTimestamp,
                                  final int timestampFrom,
                                  final int timestampTo) {
        final GenericHeartRateSampleProvider provider =
                new GenericHeartRateSampleProvider(getDevice(), getSession());
        final List<GenericHeartRateSample> heartRates = new ArrayList<>(
                provider.getAllSamples(timestampFrom * 1000L, timestampTo * 1000L)
        );
        Collections.sort(heartRates, (left, right) -> Long.compare(left.getTimestamp(), right.getTimestamp()));
        for (final GenericHeartRateSample heartRate : heartRates) {
            final int timestamp = (int) (heartRate.getTimestamp() / 60_000L) * 60;
            final YcbtActivitySample sample = samplesByTimestamp.computeIfAbsent(
                    timestamp,
                    ignored -> emptySample(timestamp)
            );
            sample.setHeartRate(heartRate.getHeartRate());
        }
    }

    private void overlaySleep(final Map<Integer, YcbtActivitySample> samplesByTimestamp,
                              final int timestampFrom,
                              final int timestampTo) {
        final GenericSleepStageSampleProvider provider =
                new GenericSleepStageSampleProvider(getDevice(), getSession());
        final List<GenericSleepStageSample> sleepStages =
                new ArrayList<>(provider.getAllSamples(timestampFrom * 1000L, timestampTo * 1000L));
        final GenericSleepStageSample stageBeforeRange = provider.getLastSampleBefore(timestampFrom * 1000L);
        if (stageBeforeRange != null
                && stageBeforeRange.getTimestamp() + stageBeforeRange.getDuration() * 60_000L
                > timestampFrom * 1000L) {
            sleepStages.add(0, stageBeforeRange);
        }

        for (final GenericSleepStageSample sleepStage : sleepStages) {
            final int stageStart = (int) (sleepStage.getTimestamp() / 60_000L) * 60;
            final int stageEnd = stageStart + sleepStage.getDuration() * 60;
            for (int timestamp = Math.max(stageStart, timestampFrom); timestamp <= timestampTo && timestamp < stageEnd;
                 timestamp += 60) {
                YcbtActivitySample sample = samplesByTimestamp.get(timestamp);
                if (sample == null) {
                    sample = emptySample(timestamp);
                    samplesByTimestamp.put(timestamp, sample);
                }
                sample.setRawKind(sleepStageToActivityKind(sleepStage.getStage()).getCode());
                sample.setRawIntensity(ActivitySample.NOT_MEASURED);
            }
        }
    }

    static ActivityKind sleepStageToActivityKind(final int sleepStage) {
        switch (sleepStage) {
            case 1:
                return ActivityKind.DEEP_SLEEP;
            case 2:
                return ActivityKind.LIGHT_SLEEP;
            case 3:
                return ActivityKind.AWAKE_SLEEP;
            case 4:
                return ActivityKind.REM_SLEEP;
            default:
                return ActivityKind.UNKNOWN;
        }
    }

    private YcbtActivitySample emptySample(final int timestamp) {
        final YcbtActivitySample sample = createActivitySample();
        sample.setTimestamp(timestamp);
        sample.setProvider(this);
        sample.setRawKind(ActivityKind.UNKNOWN.getCode());
        sample.setRawIntensity(ActivitySample.NOT_MEASURED);
        sample.setSteps(0);
        sample.setDistanceCm(0);
        sample.setHeartRate(ActivitySample.NOT_MEASURED);
        return sample;
    }
}
