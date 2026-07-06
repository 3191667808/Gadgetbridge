/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.devices.moyoungring;

import androidx.annotation.NonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.generic_hr.GenericHeartRateActivitySampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.GenericSleepStageSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSleepStageSample;
import nodomain.freeyourgadget.gadgetbridge.entities.MoyoungActivitySample;
import nodomain.freeyourgadget.gadgetbridge.entities.MoyoungActivitySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

/**
 * Activity-sample provider for the MoYoung / CRRepa "Da Ring".
 *
 * <p>The ring streams discrete HR records rather than per-minute ActivitySamples,
 * so heart rate is synthesized from the HR DAO (see
 * {@link GenericHeartRateActivitySampleProvider}). On top of that we overlay the
 * per-slot step counts persisted by {@link MoyoungRingDeviceSupport} into the
 * (reused, existing) {@code MoyoungActivitySample} table, so the dashboard renders
 * a real step chart alongside the HR graph without any schema change.
 */
public class MoyoungRingActivitySampleProvider extends GenericHeartRateActivitySampleProvider {
    private static final Logger LOG = LoggerFactory.getLogger(MoyoungRingActivitySampleProvider.class);

    private final GBDevice gbDevice;
    private final DaoSession daoSession;
    private final GenericSleepStageSampleProvider sleepStageSampleProvider;

    public MoyoungRingActivitySampleProvider(final GBDevice device, final DaoSession session) {
        super(device, session);
        this.gbDevice = device;
        this.daoSession = session;
        this.sleepStageSampleProvider = new GenericSleepStageSampleProvider(device, session);
    }

    @NonNull
    @Override
    public List<GenericActivitySample> getAllActivitySamples(final int timestampFrom, final int timestampTo) {
        final List<GenericActivitySample> hrSamples = super.getAllActivitySamples(timestampFrom, timestampTo);

        final Map<Integer, GenericActivitySample> byTs = new HashMap<>();
        for (final GenericActivitySample sample : hrSamples) {
            sample.setProvider(this);
            byTs.put(sample.getTimestamp(), sample);
        }

        for (final MoyoungActivitySample step : getStepSamples(timestampFrom, timestampTo)) {
            final int ts = step.getTimestamp();
            GenericActivitySample sample = byTs.get(ts);
            if (sample == null) {
                sample = new GenericActivitySample();
                sample.setProvider(this);
                sample.setTimestamp(ts);
                byTs.put(ts, sample);
            }
            sample.setSteps(step.getSteps());
            sample.setDistanceCm(step.getDistanceMeters() * 100);
            sample.setActiveCalories(step.getCaloriesBurnt());
            sample.setRawKind(ActivityKind.ACTIVITY.getCode());
        }

        overlaySleep(byTs, timestampFrom, timestampTo);

        final List<GenericActivitySample> merged = new ArrayList<>(byTs.values());
        Collections.sort(merged, Comparator.comparingInt(GenericActivitySample::getTimestamp));
        return merged;
    }

    @NonNull
    @Override
    public List<GenericActivitySample> getAllActivitySamplesHighRes(final int timestampFrom, final int timestampTo) {
        return getAllActivitySamples(timestampFrom, timestampTo);
    }

    private List<MoyoungActivitySample> getStepSamples(final int timestampFrom, final int timestampTo) {
        try {
            final Device dbDevice = DBHelper.findDevice(gbDevice, daoSession);
            if (dbDevice == null) {
                return Collections.emptyList();
            }
            return daoSession.getMoyoungActivitySampleDao().queryBuilder()
                    .where(
                            MoyoungActivitySampleDao.Properties.DeviceId.eq(dbDevice.getId()),
                            MoyoungActivitySampleDao.Properties.Timestamp.between(timestampFrom, timestampTo),
                            MoyoungActivitySampleDao.Properties.Steps.gt(0)
                    )
                    .orderAsc(MoyoungActivitySampleDao.Properties.Timestamp)
                    .list();
        } catch (final Exception e) {
            LOG.warn("MoyoungRing failed to read step samples", e);
            return Collections.emptyList();
        }
    }

    private void overlaySleep(final Map<Integer, GenericActivitySample> byTs, final int timestampFrom, final int timestampTo) {
        final List<GenericSleepStageSample> sleepStages = new ArrayList<>();

        final GenericSleepStageSample lastBeforeRange = sleepStageSampleProvider.getLastSampleBefore(timestampFrom * 1000L);
        if (lastBeforeRange != null && sleepStageEndMillis(lastBeforeRange) > timestampFrom * 1000L) {
            sleepStages.add(lastBeforeRange);
        }
        sleepStages.addAll(sleepStageSampleProvider.getAllSamples(timestampFrom * 1000L, timestampTo * 1000L));

        for (final GenericSleepStageSample stage : sleepStages) {
            final ActivityKind sleepKind = ActivityKind.fromCode(stage.getStage());
            if (!isSleepKind(sleepKind)) {
                continue;
            }

            final int stageStartSec = (int) Math.max(timestampFrom, stage.getTimestamp() / 1000L);
            final int stageEndSec = (int) Math.min(timestampTo, sleepStageEndMillis(stage) / 1000L);
            for (int ts = roundDownMinute(stageStartSec); ts <= stageEndSec; ts += 60) {
                if (ts < timestampFrom || ts > timestampTo) {
                    continue;
                }
                GenericActivitySample sample = byTs.get(ts);
                if (sample == null) {
                    sample = new GenericActivitySample();
                    sample.setProvider(this);
                    sample.setTimestamp(ts);
                    byTs.put(ts, sample);
                }
                sample.setRawKind(sleepKind.getCode());
                sample.setRawIntensity(ActivitySample.NOT_MEASURED);
            }
        }
    }

    private static long sleepStageEndMillis(final GenericSleepStageSample stage) {
        return stage.getTimestamp() + stage.getDuration() * 1000L;
    }

    private static int roundDownMinute(final int timestamp) {
        return timestamp / 60 * 60;
    }

    private static boolean isSleepKind(final ActivityKind kind) {
        return kind == ActivityKind.DEEP_SLEEP ||
                kind == ActivityKind.LIGHT_SLEEP ||
                kind == ActivityKind.REM_SLEEP ||
                kind == ActivityKind.AWAKE_SLEEP;
    }
}
