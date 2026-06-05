/*  Copyright (C) 2026 Freeyourgadget

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.HeartPulseSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.devices.TimeSampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.HeartPulseSample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.RespiratoryRateSample;

public class XiaomiSleepRespiratoryRateSampleProvider implements TimeSampleProvider<RespiratoryRateSample> {
    private static final long WINDOW_MS = 5 * 60 * 1000L;
    private static final long STEP_MS = WINDOW_MS;
    private static final long MIN_RR_MS = 300L;
    private static final long MAX_RR_MS = 2000L;
    private static final int MIN_INTERVALS_PER_WINDOW = 120;
    private static final double MIN_RESPIRATORY_FREQUENCY_HZ = 0.10d;
    private static final double MAX_RESPIRATORY_FREQUENCY_HZ = 0.50d;
    private static final double FREQUENCY_STEP_HZ = 0.005d;
    private static final double MIN_RR_VARIANCE_MS2 = 4d;

    private final HeartPulseSampleProvider heartPulseSampleProvider;

    public XiaomiSleepRespiratoryRateSampleProvider(final GBDevice device, final DaoSession session) {
        this.heartPulseSampleProvider = new HeartPulseSampleProvider(device, session);
    }

    @NonNull
    @Override
    public List<RespiratoryRateSample> getAllSamples(final long timestampFrom, final long timestampTo) {
        if (timestampTo <= timestampFrom) {
            return Collections.emptyList();
        }

        final List<HeartPulseSample> heartPulseSamples = heartPulseSampleProvider.getAllSamples(timestampFrom, timestampTo);
        if (heartPulseSamples.size() < MIN_INTERVALS_PER_WINDOW + 1) {
            return Collections.emptyList();
        }

        final List<Long> pulseTimestamps = new ArrayList<>(heartPulseSamples.size());
        for (final HeartPulseSample heartPulseSample : heartPulseSamples) {
            pulseTimestamps.add(heartPulseSample.getTimestamp());
        }

        return estimateSamples(pulseTimestamps, timestampFrom, timestampTo);
    }

    @Override
    public void addSample(final RespiratoryRateSample timeSample) {
        throw new UnsupportedOperationException("This sample provider is read-only!");
    }

    @Override
    public void addSamples(final List<RespiratoryRateSample> timeSamples) {
        throw new UnsupportedOperationException("This sample provider is read-only!");
    }

    @Override
    public RespiratoryRateSample createSample() {
        throw new UnsupportedOperationException("This sample provider is read-only!");
    }

    @Nullable
    @Override
    public RespiratoryRateSample getLatestSample() {
        final HeartPulseSample latestPulse = heartPulseSampleProvider.getLatestSample();
        if (latestPulse == null) {
            return null;
        }

        return getLatestSample(latestPulse.getTimestamp());
    }

    @Nullable
    @Override
    public RespiratoryRateSample getLatestSample(final long until) {
        final HeartPulseSample latestPulse = heartPulseSampleProvider.getLatestSample(until);
        if (latestPulse == null) {
            return null;
        }

        final long timestampTo = Math.min(until, latestPulse.getTimestamp());
        final List<RespiratoryRateSample> samples = getAllSamples(timestampTo - WINDOW_MS, timestampTo);
        if (samples.isEmpty()) {
            return null;
        }

        return samples.get(samples.size() - 1);
    }

    @Nullable
    @Override
    public RespiratoryRateSample getFirstSample() {
        final HeartPulseSample firstPulse = heartPulseSampleProvider.getFirstSample();
        if (firstPulse == null) {
            return null;
        }

        final List<RespiratoryRateSample> samples = getAllSamples(firstPulse.getTimestamp(), firstPulse.getTimestamp() + WINDOW_MS);
        if (samples.isEmpty()) {
            return null;
        }

        return samples.get(0);
    }

    @NonNull
    static List<RespiratoryRateSample> estimateSamples(final List<Long> pulseTimestamps, final long timestampFrom, final long timestampTo) {
        if (pulseTimestamps.size() < MIN_INTERVALS_PER_WINDOW + 1 || timestampTo <= timestampFrom) {
            return Collections.emptyList();
        }

        final List<Long> sortedPulseTimestamps = new ArrayList<>(pulseTimestamps);
        sortedPulseTimestamps.sort(Comparator.naturalOrder());

        final List<RespiratoryRateSample> samples = new ArrayList<>();
        for (long windowStart = timestampFrom; windowStart + WINDOW_MS <= timestampTo; windowStart += STEP_MS) {
            final long windowEnd = windowStart + WINDOW_MS;
            final List<Long> rrMidpoints = new ArrayList<>();
            final List<Double> rrIntervals = new ArrayList<>();

            for (int i = 1; i < sortedPulseTimestamps.size(); i++) {
                final long previousTimestamp = sortedPulseTimestamps.get(i - 1);
                final long timestamp = sortedPulseTimestamps.get(i);
                if (timestamp < windowStart) {
                    continue;
                }
                if (previousTimestamp > windowEnd) {
                    break;
                }

                final long rrInterval = timestamp - previousTimestamp;
                if (rrInterval < MIN_RR_MS || rrInterval > MAX_RR_MS) {
                    continue;
                }

                final long rrMidpoint = previousTimestamp + (rrInterval / 2L);
                if (rrMidpoint < windowStart || rrMidpoint > windowEnd) {
                    continue;
                }

                rrMidpoints.add(rrMidpoint);
                rrIntervals.add((double) rrInterval);
            }

            final float respiratoryRate = estimateRespiratoryRate(rrMidpoints, rrIntervals);
            if (!Float.isNaN(respiratoryRate)) {
                samples.add(new XiaomiSleepRespiratoryRateSample(windowStart + WINDOW_MS / 2L, respiratoryRate));
            }
        }

        return samples;
    }

    static float estimateRespiratoryRate(final List<Long> rrMidpoints, final List<Double> rrIntervals) {
        if (rrIntervals.size() < MIN_INTERVALS_PER_WINDOW) {
            return Float.NaN;
        }

        double sum = 0d;
        for (final double rrInterval : rrIntervals) {
            sum += rrInterval;
        }
        final double mean = sum / rrIntervals.size();

        double variance = 0d;
        for (final double rrInterval : rrIntervals) {
            final double centered = rrInterval - mean;
            variance += centered * centered;
        }
        variance /= rrIntervals.size();
        if (variance < MIN_RR_VARIANCE_MS2) {
            return Float.NaN;
        }

        double bestFrequency = 0d;
        double bestPower = 0d;
        final long referenceTimestamp = rrMidpoints.get(0);

        for (double frequency = MIN_RESPIRATORY_FREQUENCY_HZ; frequency <= MAX_RESPIRATORY_FREQUENCY_HZ; frequency += FREQUENCY_STEP_HZ) {
            double real = 0d;
            double imaginary = 0d;
            for (int i = 0; i < rrIntervals.size(); i++) {
                final double seconds = (rrMidpoints.get(i) - referenceTimestamp) / 1000d;
                final double centered = rrIntervals.get(i) - mean;
                final double phase = 2d * Math.PI * frequency * seconds;
                real += centered * Math.cos(phase);
                imaginary += centered * Math.sin(phase);
            }

            final double power = real * real + imaginary * imaginary;
            if (power > bestPower) {
                bestPower = power;
                bestFrequency = frequency;
            }
        }

        if (bestFrequency == 0d) {
            return Float.NaN;
        }

        return (float) (bestFrequency * 60d);
    }

    private static class XiaomiSleepRespiratoryRateSample implements RespiratoryRateSample {
        private final long timestamp;
        private final float respiratoryRate;

        XiaomiSleepRespiratoryRateSample(final long timestamp, final float respiratoryRate) {
            this.timestamp = timestamp;
            this.respiratoryRate = respiratoryRate;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public float getRespiratoryRate() {
            return respiratoryRate;
        }
    }
}
