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
package nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;

public class WomensHealthMetricsTest {

    @Test
    public void phase_resolves_to_menstruation_in_first_days() {
        WomensHealthMetrics.CyclePhase p = WomensHealthMetrics.estimatePhase(
                2, 28, 5, 0, 0, 0, 0);
        assertEquals(WomensHealthMetrics.PHASE_MENSTRUATION, p.phase);
        assertEquals(3, p.cycleDay);
    }

    @Test
    public void phase_resolves_to_follicular_mid_first_half() {
        WomensHealthMetrics.CyclePhase p = WomensHealthMetrics.estimatePhase(
                7, 28, 5, 0, 0, 0, 0);
        assertEquals(WomensHealthMetrics.PHASE_FOLLICULAR, p.phase);
    }

    @Test
    public void phase_resolves_to_ovulation_around_day_14() {
        WomensHealthMetrics.CyclePhase p = WomensHealthMetrics.estimatePhase(
                13, 28, 5, 0, 0, 0, 0);
        assertEquals(WomensHealthMetrics.PHASE_OVULATION, p.phase);
    }

    @Test
    public void phase_resolves_to_luteal_in_second_half() {
        WomensHealthMetrics.CyclePhase p = WomensHealthMetrics.estimatePhase(
                20, 28, 5, 0, 0, 0, 0);
        assertEquals(WomensHealthMetrics.PHASE_LUTEAL, p.phase);
    }

    @Test
    public void biometric_corroboration_boosts_luteal_confidence() {
        WomensHealthMetrics.CyclePhase p = WomensHealthMetrics.estimatePhase(
                23, 28, 5, 70, 66, 38.0, 45.0);
        assertEquals(WomensHealthMetrics.PHASE_LUTEAL, p.phase);
        assertTrue("confidence=" + p.confidencePct, p.confidencePct >= 90);
    }

    @Test
    public void pms_likelihood_high_close_to_period_with_signals() {
        int score = WomensHealthMetrics.pmsLikelihood(27, 28, 4, -0.20, 0.70);
        assertTrue("score=" + score, score >= 60);
    }

    @Test
    public void pms_likelihood_zero_in_follicular() {
        assertEquals(0, WomensHealthMetrics.pmsLikelihood(7, 28, 0, 0, 0.9));
    }

    @Test
    public void pms_likelihood_accepts_neutral_sleep_session() {
        SleepSession sleep = sleepSession(0, 10 * 60 * 60 * 1000L, 5, 2, 0);
        int score = WomensHealthMetrics.pmsLikelihood(27, 28, 70, 66, 36.0, 45.0, sleep);
        assertTrue("score=" + score, score >= 60);
    }

    @Test
    public void resting_hr_uses_neutral_hr_and_sleep_inputs() {
        SleepSession sleep = sleepSession(1_000, 11_000, 8, 2, 0);
        java.util.List<Sample> hr = Arrays.asList(
                new Sample(500, 50),
                new Sample(2_000, 67),
                new Sample(3_000, 61),
                new Sample(4_000, 63),
                new Sample(12_000, 45));
        assertEquals(61, WomensHealthMetrics.restingHrFromSleep(hr, Collections.singletonList(sleep)));
    }

    @Test
    public void fertile_window_covers_canonical_days() {
        assertTrue(WomensHealthMetrics.inFertileWindow(11, 28));
        assertTrue(WomensHealthMetrics.inFertileWindow(15, 28));
        assertFalse(WomensHealthMetrics.inFertileWindow(5, 28));
        assertFalse(WomensHealthMetrics.inFertileWindow(22, 28));
    }

    @Test
    public void cycle_irregularity_zero_for_constant_cycles() {
        assertEquals(0, WomensHealthMetrics.cycleIrregularity(Arrays.asList(28, 28, 28, 28)));
    }

    @Test
    public void cycle_irregularity_positive_for_variable_cycles() {
        int cv = WomensHealthMetrics.cycleIrregularity(Arrays.asList(25, 30, 22, 33, 28, 26));
        assertTrue("cv=" + cv, cv > 5);
    }

    @Test
    public void sustained_elevation_hint_triggers_after_extended_luteal() {
        assertTrue(WomensHealthMetrics.sustainedElevationPregnancyHint(35, 28, 8, 4));
    }

    @Test
    public void sustained_elevation_does_not_trigger_too_early() {
        assertFalse(WomensHealthMetrics.sustainedElevationPregnancyHint(25, 28, 7, 4));
    }

    private static SleepSession sleepSession(final long startMs, final long endMs,
                                             final int asleepHours, final int awakeHours,
                                             final int remHours) {
        SleepSession sleep = new SleepSession();
        sleep.startTimeMs = startMs;
        sleep.endTimeMs = endMs;
        sleep.deepSleepSec = asleepHours * 60 * 60;
        sleep.lightSleepSec = 0;
        sleep.remSleepSec = remHours * 60 * 60;
        sleep.wakeDurationSec = awakeHours * 60 * 60;
        return sleep;
    }

    private static final class Sample implements HeartRateSample {
        private final long timestamp;
        private final int heartRate;

        private Sample(final long timestamp, final int heartRate) {
            this.timestamp = timestamp;
            this.heartRate = heartRate;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public int getHeartRate() {
            return heartRate;
        }
    }
}
