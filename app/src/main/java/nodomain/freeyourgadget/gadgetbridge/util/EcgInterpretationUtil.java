package nodomain.freeyourgadget.gadgetbridge.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.EcgInterpretation;
import nodomain.freeyourgadget.gadgetbridge.model.EcgRecord;
import nodomain.freeyourgadget.gadgetbridge.model.EcgSample;

public final class EcgInterpretationUtil {
    private static final int MIN_SAMPLES_FOR_ANALYSIS = 600;
    private static final int MIN_RR_INTERVALS_FOR_IRREGULAR = 4;
    private static final int MIN_RR_INTERVALS_FOR_NORMAL_HINT = 3;
    private static final float MIN_SIGNAL_RANGE = 0.4f;
    private static final float MIN_SIGNAL_RANGE_FOR_NORMAL_HINT = 0.3f;
    private static final float MAX_DERIVED_HR_DELTA_BPM = 12f;
    private static final float MAX_DERIVED_HR_DELTA_RATIO = 0.20f;
    private static final float RELAXED_DERIVED_HR_DELTA_BPM = 16f;
    private static final float RELAXED_DERIVED_HR_DELTA_RATIO = 0.25f;
    private static final float STRONG_IRREGULAR_RR_CV = 0.40f;
    private static final float STRONG_IRREGULAR_RR_SPREAD_SECONDS = 0.50f;
    private static final float STRONG_IRREGULAR_RR_SPREAD_RATIO = 0.60f;

    private EcgInterpretationUtil() {
    }

    public static EcgInterpretation.DeviceHint toDeviceHint(final long deviceHintCode) {
        if (deviceHintCode == 0) {
            return EcgInterpretation.DeviceHint.NORMAL;
        }
        if (deviceHintCode > 0) {
            return EcgInterpretation.DeviceHint.IRREGULAR;
        }
        return EcgInterpretation.DeviceHint.UNKNOWN;
    }

