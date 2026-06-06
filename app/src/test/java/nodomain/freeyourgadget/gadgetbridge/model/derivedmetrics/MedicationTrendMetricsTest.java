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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;

public class MedicationTrendMetricsTest {

    @Test
    public void rhr_window_delta_picks_up_increase() {
        java.util.List<Integer> rhr = new java.util.ArrayList<>(Collections.nCopies(7, 60));
        rhr.addAll(Collections.nCopies(7, 65));
        assertEquals(5.0, MedicationTrendMetrics.rhrWindowDelta(rhr, 7), 0.01);
    }

    @Test
    public void hrv_window_delta_zero_when_stable() {
        java.util.List<Double> hrv = new java.util.ArrayList<>(Collections.nCopies(14, 40.0));
        assertEquals(0.0, MedicationTrendMetrics.hrvWindowDelta(hrv, 7), 0.01);
    }

    @Test
    public void baseline_shift_detected_when_sustained() {
        java.util.List<Integer> rhr = new java.util.ArrayList<>(Collections.nCopies(21, 58));
        rhr.addAll(Collections.nCopies(7, 63));
        double shift = MedicationTrendMetrics.detectBaselineShiftBpm(rhr, 7, 21, 5, 3);
        assertTrue("shift=" + shift, shift > 0 && shift >= 3);
    }

    @Test
    public void baseline_shift_ignored_when_not_sustained() {
        java.util.List<Integer> rhr = new java.util.ArrayList<>(Collections.nCopies(28, 58));
        rhr.set(27, 70);
        double shift = MedicationTrendMetrics.detectBaselineShiftBpm(rhr, 7, 21, 5, 3);
        assertEquals(0.0, shift, 0.01);
    }

    @Test
    public void beta_blocker_adjusted_zone_is_lower() {
        int adj = MedicationTrendMetrics.adjustedHrReservePercent(140, 190, 60, 30);
        assertTrue("adj=" + adj, adj >= 75 && adj <= 85);
    }

    @Test
    public void rhr_window_delta_accepts_neutral_hr_and_sleep_inputs() {
        java.util.List<SleepSession> sleep = new java.util.ArrayList<>();
        java.util.List<Sample> hr = new java.util.ArrayList<>();
        for (int day = 0; day < 14; day++) {
            long start = day * 86_400_000L;
            long end = start + 8 * 60 * 60 * 1000L;
            SleepSession session = new SleepSession();
            session.startTimeMs = start;
            session.endTimeMs = end;
            sleep.add(session);
            int bpm = day < 7 ? 60 : 65;
            hr.add(new Sample(start + 1_000, bpm + 2));
            hr.add(new Sample(start + 2_000, bpm));
            hr.add(new Sample(start + 3_000, bpm + 1));
        }

        assertEquals(5.0, MedicationTrendMetrics.rhrWindowDelta(hr, sleep, 7), 0.01);
    }

    @Test
    public void nightly_rhr_orders_sessions_chronologically() {
        SleepSession later = session(86_400_000L, 86_400_000L + 10_000L);
        SleepSession earlier = session(0, 10_000L);
        java.util.List<Sample> hr = Arrays.asList(
                new Sample(1_000L, 60),
                new Sample(86_401_000L, 65));

        assertEquals(Arrays.asList(60, 65), MedicationTrendMetrics.nightlyRhr(hr, Arrays.asList(later, earlier)));
    }

    private static SleepSession session(final long startMs, final long endMs) {
        SleepSession session = new SleepSession();
        session.startTimeMs = startMs;
        session.endTimeMs = endMs;
        return session;
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
