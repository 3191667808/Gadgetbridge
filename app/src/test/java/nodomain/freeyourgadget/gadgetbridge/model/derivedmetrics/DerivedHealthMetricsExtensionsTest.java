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

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;

public class DerivedHealthMetricsExtensionsTest {

    private static class TestHr implements HeartRateSample {
        private final long ts; private final int bpm;
        TestHr(long ts, int bpm) { this.ts = ts; this.bpm = bpm; }
        @Override public long getTimestamp() { return ts; }
        @Override public int getHeartRate() { return bpm; }
    }

    @Test public void bmrMifflin_male() {
        assertEquals(1767.5, DerivedHealthMetrics.bmrMifflinStJeor(80, 178, 30, true), 0.5);
    }

    @Test public void bmrMifflin_female() {
        assertEquals(1320.25, DerivedHealthMetrics.bmrMifflinStJeor(60, 165, 30, false), 0.5);
    }

    @Test public void bmrMifflin_invalid() {
        assertEquals(-1, DerivedHealthMetrics.bmrMifflinStJeor(0, 178, 30, true), 0.001);
        assertEquals(-1, DerivedHealthMetrics.bmrMifflinStJeor(80, 0, 30, true), 0.001);
        assertEquals(-1, DerivedHealthMetrics.bmrMifflinStJeor(80, 178, 0, true), 0.001);
    }

    @Test public void leanBodyMass_male() {
        assertEquals(60.89, DerivedHealthMetrics.leanBodyMassBoer(80, 178, true), 0.05);
    }

    @Test public void leanBodyMass_female() {
        assertEquals(44.87, DerivedHealthMetrics.leanBodyMassBoer(60, 165, false), 0.05);
    }

    @Test public void activeMinutes_belowThreshold() {
        List<HeartRateSample> hr = new ArrayList<>();
        for (int i = 0; i < 30; i++) hr.add(new TestHr(i * 60_000L, 130));
        assertEquals(0, DerivedHealthMetrics.activeMinutes(hr, 190, 60));
    }

    @Test public void activeMinutes_aboveThreshold() {
        List<HeartRateSample> hr = new ArrayList<>();
        for (int i = 0; i < 20; i++) hr.add(new TestHr(i * 60_000L, 145));
        assertEquals(20, DerivedHealthMetrics.activeMinutes(hr, 190, 60));
    }

    @Test public void continuousActiveSegments_findsBlock() {
        List<HeartRateSample> hr = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 20; i++) { hr.add(new TestHr(t, 145)); t += 60_000; }
        long[] seg = DerivedHealthMetrics.continuousActiveSegments(hr, 190, 60, 10, 1);
        assertEquals(2, seg.length);
        assertEquals(0L, seg[0]);
        assertEquals(19L * 60_000, seg[1]);
    }

    @Test public void continuousActiveSegments_tooShort() {
        List<HeartRateSample> hr = new ArrayList<>();
        for (int i = 0; i < 5; i++) hr.add(new TestHr(i * 60_000L, 150));
        assertEquals(0, DerivedHealthMetrics.continuousActiveSegments(hr, 190, 60, 10, 1).length);
    }

    @Test public void floorsFromPressure_5floors() {
        double[] s = {1013.0, 1012.5, 1012.0, 1011.5, 1011.0};
        assertEquals(5, DerivedHealthMetrics.floorsFromPressureSeries(s));
    }

    @Test public void floorsFromPressure_noiseRejected() {
        double[] flat = {1013.0, 1013.02, 1012.98, 1013.01, 1012.99};
        assertEquals(0, DerivedHealthMetrics.floorsFromPressureSeries(flat));
    }

    @Test public void floorsFromPressure_descentNotCounted() {
        double[] down = {1013.0, 1013.5, 1014.0, 1014.5, 1015.0};
        assertEquals(0, DerivedHealthMetrics.floorsFromPressureSeries(down));
    }

    @Test public void calibrateStride_validPair() {
        assertEquals(0.769,
                DerivedHealthMetrics.calibrateStrideFromGps(1300, 1000.0), 0.001);
    }

    @Test public void calibrateStride_tooFewSteps() {
        assertEquals(-1, DerivedHealthMetrics.calibrateStrideFromGps(50, 100), 0.001);
    }

    @Test public void calibrateStride_outOfRange() {
        assertEquals(-1, DerivedHealthMetrics.calibrateStrideFromGps(1000, 100), 0.001);
    }

    @Test public void classifyActivity_resting() {
        assertEquals(DerivedHealthMetrics.PhoneActivity.RESTING,
                DerivedHealthMetrics.classifyActivity(9.8, 0.05, 70));
    }

    @Test public void classifyActivity_walking() {
        assertEquals(DerivedHealthMetrics.PhoneActivity.WALKING,
                DerivedHealthMetrics.classifyActivity(10.5, 0.8, 95));
    }

    @Test public void classifyActivity_running() {
        assertEquals(DerivedHealthMetrics.PhoneActivity.RUNNING,
                DerivedHealthMetrics.classifyActivity(12.0, 3.0, 155));
    }

    @Test public void classifyActivity_cycling() {
        assertEquals(DerivedHealthMetrics.PhoneActivity.CYCLING,
                DerivedHealthMetrics.classifyActivity(10.0, 0.5, 135));
    }

    @Test public void ambientCorrect_thermoneutral() {
        assertEquals(33.5,
                DerivedHealthMetrics.ambientCorrectSkinTemp(33.5, 22.0), 0.001);
    }

    @Test public void ambientCorrect_cold() {
        assertEquals(32.20,
                DerivedHealthMetrics.ambientCorrectSkinTemp(32.0, 12.0), 0.001);
    }

    @Test public void ambientCorrect_warm() {
        assertEquals(33.30,
                DerivedHealthMetrics.ambientCorrectSkinTemp(33.5, 32.0), 0.001);
    }

    @Test public void backfillSteps_normal() {
        assertEquals(500,
                DerivedHealthMetrics.backfillStepsFromPhone(100_000, 100_500, 5));
    }

    @Test public void backfillSteps_capped() {
        assertEquals(1000,
                DerivedHealthMetrics.backfillStepsFromPhone(0, 10_000, 5));
    }

    @Test public void backfillSteps_negativeDelta() {
        assertEquals(0,
                DerivedHealthMetrics.backfillStepsFromPhone(200, 100, 5));
    }
}
