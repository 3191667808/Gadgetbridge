package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import com.github.mikephil.charting.data.Entry;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StepsDailyAverageCalculatorTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
    private static final int DAY = 24 * 60 * 60;

    @Test
    public void buildsCumulativeAverageForSparseFirstSamples() {
        final List<Entry> entries = StepsDailyAverageCalculator.buildAverageEntries(
                Arrays.asList(
                        sample(2 * 60 * 60, 100),
                        sample(3 * 60 * 60, 50)
                ),
                0,
                1,
                60,
                UTC
        );

        assertEquals(26, entries.size());
        assertEntry(entries.get(0), 0f, 0f);
        assertEntry(entries.get(1), 30 * 60f, 0f);
        assertEntry(entries.get(2), 90 * 60f, 0f);
        assertEntry(entries.get(3), 150 * 60f, 100f);
        assertEntry(entries.get(4), 210 * 60f, 150f);
        assertEntry(entries.get(25), DAY, 150f);
    }

    @Test
    public void includesZeroStepDaysInAverageDenominator() {
        final List<Entry> entries = StepsDailyAverageCalculator.buildAverageEntries(
                Arrays.asList(
                        sample(60 * 60, 0),
                        sample(DAY + 60 * 60, 100)
                ),
                0,
                2,
                60,
                UTC
        );

        assertEntry(entries.get(3), 150 * 60f, 50f);
        assertEntry(entries.get(25), DAY, 50f);
    }

    @Test
    public void separatesStepsAcrossDayBoundaries() {
        final List<Entry> entries = StepsDailyAverageCalculator.buildAverageEntries(
                Arrays.asList(
                        sample(DAY - 60, 100),
                        sample(DAY + 60 * 60, 200)
                ),
                0,
                2,
                60,
                UTC
        );

        assertEntry(entries.get(1), 30 * 60f, 0f);
        assertEntry(entries.get(2), 90 * 60f, 100f);
        assertEntry(entries.get(24), 23 * 60 * 60 + 30 * 60f, 150f);
        assertEntry(entries.get(25), DAY, 150f);
    }

    @Test
    public void sortsProviderSamplesBeforeBucketing() {
        final List<Entry> entries = StepsDailyAverageCalculator.buildAverageEntries(
                Arrays.asList(
                        sample(3 * 60 * 60, 50),
                        sample(2 * 60 * 60, 100)
                ),
                0,
                1,
                60,
                UTC
        );

        assertEntry(entries.get(3), 150 * 60f, 100f);
        assertEntry(entries.get(4), 210 * 60f, 150f);
    }

    @Test
    public void ignoresDaysWithoutMeasuredSteps() {
        final List<Entry> entries = StepsDailyAverageCalculator.buildAverageEntries(
                Collections.singletonList(sample(60 * 60, ActivitySample.NOT_MEASURED)),
                0,
                1,
                60,
                UTC
        );

        assertTrue(entries.isEmpty());
    }

    private static void assertEntry(final Entry entry, final float expectedX, final float expectedY) {
        assertEquals(expectedX, entry.getX(), 0.001f);
        assertEquals(expectedY, entry.getY(), 0.001f);
    }

    private static ActivitySample sample(final int timestamp, final int steps) {
        return new TestActivitySample(timestamp, steps);
    }

    private static class TestActivitySample implements ActivitySample {
        private final int timestamp;
        private final int steps;

        TestActivitySample(final int timestamp, final int steps) {
            this.timestamp = timestamp;
            this.steps = steps;
        }

        @Override
        public SampleProvider<?> getProvider() {
            return null;
        }

        @Override
        public int getRawKind() {
            return ActivityKind.UNKNOWN.getCode();
        }

        @Override
        public ActivityKind getKind() {
            return ActivityKind.UNKNOWN;
        }

        @Override
        public int getRawIntensity() {
            return ActivitySample.NOT_MEASURED;
        }

        @Override
        public float getIntensity() {
            return 0;
        }

        @Override
        public int getSteps() {
            return steps;
        }

        @Override
        public int getDistanceCm() {
            return ActivitySample.NOT_MEASURED;
        }

        @Override
        public int getActiveCalories() {
            return ActivitySample.NOT_MEASURED;
        }

        @Override
        public int getHeartRate() {
            return ActivitySample.NOT_MEASURED;
        }

        @Override
        public void setHeartRate(final int value) {
        }

        @Override
        public int getTimestamp() {
            return timestamp;
        }
    }
}
