package nodomain.freeyourgadget.gadgetbridge.devices.xiaomi;

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

    private static List<Long> generatePulseTimestamps(final double breathsPerMinute, final double amplitudeMs) {
        final List<Long> pulseTimestamps = new ArrayList<>();
        final double respiratoryFrequencyHz = breathsPerMinute / 60d;
        long timestamp = 0L;

        while (timestamp <= WINDOW_MS) {
            pulseTimestamps.add(timestamp);
            final double seconds = timestamp / 1000d;
            final double rrIntervalMs = 1000d + amplitudeMs * Math.sin(2d * Math.PI * respiratoryFrequencyHz * seconds);
            timestamp += Math.round(rrIntervalMs);
        }

        return pulseTimestamps;
    }
}
