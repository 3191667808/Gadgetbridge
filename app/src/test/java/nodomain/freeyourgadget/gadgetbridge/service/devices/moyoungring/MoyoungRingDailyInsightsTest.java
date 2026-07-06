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
package nodomain.freeyourgadget.gadgetbridge.service.devices.moyoungring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.activities.workouts.entries.ActivitySummarySimpleEntry;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepStage;

/**
 * Pure-JVM unit tests for {@link MoyoungRingDailyInsights} — the DB-free builder
 * for the ring's evidence-based "Daily Health Insights" summary. Feeds a
 * synthetic day of known HR / HRV / SpO2 / sleep and asserts the derived keys
 * and values appear in the resulting {@link ActivitySummaryData}.
 */
public class MoyoungRingDailyInsightsTest {

    private static final long DAY = 1_700_000_000_000L; // fixed base epoch (ms)
    private static final long HOUR = 3_600_000L;

    private static List<HeartRateSample> buildHr() {
        final List<HeartRateSample> hr = new ArrayList<>();
        // Nocturnal HR: 20 samples at 50 bpm inside the sleep window (23:00-07:00).
        for (int i = 0; i < 20; i++) {
            final long ts = DAY + i * 20L * 60_000L; // every 20 min, within 8h window
            hr.add(hrAt(ts, 50));
        }
        // Daytime HR: elevated, after the sleep window.
        for (int i = 0; i < 10; i++) {
            final long ts = DAY + 9 * HOUR + i * 30L * 60_000L;
            hr.add(hrAt(ts, 75));
        }
        return hr;
    }

    private static HeartRateSample hrAt(final long ts, final int bpm) {
        return new HeartRateSample() {
            @Override public int getHeartRate() { return bpm; }
            @Override public long getTimestamp() { return ts; }
        };
    }

    private static List<SleepSession> buildSleep() {
        final SleepSession s = new SleepSession();
        s.startTimeMs = DAY;
        s.endTimeMs = DAY + 8 * HOUR;
        s.deepSleepSec = (int) (1.5 * 3600);
        s.deepSleepCount = 3;
        s.lightSleepSec = (int) (4.0 * 3600);
        s.lightSleepCount = 5;
        s.remSleepSec = (int) (1.5 * 3600);
        s.wakeDurationSec = 600;
        s.wakeCount = 1;
        s.stages.add(new SleepStage(SleepStage.TYPE_LIGHT, DAY, (int) (4.0 * 3600)));
        s.stages.add(new SleepStage(SleepStage.TYPE_DEEP, DAY + 4 * HOUR, (int) (1.5 * 3600)));
        s.stages.add(new SleepStage(SleepStage.TYPE_REM, DAY + 6 * HOUR, (int) (1.5 * 3600)));
        final List<SleepSession> out = new ArrayList<>();
        out.add(s);
        return out;
    }

