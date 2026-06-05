package nodomain.freeyourgadget.gadgetbridge.devices.xiaomi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.RespiratoryRateSample;

public class XiaomiSleepRespiratoryRateSampleProviderTest {
    private static final long WINDOW_MS = 5 * 60 * 1000L;

    @Test
    public void estimatesRespiratoryRateFromRrIntervalVariation() {
        final List<Long> pulseTimestamps = generatePulseTimestamps(15d, 80d);

        final List<RespiratoryRateSample> samples = XiaomiSleepRespiratoryRateSampleProvider.estimateSamples(
                pulseTimestamps,
                0,
                WINDOW_MS
        );

        assertFalse(samples.isEmpty());
        final float respiratoryRate = samples.get(0).getRespiratoryRate();
        assertTrue(respiratoryRate >= 13f && respiratoryRate <= 17f);
    }

    @Test
    public void ignoresFlatRrIntervals() {
        final List<Long> pulseTimestamps = generatePulseTimestamps(15d, 0d);

        final List<RespiratoryRateSample> samples = XiaomiSleepRespiratoryRateSampleProvider.estimateSamples(
                pulseTimestamps,
                0,
                WINDOW_MS
        );

        assertTrue(samples.isEmpty());
    }

    @Test
    public void estimatesRespiratoryRateAcrossMultipleWindows() {
        final long timestampFrom = 1_000_000L;
        final int windowCount = 3;
        final List<Long> pulseTimestamps = generatePulseTimestamps(timestampFrom, WINDOW_MS * windowCount, 15d, 80d);

        final List<RespiratoryRateSample> samples = XiaomiSleepRespiratoryRateSampleProvider.estimateSamples(
                pulseTimestamps,
                timestampFrom,
                timestampFrom + WINDOW_MS * windowCount
        );

        assertEquals(windowCount, samples.size());
        for (int i = 0; i < windowCount; i++) {
            assertEquals(timestampFrom + WINDOW_MS / 2L + WINDOW_MS * i, samples.get(i).getTimestamp());
            final float respiratoryRate = samples.get(i).getRespiratoryRate();
            assertTrue(respiratoryRate >= 13f && respiratoryRate <= 17f);
        }
    }

    private static List<Long> generatePulseTimestamps(final double breathsPerMinute, final double amplitudeMs) {
        return generatePulseTimestamps(0L, WINDOW_MS, breathsPerMinute, amplitudeMs);
    }

    private static List<Long> generatePulseTimestamps(final long timestampFrom, final long durationMs, final double breathsPerMinute, final double amplitudeMs) {
        final List<Long> pulseTimestamps = new ArrayList<>();
        final double respiratoryFrequencyHz = breathsPerMinute / 60d;
        long timestamp = timestampFrom;
        final long timestampTo = timestampFrom + durationMs;

        while (timestamp <= timestampTo) {
            pulseTimestamps.add(timestamp);
            final double seconds = (timestamp - timestampFrom) / 1000d;
            final double rrIntervalMs = 1000d + amplitudeMs * Math.sin(2d * Math.PI * respiratoryFrequencyHz * seconds);
            timestamp += Math.round(rrIntervalMs);
        }

        return pulseTimestamps;
    }
}
