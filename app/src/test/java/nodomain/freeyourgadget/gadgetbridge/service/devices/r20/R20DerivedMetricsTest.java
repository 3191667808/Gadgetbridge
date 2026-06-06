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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet.HrRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet.SleepSession;

public class R20DerivedMetricsTest {

    @Test
    public void tanaka_hr_max_at_age_30() {
        assertEquals(187, R20DerivedMetrics.tanakaHrMax(30));
    }

    @Test
    public void resting_hr_picks_low_percentile_during_sleep_window() {
        long t0 = 1_700_000_000_000L;
        List<HrRecord> hr = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hr.add(new HrRecord(t0 + i * 60_000L, 50 + i));
        }
        SleepSession s = new SleepSession();
        s.startTimeMs = t0;
        s.endTimeMs   = t0 + 100 * 60_000L;
        assertEquals(55, R20DerivedMetrics.restingHrFromSleep(hr, Arrays.asList(s)));
    }

    @Test
    public void karvonen_zones_make_sense() {
        int[] z = R20DerivedMetrics.heartRateZones(190, 60);
        assertEquals(125, z[0]);
        assertEquals(151, z[2]);
        assertEquals(177, z[4]);
    }

    @Test
    public void active_calories_keytel_male_moderate_session() {
        long t0 = 1_700_000_000_000L;
        List<HrRecord> hr = new ArrayList<>();
        for (int i = 0; i <= 30; i++) hr.add(new HrRecord(t0 + i * 60_000L, 130));
        double kcal = R20DerivedMetrics.activeCalories(hr, 80, 37, true);
        assertTrue("got " + kcal, kcal > 250 && kcal < 400);
    }

    @Test
    public void distance_from_steps_and_height() {
        double d = R20DerivedMetrics.distanceMetres(10000, 175, true);
        assertTrue("got " + d, Math.abs(d - 7262.5) < 1);
    }

    @Test
    public void sleep_score_perfect_night() {
        SleepSession s = new SleepSession();
        s.startTimeMs = 0;
        s.endTimeMs   = 8 * 3600 * 1000L;
        s.deepSleepSec  = (int) (8 * 3600 * 0.18);
        s.remSleepSec   = (int) (8 * 3600 * 0.22);
        s.lightSleepSec = (int) (8 * 3600 - s.deepSleepSec - s.remSleepSec);
        s.wakeCount     = 1;
        s.wakeDurationSec = 60;
        int score = R20DerivedMetrics.sleepScore(s);
        assertTrue("score=" + score, score >= 90);
    }

    @Test
    public void sleep_debt_against_target() {
        SleepSession s1 = new SleepSession(); s1.deepSleepSec = 3600; s1.lightSleepSec = 3 * 3600;
        SleepSession s2 = new SleepSession(); s2.deepSleepSec = 3600; s2.lightSleepSec = 4 * 3600;
        assertEquals(7.0, R20DerivedMetrics.sleepDebtHours(Arrays.asList(s1, s2), 8.0), 0.01);
    }

    @Test
    public void hrv_proxy_zero_for_flat_hr() {
        long t0 = 1_700_000_000_000L;
        List<HrRecord> hr = new ArrayList<>();
        for (int i = 0; i < 10; i++) hr.add(new HrRecord(t0 + i * 10_000L, 70));
        assertEquals(0.0, R20DerivedMetrics.hrvProxyRmssd(hr), 0.01);
    }

    @Test
    public void hrv_proxy_nonzero_for_varying_hr() {
        long t0 = 1_700_000_000_000L;
        int[] bpms = {62, 70, 65, 72, 60, 68, 64, 71, 63, 69};
        List<HrRecord> hr = new ArrayList<>();
        for (int i = 0; i < bpms.length; i++) hr.add(new HrRecord(t0 + i * 10_000L, bpms[i]));
        double rmssd = R20DerivedMetrics.hrvProxyRmssd(hr);
        assertTrue("rmssd=" + rmssd, rmssd > 50 && rmssd < 250);
    }

    @Test
    public void readiness_baseline_neutral_returns_around_65() {
        assertEquals(65, R20DerivedMetrics.readinessScore(80, -1, -1, -1, -1));
    }

    @Test
    public void vo2max_uth_formula() {
        // HRmax 190 / RHR 50 -> 15.3 * 3.8 = 58.14
        assertEquals(58.1, R20DerivedMetrics.vo2MaxUth(190, 50), 0.1);
        assertEquals(-1.0, R20DerivedMetrics.vo2MaxUth(0, 50), 0.001);
        assertEquals(-1.0, R20DerivedMetrics.vo2MaxUth(50, 50), 0.001);
    }

    @Test
    public void cardiovascular_age_delta_fit_individual() {
        // VO2max 50 ml/kg/min, chronological age 40
        // predicted age = (60 - 50) / 0.55 = 18.2; delta = 18.2 - 40 = -21.8
        assertTrue(R20DerivedMetrics.cardiovascularAgeDelta(50, 40) < -15);
    }

    @Test
    public void heart_rate_recovery_drops_after_peak() {
        long t0 = 1_700_000_000_000L;
        java.util.List<R20Packet.HrRecord> hr = new java.util.ArrayList<>();
        // Climb to 170, then recover
        hr.add(new R20Packet.HrRecord(t0,             100));
        hr.add(new R20Packet.HrRecord(t0 + 10_000,    140));
        hr.add(new R20Packet.HrRecord(t0 + 20_000,    170));
        hr.add(new R20Packet.HrRecord(t0 + 50_000,    155));
        hr.add(new R20Packet.HrRecord(t0 + 80_000,    140));
        // Peak at index 2 (170 bpm), 60s later is t0+80_000 -> 140 bpm. HRR60 = 30.
        assertEquals(30, R20DerivedMetrics.heartRateRecovery(hr, 60));
    }

    @Test
    public void cardiac_strain_zero_for_resting_hr() {
        long t0 = 1_700_000_000_000L;
        java.util.List<R20Packet.HrRecord> hr = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) hr.add(new R20Packet.HrRecord(t0 + i * 60_000L, 55));
        // 55 bpm is below the lowest zone threshold (Z1) at HRmax=190 RHR=60 -> 0 strain
        assertEquals(0, R20DerivedMetrics.cardiacStrainEdwards(hr, 190, 60));
    }

    @Test
    public void cardiac_strain_grows_with_intensity() {
        long t0 = 1_700_000_000_000L;
        java.util.List<R20Packet.HrRecord> moderate = new java.util.ArrayList<>();
        java.util.List<R20Packet.HrRecord> hard = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) moderate.add(new R20Packet.HrRecord(t0 + i * 60_000L, 130));
        for (int i = 0; i < 30; i++) hard    .add(new R20Packet.HrRecord(t0 + i * 60_000L, 170));
        int mod  = R20DerivedMetrics.cardiacStrainEdwards(moderate, 190, 60);
        int hardS = R20DerivedMetrics.cardiacStrainEdwards(hard,     190, 60);
        assertTrue("mod=" + mod + " hard=" + hardS, hardS > mod);
    }

    @Test
    public void sdnn_proxy_matches_known_pattern() {
        long t0 = 1_700_000_000_000L;
        int[] bpms = {65, 67, 64, 66, 65, 68, 63, 67, 66, 64};
        java.util.List<R20Packet.HrRecord> hr = new java.util.ArrayList<>();
        for (int i = 0; i < bpms.length; i++) hr.add(new R20Packet.HrRecord(t0 + i * 10_000L, bpms[i]));
        double sdnn = R20DerivedMetrics.hrvProxySdnn(hr);
        assertTrue("sdnn=" + sdnn, sdnn > 0 && sdnn < 200);
    }

    @Test
    public void respiratory_rate_proxy_in_normal_range() {
        // Synthesise an HR series oscillating ~16 cycles per minute (16 br/min)
        long t0 = 1_700_000_000_000L;
        java.util.List<R20Packet.HrRecord> hr = new java.util.ArrayList<>();
        for (int i = 0; i < 240; i++) {
            double t = i * 0.5; // 0.5 s per sample, 120 s window
            int bpm = (int) Math.round(60 + 5 * Math.sin(2 * Math.PI * 16/60.0 * t));
            hr.add(new R20Packet.HrRecord(t0 + i * 500L, bpm));
        }
        int rr = R20DerivedMetrics.respiratoryRateProxy(hr);
        assertTrue("rr=" + rr, rr >= 10 && rr <= 22);
    }

    @Test
    public void sleep_regularity_index_high_for_identical_nights() {
        R20Packet.SleepSession n1 = new R20Packet.SleepSession();
        n1.startTimeMs = 1_700_000_000_000L;
        R20Packet.SleepStage s1 = new R20Packet.SleepStage(241, n1.startTimeMs, 8 * 3600);
        n1.stages.add(s1);
        R20Packet.SleepSession n2 = new R20Packet.SleepSession();
        n2.startTimeMs = n1.startTimeMs + 86_400_000L; // exactly 24 h later
        R20Packet.SleepStage s2 = new R20Packet.SleepStage(241, n2.startTimeMs, 8 * 3600);
        n2.stages.add(s2);
        int sri = R20DerivedMetrics.sleepRegularityIndex(java.util.Arrays.asList(n1, n2));
        assertTrue("sri=" + sri, sri > 80);
    }

    @Test
    public void body_battery_drains_with_strain_and_stress() {
        int rested = R20DerivedMetrics.bodyBattery(90, 0, 50);
        int spent  = R20DerivedMetrics.bodyBattery(90, 400, 90);
        assertTrue("rested=" + rested, rested >= 85);
        assertTrue("spent=" + spent + " rested=" + rested, spent < rested);
    }

    @Test
    public void energy_score_returns_in_range() {
        int e = R20DerivedMetrics.energyScore(75, -1, -1, -1, -1, 40, 200, 250);
        assertTrue("energy=" + e, e >= 30 && e <= 90);
    }
}