    private static List<MoyoungRingPacket.HrvRecord> buildHrv() {
        final List<MoyoungRingPacket.HrvRecord> hrv = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            hrv.add(new MoyoungRingPacket.HrvRecord(DAY + i * HOUR, 40)); // steady 40 ms
        }
        return hrv;
    }

    private static List<MoyoungRingPacket.Spo2Record> buildSpo2() {
        // 6 nocturnal samples 20 min apart; one desaturation dip to 92.
        final int[] vals = {98, 98, 97, 92, 97, 98};
        final List<MoyoungRingPacket.Spo2Record> spo2 = new ArrayList<>();
        for (int i = 0; i < vals.length; i++) {
            spo2.add(new MoyoungRingPacket.Spo2Record(DAY + i * 20L * 60_000L, vals[i]));
        }
        return spo2;
    }

    private static List<Integer> buildStress() {
        return new ArrayList<>(java.util.Arrays.asList(25, 30, 35, 30));
    }

    private static String stringValue(final ActivitySummaryData data, final String key) {
        return (String) ((ActivitySummarySimpleEntry) data.get(key)).getValue();
    }

    @Test
    public void computesEvidenceBasedDailyInsights() {
        final ActivitySummaryData data = MoyoungRingDailyInsights.compute(
                buildHr(), buildSleep(), buildHrv(), 45.0 /* HRV baseline ms */,
                buildSpo2(), buildStress(), 30 /* age */, 52 /* resting HR baseline */);

        // Resting HR = 5th percentile of nocturnal HR (all 50 bpm).
        assertTrue(data.has(ActivitySummaryEntries.HR_USER_RESTING));
        assertEquals(50.0, data.getNumber(ActivitySummaryEntries.HR_USER_RESTING, -1).doubleValue(), 0.001);

        // HR aggregates present.
        assertTrue(data.has(ActivitySummaryEntries.HR_AVG));
        assertTrue(data.has(ActivitySummaryEntries.HR_MIN));
        assertTrue(data.has(ActivitySummaryEntries.HR_MAX));
        assertEquals(50.0, data.getNumber(ActivitySummaryEntries.HR_MIN, -1).doubleValue(), 0.001);
        assertEquals(75.0, data.getNumber(ActivitySummaryEntries.HR_MAX, -1).doubleValue(), 0.001);

        // Predicted HRmax (Tanaka: 208 - 0.7*30 = 187) + Karvonen zones.
        assertEquals(187.0, data.getNumber(ActivitySummaryEntries.HR_USER_MAX, -1).doubleValue(), 0.001);
        // reserve = 187-50 = 137; 50% band lower bound = 50 + 68.5 = 118.5 -> 119.
        assertTrue(data.has(ActivitySummaryEntries.HR_ZONE_FAT_BURN));
        assertEquals(119.0, data.getNumber(ActivitySummaryEntries.HR_ZONE_FAT_BURN, -1).doubleValue(), 0.001);
        assertTrue(data.has(ActivitySummaryEntries.HR_ZONE_MAXIMUM));

        // HRV mean RMSSD from native stream = 40 ms.
        assertTrue(data.has(ActivitySummaryEntries.HRV_RMSSD));
        assertEquals(40.0, data.getNumber(ActivitySummaryEntries.HRV_RMSSD, -1).doubleValue(), 0.001);

        // HRV baseline deviation: (40 - 45) / 45 * 100 = -11.1%.
        assertTrue(data.has(ActivitySummaryEntries.HRV_BASELINE_DEVIATION));
        assertEquals(-11.1, data.getNumber(ActivitySummaryEntries.HRV_BASELINE_DEVIATION, 0).doubleValue(), 0.05);

        // SpO2 average (rounded) and desaturation index (> 0 because of the dip).
        assertTrue(data.has(ActivitySummaryEntries.SPO2_AVG));
        assertEquals(97.0, data.getNumber(ActivitySummaryEntries.SPO2_AVG, -1).doubleValue(), 0.001);
        assertTrue(data.has(ActivitySummaryEntries.SPO2_DESATURATION_INDEX));
        assertTrue(data.getNumber(ActivitySummaryEntries.SPO2_DESATURATION_INDEX, 0).doubleValue() > 0);

        // Stress average present (non-medical estimate).
        assertTrue(data.has(ActivitySummaryEntries.STRESS_AVG));
        assertEquals(30.0, data.getNumber(ActivitySummaryEntries.STRESS_AVG, -1).doubleValue(), 0.001);

        // VO2max estimate (Uth): 15.3 * 187 / 50 = 57.2 ml/kg/min.
        assertTrue(data.has(ActivitySummaryEntries.VO2MAX_ESTIMATE));
        assertEquals(57.2, data.getNumber(ActivitySummaryEntries.VO2MAX_ESTIMATE, -1).doubleValue(), 0.1);

        // Sleep score + efficiency + readiness derived from the sleep session.
        assertTrue(data.has(ActivitySummaryEntries.SLEEP_SCORE_ESTIMATE));
        assertTrue(data.has(ActivitySummaryEntries.SLEEP_EFFICIENCY_ESTIMATE));
        assertTrue(data.has(ActivitySummaryEntries.READINESS_SCORE));
    }

    @Test
    public void skipsMetricsWithInsufficientData() {
        // Only a couple of daytime HR points, no sleep / HRV / SpO2 / stress, no age.
        final List<HeartRateSample> hr = new ArrayList<>();
        hr.add(hrAt(DAY, 70));
        hr.add(hrAt(DAY + 60_000L, 72));

        final ActivitySummaryData data = MoyoungRingDailyInsights.compute(
                hr, Collections.emptyList(), Collections.emptyList(), 0d,
                Collections.emptyList(), Collections.emptyList(), 0 /* age unknown */, 0);

        // Age-derived + sleep-derived metrics must be absent (no zeros emitted).
        assertFalse(data.has(ActivitySummaryEntries.HR_USER_MAX));
        assertFalse(data.has(ActivitySummaryEntries.HR_ZONE_FAT_BURN));
        assertFalse(data.has(ActivitySummaryEntries.VO2MAX_ESTIMATE));
        assertFalse(data.has(ActivitySummaryEntries.SLEEP_SCORE_ESTIMATE));
        assertFalse(data.has(ActivitySummaryEntries.READINESS_SCORE));
        assertFalse(data.has(ActivitySummaryEntries.SPO2_AVG));

        // But basic HR aggregates + a fallback resting HR are still present.
        assertTrue(data.has(ActivitySummaryEntries.HR_AVG));
        assertTrue(data.has(ActivitySummaryEntries.HR_USER_RESTING));
    }

    @Test
    public void metMinutesFromSlots() {
        // 30-min slots. Cadences: sedentary(300/30=10 -> ignored),
        // light(1800/30=60 -> 2.5 MET), moderate(3000/30=100 -> 3.5 MET),
        // vigorous(3900/30=130 -> 5.0 MET), and a zero slot.
        final int[] slots = {300, 1800, 3000, 3900, 0};
        final double met = MoyoungRingDailyInsights.metMinutesFromSlots(slots, 30);
        // 2.5*30 + 3.5*30 + 5.0*30 = 75 + 105 + 150 = 330 MET-min.
        assertEquals(330.0, met, 0.001);
        // Empty / null inputs -> 0.
        assertEquals(0.0, MoyoungRingDailyInsights.metMinutesFromSlots(new int[0], 30), 0.001);
        assertEquals(0.0, MoyoungRingDailyInsights.metMinutesFromSlots(null, 30), 0.001);
    }

    @Test
    public void normalCdfMatchesKnownValues() {
        assertEquals(0.5, MoyoungRingDailyInsights.normalCdf(0.0), 0.001);
        assertEquals(0.8413, MoyoungRingDailyInsights.normalCdf(1.0), 0.001);
        assertEquals(0.1587, MoyoungRingDailyInsights.normalCdf(-1.0), 0.001);
    }

    @Test
    public void stepPercentileEstimate() {
        // Age < 50 -> mean 7000, sd 3000. 4444 steps -> z = -0.852 -> ~20th percentile,
        // in the ballpark of Da Rings' "beyond 31%" wording (coarse estimate).
        final int pct = MoyoungRingDailyInsights.stepPercentile(
                4444, 30, nodomain.freeyourgadget.gadgetbridge.model.ActivityUser.GENDER_MALE);
        assertTrue("percentile should be a low-ish value for 4444 steps: " + pct,
                pct >= 10 && pct <= 40);
        // Steps equal to the mean -> ~50th percentile.
        assertEquals(50, MoyoungRingDailyInsights.stepPercentile(7000, 30, 1));
        // Result is always clamped to 1..99.
        assertTrue(MoyoungRingDailyInsights.stepPercentile(0, 30, 1) >= 1);
        assertTrue(MoyoungRingDailyInsights.stepPercentile(100000, 30, 1) <= 99);
    }

    @Test
    public void deviationInsightsHighStrainText() {
        final ActivitySummaryData data = new ActivitySummaryData();
        MoyoungRingDailyInsights.buildDeviationInsights(
                data,
                72, 68,
                68, 60,
                32, 40,
                35.1, 34.5,
                89, 96,
                300, 430,
                4000, 8000,
                50, 30,
                17, 14,
                true);

        assertEquals(8.0, data.getNumber(ActivitySummaryEntries.RHR_DEVIATION, 0).doubleValue(), 0.001);
        assertEquals(-20.0, data.getNumber(ActivitySummaryEntries.HRV_DEVIATION_PCT, 0).doubleValue(), 0.001);
        assertEquals("Resting HR notably elevated vs your 7-day average - often incomplete recovery, stress, dehydration, alcohol, or oncoming illness.",
                stringValue(data, ActivitySummaryEntries.RHR_INSIGHT));
        assertEquals("HRV meaningfully below baseline - autonomic strain (hard effort, stress, illness, alcohol). Prioritize recovery.",
                stringValue(data, ActivitySummaryEntries.HRV_INSIGHT));
        assertEquals("Wellness estimate, not medical advice. Overall: High strain signals - favor rest; see a clinician if you feel unwell.",
                stringValue(data, ActivitySummaryEntries.INSIGHTS_SUMMARY));
    }

    @Test
    public void deviationInsightsBaselineNotReady() {
        final ActivitySummaryData data = new ActivitySummaryData();
        MoyoungRingDailyInsights.buildDeviationInsights(
                data,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                false);

        assertEquals("Building your 7-day baseline - insights available after a few more days of data.",
                stringValue(data, ActivitySummaryEntries.INSIGHTS_SUMMARY));
        assertFalse(data.has(ActivitySummaryEntries.RHR_INSIGHT));
        assertFalse(data.has(ActivitySummaryEntries.HRV_DEVIATION_PCT));
    }
}