    public static EcgInterpretation interpret(final EcgRecord record, final List<EcgSample> waveform) {
        final EcgInterpretation.DeviceHint deviceHint = toDeviceHint(record.getDeviceHintCode());
        if (waveform == null || waveform.isEmpty()) {
            return new EcgInterpretation(deviceHint, EcgInterpretation.SignalQuality.INCONCLUSIVE, EcgInterpretation.Rhythm.INCONCLUSIVE);
        }

        final float[] values = new float[waveform.size()];
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        float totalAbsDiff = 0f;
        float previous = waveform.get(0).getValue();
        values[0] = previous;

        for (int i = 0; i < waveform.size(); i++) {
            final float value = waveform.get(i).getValue();
            values[i] = value;
            min = Math.min(min, value);
            max = Math.max(max, value);
            if (i > 0) {
                totalAbsDiff += Math.abs(value - previous);
            }
            previous = value;
        }

        final float range = max - min;
        final float averageAbsDiff = totalAbsDiff / Math.max(1, waveform.size() - 1);

        // Detect ADC clipping: count samples at the top/bottom 0.5% of the range.
        // A high fraction there indicates signal saturation rather than legitimate peaks.
        final float clipWindow = range * 0.005f;
        float clippedSamples = 0f;
        for (int i = 0; i < waveform.size(); i++) {
            final float value = values[i];
            if (value >= max - clipWindow || value <= min + clipWindow) {
                clippedSamples++;
            }
        }
        final float clippedRatio = clippedSamples / waveform.size();

        final EcgInterpretation.SignalQuality signalQuality;
        if (range < MIN_SIGNAL_RANGE_FOR_NORMAL_HINT) {
            signalQuality = EcgInterpretation.SignalQuality.NOISY;
        } else if (clippedRatio > 0.40f || averageAbsDiff > range * 1.10f) {
            signalQuality = EcgInterpretation.SignalQuality.NOISY;
        } else {
            signalQuality = EcgInterpretation.SignalQuality.GOOD;
        }

        final boolean normalHintWithUsableSignal = deviceHint == EcgInterpretation.DeviceHint.NORMAL
                && signalQuality == EcgInterpretation.SignalQuality.GOOD
                && range >= MIN_SIGNAL_RANGE_FOR_NORMAL_HINT;

        if (waveform.size() < MIN_SAMPLES_FOR_ANALYSIS) {
            final EcgInterpretation.Rhythm rhythm = normalHintWithUsableSignal
                    ? EcgInterpretation.Rhythm.REGULAR
                    : EcgInterpretation.Rhythm.INCONCLUSIVE;
            return new EcgInterpretation(deviceHint, signalQuality, rhythm);
        }

        final float sampleRateHz = estimateSampleRate(record, waveform);
        final float[] smoothedValues = smooth(values, Math.max(7, Math.round(sampleRateHz * 0.04f) | 1));
        final List<Integer> peaks = detectPeaks(smoothedValues, sampleRateHz, record.getAverageHeartRate());
        if (peaks.size() < 3) {
            final EcgInterpretation.Rhythm rhythm = normalHintWithUsableSignal && peaks.size() >= 2
                    ? EcgInterpretation.Rhythm.REGULAR
                    : EcgInterpretation.Rhythm.INCONCLUSIVE;
            return new EcgInterpretation(deviceHint, signalQuality, rhythm);
        }

        final List<Float> rrIntervals = new ArrayList<>(peaks.size() - 1);
        for (int i = 1; i < peaks.size(); i++) {
            rrIntervals.add((peaks.get(i) - peaks.get(i - 1)) / sampleRateHz);
        }

        float rrSum = 0f;
        float rrMin = Float.MAX_VALUE;
        float rrMax = -Float.MAX_VALUE;
        for (final float interval : rrIntervals) {
            rrSum += interval;
            rrMin = Math.min(rrMin, interval);
            rrMax = Math.max(rrMax, interval);
        }

        final float rrMean = rrSum / rrIntervals.size();
        float rrVariance = 0f;
        for (final float interval : rrIntervals) {
            final float delta = interval - rrMean;
            rrVariance += delta * delta;
        }
        final float rrStdDev = (float) Math.sqrt(rrVariance / rrIntervals.size());
        final float rrCoeffVar = rrMean > 0 ? rrStdDev / rrMean : 0f;
        final float derivedHeartRate = rrMean > 0 ? 60f / rrMean : 0f;

        final boolean strictPeakCountPlausible = record.getAverageHeartRate() <= 0 || hasPlausiblePeakCount(record, peaks.size(), 0.50f, 1.50f);
        final boolean relaxedPeakCountPlausible = record.getAverageHeartRate() <= 0 || hasPlausiblePeakCount(record, peaks.size(), 0.45f, 1.55f);
        final boolean strictHeartRateMatch = record.getAverageHeartRate() <= 0
                || Math.abs(derivedHeartRate - record.getAverageHeartRate()) <= Math.max(MAX_DERIVED_HR_DELTA_BPM, record.getAverageHeartRate() * MAX_DERIVED_HR_DELTA_RATIO);
        final boolean relaxedHeartRateMatch = record.getAverageHeartRate() <= 0
                || Math.abs(derivedHeartRate - record.getAverageHeartRate()) <= Math.max(RELAXED_DERIVED_HR_DELTA_BPM, record.getAverageHeartRate() * RELAXED_DERIVED_HR_DELTA_RATIO);
        final boolean looksRegular = rrCoeffVar <= 0.20f && (rrMax - rrMin) <= Math.max(0.25f, rrMean * 0.32f);
        final boolean irregular = rrCoeffVar > 0.34f || (rrMax - rrMin) > Math.max(0.45f, rrMean * 0.55f);
        final boolean stronglyIrregular = rrCoeffVar > STRONG_IRREGULAR_RR_CV
                || (rrMax - rrMin) > Math.max(STRONG_IRREGULAR_RR_SPREAD_SECONDS, rrMean * STRONG_IRREGULAR_RR_SPREAD_RATIO);

        final boolean normalHint = deviceHint == EcgInterpretation.DeviceHint.NORMAL;
        final boolean canTrustNormalHint = normalHint
                && signalQuality == EcgInterpretation.SignalQuality.GOOD
                && rrIntervals.size() >= MIN_RR_INTERVALS_FOR_NORMAL_HINT;

        final EcgInterpretation.Rhythm rhythm;
        if (signalQuality == EcgInterpretation.SignalQuality.NOISY) {
            rhythm = EcgInterpretation.Rhythm.INCONCLUSIVE;
        } else if (canTrustNormalHint) {
            // Device says Normal, signal is good, enough RR intervals detected - trust the device
            rhythm = EcgInterpretation.Rhythm.REGULAR;
        } else if (rrIntervals.size() < MIN_RR_INTERVALS_FOR_IRREGULAR) {
            rhythm = normalHintWithUsableSignal ? EcgInterpretation.Rhythm.REGULAR : EcgInterpretation.Rhythm.INCONCLUSIVE;
        } else if (stronglyIrregular && !normalHint) {
            rhythm = EcgInterpretation.Rhythm.IRREGULAR;
        } else if (irregular && !canTrustNormalHint) {
            rhythm = EcgInterpretation.Rhythm.IRREGULAR;
        } else if (strictPeakCountPlausible && strictHeartRateMatch) {
            rhythm = EcgInterpretation.Rhythm.REGULAR;
        } else if (normalHintWithUsableSignal && (relaxedPeakCountPlausible || relaxedHeartRateMatch || looksRegular)) {
            rhythm = EcgInterpretation.Rhythm.REGULAR;
        } else {
            rhythm = EcgInterpretation.Rhythm.INCONCLUSIVE;
        }

        return new EcgInterpretation(deviceHint, signalQuality, rhythm);
    }

