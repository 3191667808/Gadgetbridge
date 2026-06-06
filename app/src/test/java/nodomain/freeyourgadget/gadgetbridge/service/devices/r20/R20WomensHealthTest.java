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
package nodomain.freeyourgadget.gadgetbridge.service.devices.r20;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class R20WomensHealthTest {

    @Test
    public void phase_resolves_to_menstruation_in_first_days() {
        R20WomensHealth.CyclePhase p = R20WomensHealth.estimatePhase(
                2, 28, 5, 0, 0, 0, 0);
        assertEquals(R20WomensHealth.PHASE_MENSTRUATION, p.phase);
        assertEquals(3, p.cycleDay);
    }

    @Test
    public void phase_resolves_to_follicular_mid_first_half() {
        R20WomensHealth.CyclePhase p = R20WomensHealth.estimatePhase(
                7, 28, 5, 0, 0, 0, 0);
        assertEquals(R20WomensHealth.PHASE_FOLLICULAR, p.phase);
    }

    @Test
    public void phase_resolves_to_ovulation_around_day_14() {
        R20WomensHealth.CyclePhase p = R20WomensHealth.estimatePhase(
                13, 28, 5, 0, 0, 0, 0);
        assertEquals(R20WomensHealth.PHASE_OVULATION, p.phase);
    }

    @Test
    public void phase_resolves_to_luteal_in_second_half() {
        R20WomensHealth.CyclePhase p = R20WomensHealth.estimatePhase(
                20, 28, 5, 0, 0, 0, 0);
        assertEquals(R20WomensHealth.PHASE_LUTEAL, p.phase);
    }

    @Test
    public void biometric_corroboration_boosts_luteal_confidence() {
        // Day 24 of 28, RHR +4 bpm, HRV −15 % → strong luteal signal
        R20WomensHealth.CyclePhase p = R20WomensHealth.estimatePhase(
                23, 28, 5, 70, 66, 38.0, 45.0);
        assertEquals(R20WomensHealth.PHASE_LUTEAL, p.phase);
        assertTrue("confidence=" + p.confidencePct, p.confidencePct >= 90);
    }

    @Test
    public void pms_likelihood_high_close_to_period_with_signals() {
        // Day 27 of 28, RHR +4 bpm, HRV −20 %, sleep efficiency 70 %
        int score = R20WomensHealth.pmsLikelihood(27, 28, 4, -0.20, 0.70);
        assertTrue("score=" + score, score >= 60);
    }

    @Test
    public void pms_likelihood_zero_in_follicular() {
        assertEquals(0, R20WomensHealth.pmsLikelihood(7, 28, 0, 0, 0.9));
    }

    @Test
    public void fertile_window_covers_canonical_days() {
        // For 28-day cycle: fertile window is days 10–16
        assertTrue(R20WomensHealth.inFertileWindow(11, 28));
        assertTrue(R20WomensHealth.inFertileWindow(15, 28));
        assertFalse(R20WomensHealth.inFertileWindow(5, 28));
        assertFalse(R20WomensHealth.inFertileWindow(22, 28));
    }

    @Test
    public void cycle_irregularity_zero_for_constant_cycles() {
        assertEquals(0, R20WomensHealth.cycleIrregularity(Arrays.asList(28, 28, 28, 28)));
    }

    @Test
    public void cycle_irregularity_positive_for_variable_cycles() {
        int cv = R20WomensHealth.cycleIrregularity(Arrays.asList(25, 30, 22, 33, 28, 26));
        assertTrue("cv=" + cv, cv > 5);
    }

    @Test
    public void sustained_elevation_hint_triggers_after_extended_luteal() {
        // 35 days since LPSD on a 28-day cycle, RHR +4 bpm sustained 8 days
        assertTrue(R20WomensHealth.sustainedElevationPregnancyHint(35, 28, 8, 4));
    }

    @Test
    public void sustained_elevation_does_not_trigger_too_early() {
        // Day 25 — still within normal luteal
        assertFalse(R20WomensHealth.sustainedElevationPregnancyHint(25, 28, 7, 4));
    }

    // ===== R20MedicationTrends =====

    @Test
    public void rhr_window_delta_picks_up_increase() {
        // 7-day baseline 60 bpm, recent 7 days 65 bpm → +5 Δ
        java.util.List<Integer> rhr = new java.util.ArrayList<>(
                Collections.nCopies(7, 60));
        rhr.addAll(Collections.nCopies(7, 65));
        assertEquals(5.0, R20MedicationTrends.rhrWindowDelta(rhr, 7), 0.01);
    }

    @Test
    public void hrv_window_delta_zero_when_stable() {
        java.util.List<Double> hrv = new java.util.ArrayList<>(
                Collections.nCopies(14, 40.0));
        assertEquals(0.0, R20MedicationTrends.hrvWindowDelta(hrv, 7), 0.01);
    }

    @Test
    public void baseline_shift_detected_when_sustained() {
        // 21-day baseline of 58, then 7 days at 63 → +5 sustained
        java.util.List<Integer> rhr = new java.util.ArrayList<>(
                Collections.nCopies(21, 58));
        rhr.addAll(Collections.nCopies(7, 63));
        double shift = R20MedicationTrends.detectBaselineShiftBpm(rhr, 7, 21, 5, 3);
        assertTrue("shift=" + shift, shift > 0 && shift >= 3);
    }

    @Test
    public void baseline_shift_ignored_when_not_sustained() {
        java.util.List<Integer> rhr = new java.util.ArrayList<>(
                Collections.nCopies(28, 58));
        // Single high outlier — should NOT trigger
        rhr.set(27, 70);
        double shift = R20MedicationTrends.detectBaselineShiftBpm(rhr, 7, 21, 5, 3);
        assertEquals(0.0, shift, 0.01);
    }

    @Test
    public void beta_blocker_adjusted_zone_is_lower() {
        // HR 140, predicted HRmax 190, RHR 60, beta-blocker reduces HRmax by 30
        // Unadjusted: (140-60)/(190-60) = 61 %
        // Adjusted: (140-60)/(160-60) = 80 %
        int adj = R20MedicationTrends.adjustedHrReservePercent(140, 190, 60, 30);
        assertTrue("adj=" + adj, adj >= 75 && adj <= 85);
    }
}
