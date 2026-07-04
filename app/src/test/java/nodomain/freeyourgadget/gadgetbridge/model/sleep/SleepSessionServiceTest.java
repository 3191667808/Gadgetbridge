package nodomain.freeyourgadget.gadgetbridge.model.sleep;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;

public class SleepSessionServiceTest {
    @Test
    public void normalizeForSaveExtendsWithLightSleep() {
        CorrectedSleepSession session = session(
                40,
                280,
                Arrays.asList(
                        new SleepStage(100, 160, ActivityKind.DEEP_SLEEP),
                        new SleepStage(160, 220, ActivityKind.REM_SLEEP)
                )
        );

        SleepCorrectionRequest normalized = SleepSessionService.normalizeForSave(session);

        assertEquals(4, normalized.getStages().size());
        assertStage(normalized.getStages().get(0), 40, 100, ActivityKind.LIGHT_SLEEP);
        assertStage(normalized.getStages().get(1), 100, 160, ActivityKind.DEEP_SLEEP);
        assertStage(normalized.getStages().get(2), 160, 220, ActivityKind.REM_SLEEP);
        assertStage(normalized.getStages().get(3), 220, 280, ActivityKind.LIGHT_SLEEP);
    }

    @Test
    public void normalizeForSaveClipsOverlapsAndMergesSameStage() {
        CorrectedSleepSession session = session(
                100,
                420,
                Arrays.asList(
                        new SleepStage(40, 160, ActivityKind.DEEP_SLEEP),
                        new SleepStage(160, 220, ActivityKind.DEEP_SLEEP),
                        new SleepStage(180, 300, ActivityKind.AWAKE_SLEEP),
                        new SleepStage(300, 420, ActivityKind.UNKNOWN)
                )
        );

        SleepCorrectionRequest normalized = SleepSessionService.normalizeForSave(session);

        assertEquals(3, normalized.getStages().size());
        assertStage(normalized.getStages().get(0), 100, 220, ActivityKind.DEEP_SLEEP);
        assertStage(normalized.getStages().get(1), 220, 300, ActivityKind.AWAKE_SLEEP);
        assertStage(normalized.getStages().get(2), 300, 420, ActivityKind.LIGHT_SLEEP);
    }

    @Test
    public void totalsExcludeAwakeSleep() {
        SleepCorrectionRequest normalized = SleepSessionService.normalizeForSave(session(
                100,
                340,
                Arrays.asList(
                        new SleepStage(100, 160, ActivityKind.LIGHT_SLEEP),
                        new SleepStage(160, 220, ActivityKind.DEEP_SLEEP),
                        new SleepStage(220, 280, ActivityKind.REM_SLEEP),
                        new SleepStage(280, 340, ActivityKind.AWAKE_SLEEP)
                )
        ));

        assertEquals(180, duration(normalized, ActivityKind.LIGHT_SLEEP)
                + duration(normalized, ActivityKind.DEEP_SLEEP)
                + duration(normalized, ActivityKind.REM_SLEEP));
        assertEquals(60, duration(normalized, ActivityKind.AWAKE_SLEEP));
    }

    @Test
    public void calculateTotalsUsesExactStageDurations() {
        CorrectedSleepSession session = session(
                100,
                340,
                Arrays.asList(
                        new SleepStage(100, 220, ActivityKind.LIGHT_SLEEP),
                        new SleepStage(220, 280, ActivityKind.DEEP_SLEEP),
                        new SleepStage(280, 340, ActivityKind.AWAKE_SLEEP)
                )
        );

        SleepSessionService.SleepTotals totals = SleepSessionService.calculateTotals(
                Collections.singletonList(session),
                100,
                340
        );

        assertEquals(120, totals.getLightSleepSeconds());
        assertEquals(60, totals.getDeepSleepSeconds());
        assertEquals(0, totals.getRemSleepSeconds());
        assertEquals(60, totals.getAwakeSleepSeconds());
        assertEquals(180, totals.getTotalSleepSeconds());
    }

