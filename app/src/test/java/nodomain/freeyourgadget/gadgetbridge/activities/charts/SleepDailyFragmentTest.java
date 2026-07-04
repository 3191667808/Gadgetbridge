package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.CorrectedSleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.CorrectedSleepSample;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepSessionService;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepStage;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepTimeline;

public class SleepDailyFragmentTest {
    @Test
    public void displaySamplesUseCorrectedActivityTimelineFor24hRange() {
        final CorrectedSleepSample sleepSample = new CorrectedSleepSample(
                120,
                1,
                1,
                ActivityKind.LIGHT_SLEEP,
                ActivitySample.NOT_MEASURED,
                ActivitySample.NOT_MEASURED
        );
        final List<ActivitySample> correctedActivitySamples = Arrays.asList(
                sample(0, ActivityKind.ACTIVITY),
                sleepSample,
                sample(240, ActivityKind.ACTIVITY)
        );
        final SleepTimeline timeline = new SleepTimeline(
                Collections.emptyList(),
                Collections.singletonList(sleepSample),
                correctedActivitySamples,
                correctedActivitySamples,
                new SleepSessionService.SleepTotals(60, 0, 0, 0)
        );

        final List<? extends ActivitySample> displaySamples = SleepDailyFragment.getDisplaySamples(timeline, true);

        assertEquals(3, displaySamples.size());
        assertEquals(ActivityKind.ACTIVITY, displaySamples.get(0).getKind());
        assertEquals(ActivityKind.LIGHT_SLEEP, displaySamples.get(1).getKind());
        assertEquals(ActivityKind.ACTIVITY, displaySamples.get(2).getKind());
    }

    @Test
    public void displaySamplesUseSleepOnlyTimelineForSleepRange() {
        final CorrectedSleepSample sleepSample = new CorrectedSleepSample(
                120,
                1,
                1,
                ActivityKind.LIGHT_SLEEP,
                ActivitySample.NOT_MEASURED,
                ActivitySample.NOT_MEASURED
        );
        final List<ActivitySample> correctedActivitySamples = Arrays.asList(
                sample(0, ActivityKind.ACTIVITY),
                sleepSample,
                sample(240, ActivityKind.ACTIVITY)
        );
        final SleepTimeline timeline = new SleepTimeline(
                Collections.emptyList(),
                Collections.singletonList(sleepSample),
                correctedActivitySamples,
                correctedActivitySamples,
                new SleepSessionService.SleepTotals(60, 0, 0, 0)
        );

        final List<? extends ActivitySample> displaySamples = SleepDailyFragment.getDisplaySamples(timeline, false);

        assertEquals(1, displaySamples.size());
        assertEquals(ActivityKind.LIGHT_SLEEP, displaySamples.get(0).getKind());
    }

    @Test
    public void sleepAmountsUseTimelineTotals() {
        final CorrectedSleepSession session = new CorrectedSleepSession(
                null,
                1,
                1,
                0,
                3_600,
                0,
                3_600,
                0,
                0,
                true,
                Collections.singletonList(new SleepStage(0, 3_600, ActivityKind.LIGHT_SLEEP))
        );
        final SleepTimeline timeline = new SleepTimeline(
                Collections.singletonList(session),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                new SleepSessionService.SleepTotals(600, 120, 180, 60)
        );

        final SleepDailyFragment.MySleepChartsData data = SleepDailyFragment.refreshSleepAmounts(
                timeline,
                Collections.emptyList()
        );

        assertEquals(900, data.getTotalSleep());
        assertEquals(60, data.getTotalAwake());
        assertEquals(180, data.getTotalRem());
        assertEquals(120, data.getTotalDeep());
        assertEquals(600, data.getTotalLight());
        assertEquals(1, data.getSleepSessions().size());
        assertSession(session, data.getSleepSessions().get(0));
    }

    @Test
    public void sleepAmountsExposeSourceOnlyCorrectionsForDailyUi() {
        final CorrectedSleepSession movedOutSession = new CorrectedSleepSession(
                42L,
                1,
                1,
                120,
                240,
                360,
                480,
                0,
                0,
                true,
                Collections.singletonList(new SleepStage(360, 480, ActivityKind.LIGHT_SLEEP))
        );
        final SleepTimeline timeline = new SleepTimeline(
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                new SleepSessionService.SleepTotals(0, 0, 0, 0),
                Collections.singletonList(movedOutSession),
                true
        );

        final SleepDailyFragment.MySleepChartsData data = SleepDailyFragment.refreshSleepAmounts(
                timeline,
                Collections.emptyList()
        );

        assertEquals(Collections.emptyList(), data.getSleepSessions());
        assertEquals(1, data.getCorrectionRanges().size());
        assertSession(movedOutSession, data.getCorrectionRanges().get(0));
        assertEquals(Collections.singletonList(42L), data.getCorrectionSessionIds());
        assertTrue(data.hasCorrections());
        assertTrue(data.canResetSourceOnlyCorrections());
        assertTrue(SleepDailyFragment.shouldShowEditedIndicator(data));
        assertTrue(SleepDailyFragment.shouldShowEditButton(data));
    }

    private static ActivitySample sample(final int timestamp, final ActivityKind kind) {
        return new MockSample(timestamp, kind);
    }

    private static void assertSession(final CorrectedSleepSession expected, final CorrectedSleepSession actual) {
        assertEquals(expected.getId(), actual.getId());
        assertEquals(expected.getDeviceId(), actual.getDeviceId());
        assertEquals(expected.getUserId(), actual.getUserId());
        assertEquals(expected.getSourceStartTs(), actual.getSourceStartTs());
        assertEquals(expected.getSourceEndTs(), actual.getSourceEndTs());
        assertEquals(expected.getStartTs(), actual.getStartTs());
        assertEquals(expected.getEndTs(), actual.getEndTs());
        assertEquals(expected.getCreatedAt(), actual.getCreatedAt());
        assertEquals(expected.getUpdatedAt(), actual.getUpdatedAt());
        assertEquals(expected.isEdited(), actual.isEdited());
        assertEquals(expected.getStages().size(), actual.getStages().size());
        for (int i = 0; i < expected.getStages().size(); i++) {
            assertStage(expected.getStages().get(i), actual.getStages().get(i));
        }
    }

    private static void assertStage(final SleepStage expected, final SleepStage actual) {
        assertEquals(expected.getStartTs(), actual.getStartTs());
        assertEquals(expected.getEndTs(), actual.getEndTs());
        assertEquals(expected.getKind(), actual.getKind());
    }

    private static class MockSample implements ActivitySample {
        private final int timestamp;
        private final ActivityKind kind;

        MockSample(final int timestamp, final ActivityKind kind) {
            this.timestamp = timestamp;
            this.kind = kind;
        }

        @Override public int getTimestamp() { return timestamp; }
        @Override public SampleProvider<?> getProvider() { return null; }
        @Override public int getRawKind() { return kind.getCode(); }
        @Override public ActivityKind getKind() { return kind; }
        @Override public int getRawIntensity() { return NOT_MEASURED; }
        @Override public float getIntensity() { return NOT_MEASURED; }
        @Override public int getSteps() { return NOT_MEASURED; }
        @Override public int getDistanceCm() { return NOT_MEASURED; }
        @Override public int getActiveCalories() { return NOT_MEASURED; }
        @Override public int getHeartRate() { return NOT_MEASURED; }
        @Override public void setHeartRate(final int value) {}
    }
}