    private static float estimateSampleRate(final EcgRecord record, final List<EcgSample> waveform) {
        final long durationMs = record.getEndTimestamp() - record.getStartTimestamp();
        if (durationMs > 0) {
            return Math.max(1f, (waveform.size() * 1000f) / durationMs);
        }

        if (waveform.size() < 2) {
            return 1f;
        }

        final List<Integer> deltas = new ArrayList<>(waveform.size() - 1);
        for (int i = 1; i < waveform.size(); i++) {
            final int delta = waveform.get(i).getTimeDeltaMs() - waveform.get(i - 1).getTimeDeltaMs();
            if (delta > 0) {
                deltas.add(delta);
            }
        }
        if (deltas.isEmpty()) {
            return 1f;
        }
        Collections.sort(deltas);
        final int medianDelta = deltas.get(deltas.size() / 2);
        return medianDelta > 0 ? 1000f / medianDelta : 1f;
    }

    private static boolean hasPlausiblePeakCount(final EcgRecord record,
                                                 final int peakCount,
                                                 final float minRatio,
                                                 final float maxRatio) {
        final long durationMs = record.getEndTimestamp() - record.getStartTimestamp();
        if (durationMs <= 0 || record.getAverageHeartRate() <= 0) {
            return true;
        }

        final float expectedPeaks = (durationMs / 60000f) * record.getAverageHeartRate();
        return peakCount >= expectedPeaks * minRatio && peakCount <= expectedPeaks * maxRatio;
    }

    private static float[] smooth(final float[] values, final int windowSize) {
        if (windowSize <= 1 || values.length < windowSize) {
            return values;
        }

        final float[] smoothed = new float[values.length];
        final int halfWindow = windowSize / 2;
        for (int i = 0; i < values.length; i++) {
            final int start = Math.max(0, i - halfWindow);
            final int end = Math.min(values.length - 1, i + halfWindow);
            float sum = 0f;
            for (int j = start; j <= end; j++) {
                sum += values[j];
            }
            smoothed[i] = sum / (end - start + 1);
        }
        return smoothed;
    }

    private static List<Integer> detectPeaks(final float[] values, final float sampleRateHz, final int averageHeartRate) {
        float sum = 0f;
        float sumSquares = 0f;
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (final float value : values) {
            sum += value;
            sumSquares += value * value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        final float mean = sum / values.length;
        final float variance = Math.max(0f, (sumSquares / values.length) - (mean * mean));
        final float stdDev = (float) Math.sqrt(variance);
        final float range = max - min;
        final float threshold = mean + Math.max(stdDev * 1.5f, range * 0.24f);

        final float expectedRrSeconds = averageHeartRate > 0 ? 60f / Math.max(averageHeartRate, 1) : 0.85f;
        final float minDistanceSeconds = Math.max(0.38f, Math.min(0.95f, expectedRrSeconds * 0.58f));
        final int minDistanceSamples = Math.max(1, Math.round(sampleRateHz * minDistanceSeconds));
        final int prominenceWindow = Math.max(1, Math.round(sampleRateHz * 0.12f));
        final float minProminence = Math.max(stdDev * 0.60f, range * 0.12f);

        final List<Integer> peaks = new ArrayList<>();
        int lastPeak = -minDistanceSamples;
        for (int i = 1; i < values.length - 1; i++) {
            final float current = values[i];
            if (current < threshold) {
                continue;
            }
            if (current < values[i - 1] || current < values[i + 1]) {
                continue;
            }
            float localMin = current;
            final int windowStart = Math.max(0, i - prominenceWindow);
            final int windowEnd = Math.min(values.length - 1, i + prominenceWindow);
            for (int j = windowStart; j <= windowEnd; j++) {
                localMin = Math.min(localMin, values[j]);
            }
            if ((current - localMin) < minProminence) {
                continue;
            }
            if (i - lastPeak < minDistanceSamples) {
                if (!peaks.isEmpty() && current > values[lastPeak]) {
                    peaks.set(peaks.size() - 1, i);
                    lastPeak = i;
                }
                continue;
            }
            peaks.add(i);
            lastPeak = i;
        }

        return peaks;
    }
}