    @Test
    public void calculateTotalsClipsToRequestedRange() {
        CorrectedSleepSession session = session(
                100,
                400,
                Arrays.asList(
                        new SleepStage(100, 220, ActivityKind.LIGHT_SLEEP),
                        new SleepStage(220, 340, ActivityKind.REM_SLEEP),
                        new SleepStage(340, 400, ActivityKind.AWAKE_SLEEP)
                )
        );

        SleepSessionService.SleepTotals totals = SleepSessionService.calculateTotals(
                Collections.singletonList(session),
                160,
                370
        );

        assertEquals(60, totals.getLightSleepSeconds());
        assertEquals(0, totals.getDeepSleepSeconds());
        assertEquals(120, totals.getRemSleepSeconds());
        assertEquals(30, totals.getAwakeSleepSeconds());
        assertEquals(180, totals.getTotalSleepSeconds());
    }

    @Test(expected = IllegalArgumentException.class)
    public void normalizeForSaveRejectsTooShortSessions() {
        SleepSessionService.normalizeForSave(session(100, 130, Collections.emptyList()));
    }

    @Test
    public void correctionReplacesRawSessionInCorrectedRange() {
        CorrectedSleepSession rawSession = rawSession(
                300,
                420,
                Collections.singletonList(new SleepStage(300, 420, ActivityKind.LIGHT_SLEEP))
        );
        CorrectedSleepSession movedCorrection = session(
                120,
                240,
                300,
                420,
                Collections.singletonList(new SleepStage(300, 420, ActivityKind.REM_SLEEP))
        );

        assertTrue(SleepSessionService.isReplacedByCorrection(
                rawSession,
                Collections.singletonList(movedCorrection)
        ));
    }

    @Test
    public void mergeEditedSessionsIntoSamplesKeepsRawTimelineAndSubstitutesEditedSleep() {
        CorrectedSleepSession editedSession = session(
                120,
                240,
                120,
                240,
                Collections.singletonList(new SleepStage(120, 240, ActivityKind.REM_SLEEP))
        );
        List<ActivitySample> rawSamples = Arrays.asList(
                sample(0, ActivityKind.ACTIVITY),
                sample(120, ActivityKind.LIGHT_SLEEP),
                sample(180, ActivityKind.DEEP_SLEEP),
                sample(300, ActivityKind.ACTIVITY)
        );
        List<CorrectedSleepSample> correctedSleepSamples = SleepSessionService.toSamples(
                Collections.singletonList(editedSession),
                rawSamples,
                0,
                400
        );

        List<ActivitySample> merged = SleepSessionService.mergeEditedSessionsIntoSamples(
                rawSamples,
                Collections.singletonList(editedSession),
                correctedSleepSamples
        );

        assertEquals(4, merged.size());
        assertEquals(ActivityKind.ACTIVITY, merged.get(0).getKind());
        assertEquals(ActivityKind.REM_SLEEP, merged.get(1).getKind());
        assertEquals(ActivityKind.REM_SLEEP, merged.get(2).getKind());
        assertEquals(ActivityKind.ACTIVITY, merged.get(3).getKind());
    }

    @Test
    public void mergeEditedSessionsIntoSamplesRemovesSourceAndCorrectedRanges() {
        CorrectedSleepSession movedSession = session(
                120,
                240,
                300,
                420,
                Collections.singletonList(new SleepStage(300, 420, ActivityKind.REM_SLEEP))
        );
        List<ActivitySample> rawSamples = Arrays.asList(
                sample(0, ActivityKind.ACTIVITY),
                sample(120, ActivityKind.LIGHT_SLEEP),
                sample(180, ActivityKind.DEEP_SLEEP),
                sample(300, ActivityKind.ACTIVITY),
                sample(360, ActivityKind.ACTIVITY),
                sample(480, ActivityKind.ACTIVITY)
        );
        List<CorrectedSleepSample> correctedSleepSamples = SleepSessionService.toSamples(
                Collections.singletonList(movedSession),
                rawSamples,
                0,
                600
        );

        List<ActivitySample> merged = SleepSessionService.mergeEditedSessionsIntoSamples(
                rawSamples,
                Collections.singletonList(movedSession),
                correctedSleepSamples
        );

        assertEquals(4, merged.size());
        assertEquals(0, merged.get(0).getTimestamp());
        assertEquals(ActivityKind.ACTIVITY, merged.get(0).getKind());
        assertEquals(300, merged.get(1).getTimestamp());
        assertEquals(ActivityKind.REM_SLEEP, merged.get(1).getKind());
        assertEquals(360, merged.get(2).getTimestamp());
        assertEquals(ActivityKind.REM_SLEEP, merged.get(2).getKind());
        assertEquals(480, merged.get(3).getTimestamp());
        assertEquals(ActivityKind.ACTIVITY, merged.get(3).getKind());

        List<ActivitySample> stepSamples = SleepSessionService.removeEditedSessionRangesFromSamples(
                rawSamples,
                Collections.singletonList(movedSession)
        );

        assertEquals(2, stepSamples.size());
        assertEquals(0, stepSamples.get(0).getTimestamp());
        assertEquals(480, stepSamples.get(1).getTimestamp());
    }

    @Test
    public void timelineRemovesSourceOnlyCorrectionRangesFromActivitySamples() {
        CorrectedSleepSession movedOutSession = session(
                120,
                240,
                360,
                480,
                Collections.singletonList(new SleepStage(360, 480, ActivityKind.REM_SLEEP))
        );
        List<ActivitySample> rawSamples = Arrays.asList(
                sample(0, ActivityKind.ACTIVITY),
                sample(120, ActivityKind.LIGHT_SLEEP),
                sample(180, ActivityKind.DEEP_SLEEP)
        );

        SleepTimeline timeline = SleepSessionService.createTimeline(
                Collections.emptyList(),
                Collections.singletonList(movedOutSession),
                rawSamples,
                0,
                240
        );

        assertEquals(0, timeline.getSessions().size());
        assertEquals(1, timeline.getActivitySamplesWithEditedSleep().size());
        assertEquals(0, timeline.getActivitySamplesWithEditedSleep().get(0).getTimestamp());
        assertEquals(1, timeline.getActivitySamplesWithoutEditedSleep().size());
        assertEquals(0, timeline.getActivitySamplesWithoutEditedSleep().get(0).getTimestamp());
    }

    @Test
    public void correctedSleepSessionDoesNotExposeMutableStages() {
        SleepStage inputStage = new SleepStage(100, 220, ActivityKind.LIGHT_SLEEP);
        List<SleepStage> stages = new ArrayList<>();
        stages.add(inputStage);

        CorrectedSleepSession session = session(100, 220, stages);
        inputStage.setKind(ActivityKind.DEEP_SLEEP);
        stages.add(new SleepStage(220, 280, ActivityKind.REM_SLEEP));

        assertEquals(1, session.getStages().size());
        assertStage(session.getStages().get(0), 100, 220, ActivityKind.LIGHT_SLEEP);

        SleepStage returnedStage = session.getStages().get(0);
        returnedStage.setStartTs(40);
        returnedStage.setEndTs(160);
        returnedStage.setKind(ActivityKind.AWAKE_SLEEP);

        assertStage(session.getStages().get(0), 100, 220, ActivityKind.LIGHT_SLEEP);
    }

    @Test
    public void sleepTimelineDoesNotExposeMutableSessionStages() {
        SleepStage inputStage = new SleepStage(100, 220, ActivityKind.LIGHT_SLEEP);
        CorrectedSleepSession session = session(100, 220, Collections.singletonList(inputStage));
        List<CorrectedSleepSession> sessions = new ArrayList<>();
        sessions.add(session);

        SleepTimeline timeline = new SleepTimeline(
                sessions,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                new SleepSessionService.SleepTotals(120, 0, 0, 0),
                sessions,
                true
        );
        sessions.clear();

        assertEquals(1, timeline.getSessions().size());
        assertEquals(1, timeline.getCorrectionRanges().size());
        SleepStage returnedStage = timeline.getSessions().get(0).getStages().get(0);
        returnedStage.setKind(ActivityKind.REM_SLEEP);
        returnedStage.setStartTs(40);

        assertStage(timeline.getSessions().get(0).getStages().get(0), 100, 220, ActivityKind.LIGHT_SLEEP);
        assertStage(timeline.getCorrectionRanges().get(0).getStages().get(0), 100, 220, ActivityKind.LIGHT_SLEEP);
    }

    @Test
    public void toSamplesCopiesOverlayMetricsFromNearbyRawSample() {
        CorrectedSleepSession session = session(
                120,
                240,
                Collections.singletonList(new SleepStage(120, 240, ActivityKind.LIGHT_SLEEP))
        );
        List<ActivitySample> rawSamples = Collections.singletonList(
                sample(122, ActivityKind.LIGHT_SLEEP, 0.5f, 12, 34, 56, 61)
        );

        List<CorrectedSleepSample> samples = SleepSessionService.toSamples(
                Collections.singletonList(session),
                rawSamples,
                0,
                400
        );

        assertEquals(2, samples.size());
        assertEquals(120, samples.get(0).getTimestamp());
        assertEquals(0.5f, samples.get(0).getIntensity(), 0.001f);
        assertEquals(ActivitySample.NOT_MEASURED, samples.get(0).getSteps());
        assertEquals(ActivitySample.NOT_MEASURED, samples.get(0).getDistanceCm());
        assertEquals(ActivitySample.NOT_MEASURED, samples.get(0).getActiveCalories());
        assertEquals(61, samples.get(0).getHeartRate());
        assertEquals(180, samples.get(1).getTimestamp());
        assertEquals(ActivitySample.NOT_MEASURED, samples.get(1).getHeartRate());
    }

    private CorrectedSleepSession session(final long startTs, final long endTs, final List<SleepStage> stages) {
        return session(100, 220, startTs, endTs, stages);
    }

    private CorrectedSleepSession rawSession(final long startTs, final long endTs, final List<SleepStage> stages) {
        return new CorrectedSleepSession(
                null,
                1,
                1,
                startTs,
                endTs,
                startTs,
                endTs,
                0,
                0,
                false,
                stages
        );
    }

    private CorrectedSleepSession session(final long sourceStartTs,
                                          final long sourceEndTs,
                                          final long startTs,
                                          final long endTs,
                                          final List<SleepStage> stages) {
        return new CorrectedSleepSession(
                null,
                1,
                1,
                sourceStartTs,
                sourceEndTs,
                startTs,
                endTs,
                0,
                0,
                true,
                stages
        );
    }

    private void assertStage(final SleepStage stage, final long startTs, final long endTs, final ActivityKind kind) {
        assertEquals(startTs, stage.getStartTs());
        assertEquals(endTs, stage.getEndTs());
        assertEquals(kind, stage.getKind());
    }

    private long duration(final SleepCorrectionRequest session, final ActivityKind kind) {
        long total = 0;
        for (SleepStage stage : session.getStages()) {
            if (stage.getKind() == kind) {
                total += stage.getDurationSeconds();
            }
        }
        return total;
    }

    private ActivitySample sample(final int timestamp, final ActivityKind kind) {
        return sample(timestamp, kind, ActivitySample.NOT_MEASURED, ActivitySample.NOT_MEASURED, ActivitySample.NOT_MEASURED, ActivitySample.NOT_MEASURED, ActivitySample.NOT_MEASURED);
    }

    private ActivitySample sample(final int timestamp,
                                  final ActivityKind kind,
                                  final float intensity,
                                  final int steps,
                                  final int distanceCm,
                                  final int activeCalories,
                                  final int heartRate) {
        return new TestActivitySample(timestamp, kind, intensity, steps, distanceCm, activeCalories, heartRate);
    }

    private static class TestActivitySample implements ActivitySample {
        private final int timestamp;
        private final ActivityKind kind;
        private final float intensity;
        private final int steps;
        private final int distanceCm;
        private final int activeCalories;
        private int heartRate;

        private TestActivitySample(final int timestamp,
                                   final ActivityKind kind,
                                   final float intensity,
                                   final int steps,
                                   final int distanceCm,
                                   final int activeCalories,
                                   final int heartRate) {
            this.timestamp = timestamp;
            this.kind = kind;
            this.intensity = intensity;
            this.steps = steps;
            this.distanceCm = distanceCm;
            this.activeCalories = activeCalories;
            this.heartRate = heartRate;
        }

        @Override
        public int getTimestamp() {
            return timestamp;
        }

        @Override
        public SampleProvider<?> getProvider() {
            return null;
        }

        @Override
        public int getRawKind() {
            return kind.getCode();
        }

        @Override
        public ActivityKind getKind() {
            return kind;
        }

        @Override
        public int getRawIntensity() {
            return ActivitySample.NOT_MEASURED;
        }

        @Override
        public float getIntensity() {
            return intensity;
        }

        @Override
        public int getSteps() {
            return steps;
        }

        @Override
        public int getDistanceCm() {
            return distanceCm;
        }

        @Override
        public int getActiveCalories() {
            return activeCalories;
        }

        @Override
        public int getHeartRate() {
            return heartRate;
        }

        @Override
        public void setHeartRate(final int value) {
            heartRate = value;
        }
    }
}
