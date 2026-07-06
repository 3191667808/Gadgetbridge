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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryData;
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.DerivedHealthMetrics;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepSession;

/**
 * Pure, DB-free, Android-free builder for the ring's per-day "Daily Health
 * Insights" summary. All maths delegate to the shared {@link DerivedHealthMetrics}
 * engine and its ring-native adapter {@link MoyoungRingDerivedMetrics}; this class
 * only marshals already-extracted samples into a single {@link ActivitySummaryData}
 * so it can be unit-tested without a device, DB or Bluetooth stack.
 *
 * <p>The raw per-metric streams are already charted by Gadgetbridge's generic
 * sample providers; this surface exposes the DERIVED, evidence-based metrics
 * (resting HR, Karvonen HR zones, HRV baseline deviation, SpO2 desaturation
 * index, VO2max, readiness) that would otherwise never be shown.
 *
 * <p><b>Not medical.</b> Every value here is a wellness ESTIMATE for trend
 * tracking, not a diagnostic measurement. Stress- and SpO2-screening derived
 * figures in particular must never be presented as clinical values.
 */
public final class MoyoungRingDailyInsights {

    /** SpO2 desaturation threshold (percentage points) used for the ODI proxy. */
    private static final int ODI_DROP_PCT = 3;
    private static final String GROUP_DAILY_INSIGHTS = "Daily Insights (24h vs 7-day avg)";
    private static final String WELLNESS_PREFIX = "Wellness estimate, not medical advice. ";

    private MoyoungRingDailyInsights() { }

    /**
     * Build the day's insight summary including the activity-derived MET-minutes and
     * age/gender step percentile, on top of the evidence-based HR/HRV/SpO2/sleep
     * metrics from the {@link #compute(List, List, List, double, List, List, int, int)}
     * overload.
     *
     * @param stepSlots      per-30-minute step counts for the day (index 0 = 00:00-00:30);
     *                       may be {@code null}/empty when no step histogram is available
     * @param totalDaySteps  the day's total step count (ring 2/13 total, or the slot sum)
     * @param gender         {@code ActivityUser.GENDER_*}; only used to caption the percentile
     */
    public static ActivitySummaryData compute(
            final List<? extends HeartRateSample> hr,
            final List<SleepSession> sleepSessions,
            final List<MoyoungRingPacket.HrvRecord> hrv,
            final double hrvBaselineMs,
            final List<MoyoungRingPacket.Spo2Record> spo2,
            final List<Integer> stressValues,
            final int ageYears,
            final int restingHrBaseline,
            final int[] stepSlots,
            final int totalDaySteps,
            final int gender) {
        final ActivitySummaryData data = compute(
                hr, sleepSessions, hrv, hrvBaselineMs, spo2, stressValues, ageYears, restingHrBaseline);
        addStepDerivedInsights(data, stepSlots, totalDaySteps, ageYears, gender);
        return data;
    }

    /**
     * Add literature-based 24h-vs-7d-baseline interpretation text and numeric
     * deviations. All inputs are pre-computed daily values; invalid/missing
     * values should be passed as {@link Double#NaN}.
     */
    public static void buildDeviationInsights(
            final ActivitySummaryData data,
            final double todayMeanHr,
            final double baselineMeanHr,
            final double todayRestingHr,
            final double baselineRestingHr,
            final double todayHrvMs,
            final double baselineHrvMs,
            final double todaySkinTempC,
            final double baselineSkinTempC,
            final double todaySpo2Pct,
            final double baselineSpo2Pct,
            final double todaySleepMinutes,
            final double baselineSleepMinutes,
            final double todaySteps,
            final double baselineSteps,
            final double todayStress,
            final double baselineStress,
            final double todayRespiration,
            final double baselineRespiration,
            final boolean baselineReady) {
        if (data == null) {
            return;
        }
        if (!baselineReady) {
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.INSIGHTS_SUMMARY,
                    "Building your 7-day baseline - insights available after a few more days of data.");
            return;
        }

        int strainFlags = 0;

        if (hasPositiveValues(todayRestingHr, baselineRestingHr)) {
            final double delta = todayRestingHr - baselineRestingHr;
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.RHR_DEVIATION,
                    round1(delta), ActivitySummaryEntries.UNIT_BPM, true);
            final String insight;
            if (delta >= 7d) {
                insight = "Resting HR notably elevated vs your 7-day average - often incomplete recovery, stress, dehydration, alcohol, or oncoming illness.";
                strainFlags++;
            } else if (delta >= 3d) {
                insight = "Resting HR slightly above baseline.";
            } else if (delta <= -3d) {
                insight = "Resting HR below baseline - a sign of good recovery/fitness.";
            } else {
                insight = "Resting HR in your normal range.";
            }
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.RHR_INSIGHT, insight);
        }

        if (hasPositiveValues(todayHrvMs, baselineHrvMs)) {
            final double pct = (todayHrvMs - baselineHrvMs) * 100d / baselineHrvMs;
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.HRV_DEVIATION_PCT,
                    round1(pct), ActivitySummaryEntries.UNIT_PERCENTAGE, true);
            final String insight;
            if (pct <= -10d) {
                insight = "HRV meaningfully below baseline - autonomic strain (hard effort, stress, illness, alcohol). Prioritize recovery.";
                strainFlags++;
            } else if (pct <= -5d) {
                insight = "HRV mildly reduced vs baseline.";
            } else if (pct >= 5d) {
                insight = "HRV above baseline - good parasympathetic recovery.";
            } else {
                insight = "HRV around baseline - balanced recovery.";
            }
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.HRV_INSIGHT, insight);
        }

        if (hasPositiveValues(todaySkinTempC, baselineSkinTempC)) {
            final double delta = todaySkinTempC - baselineSkinTempC;
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.SKIN_TEMP_DEVIATION,
                    round1(delta), ActivitySummaryEntries.UNIT_CELSIUS, true);
            final double abs = Math.abs(delta);
            final String insight;
            if (abs >= 0.5d) {
                insight = "Skin temperature deviates markedly from baseline - a rise can precede illness/fever or follow late meals/alcohol/luteal phase; a drop can reflect cold exposure.";
                strainFlags++;
            } else if (abs >= 0.3d) {
                insight = "Moderate skin-temperature deviation.";
            } else {
                insight = "Skin temperature stable vs baseline.";
            }
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.SKIN_TEMP_INSIGHT, insight);
        }

        if (hasPositiveValues(todaySpo2Pct, baselineSpo2Pct)) {
            final double delta = todaySpo2Pct - baselineSpo2Pct;
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.SPO2_DEVIATION,
                    round1(delta), ActivitySummaryEntries.UNIT_PERCENTAGE, true);
            final String insight;
            if (todaySpo2Pct < 90d) {
                insight = "Average blood oxygen is low; persistent <90% can indicate sleep-disordered breathing and warrants medical review.";
                strainFlags++;
            } else if (todaySpo2Pct <= 94d || delta <= -2d) {
                insight = "Blood oxygen slightly below normal/baseline - check sleep posture/environment.";
            } else {
                insight = "Blood oxygen in the normal range.";
            }
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.SPO2_INSIGHT, insight);
        }

        if (hasPositiveValues(todaySleepMinutes, baselineSleepMinutes)) {
            final double delta = todaySleepMinutes - baselineSleepMinutes;
            final double deficit = baselineSleepMinutes - todaySleepMinutes;
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.SLEEP_DURATION_DEVIATION,
                    round1(delta), ActivitySummaryEntries.UNIT_MINUTES, true);
            final String insight;
            if (deficit >= 90d) {
                insight = "Well below your recent average - sleep debt accumulating; short sleep impairs recovery, cognition, glucose control, immunity.";
                strainFlags++;
            } else if (deficit >= 30d) {
                insight = "Slightly less sleep than usual.";
            } else if (delta >= 30d) {
                insight = "More sleep than usual - good for recovery.";
            } else {
                insight = "Sleep duration consistent with your average.";
            }
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.SLEEP_DURATION_INSIGHT, insight);
        }

        if (hasBaseline(todaySteps, baselineSteps)) {
            final double pctOfBaseline = todaySteps * 100d / baselineSteps;
            final double deviationPct = pctOfBaseline - 100d;
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.STEPS_DEVIATION_PCT,
                    round1(deviationPct), ActivitySummaryEntries.UNIT_PERCENTAGE, true);
            final String insight;
            if (pctOfBaseline <= 50d) {
                insight = "Activity well below your recent average.";
            } else if (pctOfBaseline < 85d) {
                insight = "Somewhat less active than usual.";
            } else if (pctOfBaseline >= 115d) {
                insight = "More active than usual.";
            } else {
                insight = "Activity in line with your average.";
            }
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.STEPS_INSIGHT, insight);
        }

        if (hasPositiveValues(todayStress, baselineStress)) {
            final double delta = todayStress - baselineStress;
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.STRESS_DEVIATION,
                    round1(delta), ActivitySummaryEntries.UNIT_NONE, true);
            final String insight = delta >= 15d
                    ? "Stress load elevated vs baseline."
                    : "Stress in your usual range.";
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.STRESS_INSIGHT, insight);
        }

        if (hasPositiveValues(todayRespiration, baselineRespiration)) {
            final double delta = todayRespiration - baselineRespiration;
            data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.RESP_DEVIATION,
                    round1(delta), ActivitySummaryEntries.UNIT_BREATHS_PER_MIN, true);
            if (delta >= 2d) {
                data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.RESP_INSIGHT,
                        "Respiration rate elevated vs baseline - can accompany illness, stress, or poor sleep.");
            }
        }

        final String overall;
        if (strainFlags == 0) {
            overall = "Overall: Recovered/Balanced.";
        } else if (strainFlags <= 2) {
            overall = "Overall: Mild strain - ease up and prioritize sleep/hydration.";
        } else {
            overall = "Overall: High strain signals - favor rest; see a clinician if you feel unwell.";
        }
        data.add(GROUP_DAILY_INSIGHTS, ActivitySummaryEntries.INSIGHTS_SUMMARY, WELLNESS_PREFIX + overall);
    }

    /**
     * Build the day's insight summary from pre-filtered samples. Callers are
     * expected to have already dropped implausible/sentinel values (via the
     * {@code MoyoungRingConstants.plausible*} clamps). Any metric with
     * insufficient data is simply omitted — {@link ActivitySummaryData#add}
     * already drops zero/NaN values, and we additionally guard the {@code -1}
     * "not enough data" sentinels returned by the derived-metric engine.
     *
     * @param hr               day HR samples (plausibility-filtered), time-ordered
     * @param sleepSessions    neutral sleep sessions overlapping the night, may be empty
     * @param hrv              ring-native HRV records (ms), may be empty
     * @param hrvBaselineMs    trailing-baseline mean HRV (ms); {@code <=0} if unknown
     * @param spo2             ring-native nocturnal SpO2 records, may be empty
     * @param stressValues     stress scores (0-100), may be empty
     * @param ageYears         user age in years (for Tanaka HRmax); {@code <=0} disables age-derived metrics
     * @param restingHrBaseline trailing-baseline resting HR (bpm); {@code <=0} if unknown
     * @return a populated {@link ActivitySummaryData}; empty if nothing could be derived
     */
    public static ActivitySummaryData compute(
            final List<? extends HeartRateSample> hr,
            final List<SleepSession> sleepSessions,
            final List<MoyoungRingPacket.HrvRecord> hrv,
            final double hrvBaselineMs,
            final List<MoyoungRingPacket.Spo2Record> spo2,
            final List<Integer> stressValues,
            final int ageYears,
            final int restingHrBaseline) {

        final ActivitySummaryData data = new ActivitySummaryData();

        // ---- Heart rate aggregates -----------------------------------------
        int avgHr = -1, minHr = -1, maxHrObserved = -1;
        if (hr != null && !hr.isEmpty()) {
            long sum = 0;
            minHr = Integer.MAX_VALUE;
            maxHrObserved = Integer.MIN_VALUE;
            for (final HeartRateSample s : hr) {
                final int b = s.getHeartRate();
                sum += b;
                if (b < minHr) minHr = b;
                if (b > maxHrObserved) maxHrObserved = b;
            }
            avgHr = (int) Math.round((double) sum / hr.size());
            addIfPositive(data, ActivitySummaryEntries.HR_AVG, avgHr, ActivitySummaryEntries.UNIT_BPM);
            addIfPositive(data, ActivitySummaryEntries.HR_MIN, minHr, ActivitySummaryEntries.UNIT_BPM);
            addIfPositive(data, ActivitySummaryEntries.HR_MAX, maxHrObserved, ActivitySummaryEntries.UNIT_BPM);
        }

        // ---- Resting HR (5th-percentile of nocturnal HR, sleep-gated) -------
        int restingHr = -1;
        if (hr != null && !hr.isEmpty()) {
            if (sleepSessions != null && !sleepSessions.isEmpty()) {
                restingHr = DerivedHealthMetrics.restingHrFromSleep(hr, sleepSessions);
            }
            if (restingHr <= 0) {
                // Fallback aggregate: 5th percentile of the whole day's HR.
                restingHr = percentileHr(hr, 0.05);
            }
            addIfPositive(data, ActivitySummaryEntries.HR_USER_RESTING, restingHr, ActivitySummaryEntries.UNIT_BPM);
        }

        // ---- Predicted HRmax + Karvonen HR zones ---------------------------
        int hrMax = -1;
        if (ageYears > 0) {
            hrMax = DerivedHealthMetrics.tanakaHrMax(ageYears);
            addIfPositive(data, ActivitySummaryEntries.HR_USER_MAX, hrMax, ActivitySummaryEntries.UNIT_BPM);
        }
        if (hrMax > 0 && restingHr > 0) {
            final int[] zones = DerivedHealthMetrics.heartRateZones(hrMax, restingHr);
            // zones[] = lower bound (bpm) of 50/60/70/80/90% HR-reserve bands.
            if (zones != null && zones.length == 5) {
                addIfPositive(data, ActivitySummaryEntries.HR_ZONE_FAT_BURN, zones[0], ActivitySummaryEntries.UNIT_BPM);
                addIfPositive(data, ActivitySummaryEntries.HR_ZONE_AEROBIC, zones[1], ActivitySummaryEntries.UNIT_BPM);
                addIfPositive(data, ActivitySummaryEntries.HR_ZONE_ANAEROBIC, zones[2], ActivitySummaryEntries.UNIT_BPM);
                addIfPositive(data, ActivitySummaryEntries.HR_ZONE_THRESHOLD, zones[3], ActivitySummaryEntries.UNIT_BPM);
                addIfPositive(data, ActivitySummaryEntries.HR_ZONE_MAXIMUM, zones[4], ActivitySummaryEntries.UNIT_BPM);
            }
        }

        // ---- HRV: mean RMSSD (native stream, HR-proxy fallback) + baseline --
        double hrvMean = MoyoungRingDerivedMetrics.meanHrvMs(hrv);
        if (hrvMean <= 0d) {
            final double proxy = DerivedHealthMetrics.hrvProxyRmssd(hr);
            if (proxy > 0d) hrvMean = proxy;
        }
        if (hrvMean > 0d) {
            data.add(ActivitySummaryEntries.HRV_RMSSD, round1(hrvMean), ActivitySummaryEntries.UNIT_MILLISECONDS);
        }
        final double hrvSdnn = DerivedHealthMetrics.hrvProxySdnn(hr);
        if (hrvSdnn > 0d) {
            data.add(ActivitySummaryEntries.HRV_SDRR, round1(hrvSdnn), ActivitySummaryEntries.UNIT_MILLISECONDS);
        }
        if (hrvMean > 0d && hrvBaselineMs > 0d) {
            final double devPct = MoyoungRingDerivedMetrics.hrvBaselineDeviationPct(hrvMean, hrvBaselineMs);
            // Deviation is meaningful when negative (below baseline), so force display.
            data.add(ActivitySummaryEntries.HRV_BASELINE_DEVIATION, round1(devPct),
                    ActivitySummaryEntries.UNIT_PERCENTAGE, true);
        }

        // ---- SpO2 average + desaturation index (OSA screening proxy) -------
        if (spo2 != null && !spo2.isEmpty()) {
            long sum = 0; int n = 0;
            for (final MoyoungRingPacket.Spo2Record r : spo2) {
                if (r.spo2 >= 70 && r.spo2 <= 100) { sum += r.spo2; n++; }
            }
            if (n > 0) {
                addIfPositive(data, ActivitySummaryEntries.SPO2_AVG,
                        (int) Math.round((double) sum / n), ActivitySummaryEntries.UNIT_PERCENTAGE);
            }
            final double odi = MoyoungRingDerivedMetrics.spo2DesaturationIndex(spo2, ODI_DROP_PCT);
            if (odi > 0d) {
                data.add(ActivitySummaryEntries.SPO2_DESATURATION_INDEX, round1(odi),
                        ActivitySummaryEntries.UNIT_NONE);
            }
        }

        // ---- Stress average (non-medical estimate) -------------------------
        if (stressValues != null && !stressValues.isEmpty()) {
            long sum = 0; int n = 0;
            for (final Integer v : stressValues) {
                if (v != null && v >= 1 && v <= 100) { sum += v; n++; }
            }
            if (n > 0) {
                addIfPositive(data, ActivitySummaryEntries.STRESS_AVG,
                        (int) Math.round((double) sum / n), ActivitySummaryEntries.UNIT_NONE);
            }
        }

        // ---- Respiratory rate proxy ----------------------------------------
        final int rr = DerivedHealthMetrics.respiratoryRateProxy(hr);
        addIfPositive(data, ActivitySummaryEntries.RESPIRATION_AVG, rr,
                ActivitySummaryEntries.UNIT_BREATHS_PER_MIN);

        // ---- VO2max estimate -----------------------------------------------
        double vo2max = -1d;
        if (hrMax > 0 && restingHr > 0) {
            vo2max = DerivedHealthMetrics.vo2MaxUth(hrMax, restingHr);
            if (vo2max > 0d) {
                data.add(ActivitySummaryEntries.VO2MAX_ESTIMATE, round1(vo2max),
                        ActivitySummaryEntries.UNIT_ML_KG_MIN);
            }
        }

        // ---- Sleep score + efficiency --------------------------------------
        int sleepScore = -1;
        if (sleepSessions != null && !sleepSessions.isEmpty()) {
            final SleepSession primary = longestSession(sleepSessions);
            sleepScore = DerivedHealthMetrics.sleepScore(primary);
            addIfPositive(data, ActivitySummaryEntries.SLEEP_SCORE_ESTIMATE, sleepScore,
                    ActivitySummaryEntries.UNIT_NONE);
            final long totalSec = (primary.endTimeMs - primary.startTimeMs) / 1000L;
            final int sleepSec = primary.deepSleepSec + primary.lightSleepSec + primary.remSleepSec;
            if (totalSec > 0 && sleepSec > 0) {
                final double effPct = Math.min(100d, sleepSec * 100d / totalSec);
                addIfPositive(data, ActivitySummaryEntries.SLEEP_EFFICIENCY_ESTIMATE,
                        (int) Math.round(effPct), ActivitySummaryEntries.UNIT_PERCENTAGE);
            }
        }

        // ---- Readiness score -----------------------------------------------
        if (sleepScore >= 0) {
            final int readiness = DerivedHealthMetrics.readinessScore(
                    sleepScore, restingHr, restingHrBaseline, hrvMean, hrvBaselineMs);
            addIfPositive(data, ActivitySummaryEntries.READINESS_SCORE, readiness,
                    ActivitySummaryEntries.UNIT_NONE);
        }

        return data;
    }

    // ------------------------------------------------------------------ utils

    /**
     * Add the activity-derived insights to the summary:
     * <ul>
     *   <li><b>MET minutes</b> — estimated from the step histogram (see
     *       {@link #metMinutesFromSlots}). Stored under
     *       {@link ActivitySummaryEntries#MET_MINUTES}. A healthy adult target is
     *       roughly 600-1000 MET-min/week.</li>
     *   <li><b>Age/gender step percentile</b> — where today's total steps fall on a
     *       typical adult daily-step distribution (see {@link #stepPercentile}).
     *       Stored under {@link ActivitySummaryEntries#STEPS_AGE_PERCENTILE}.</li>
     * </ul>
     * Both are ESTIMATES for trend tracking, not medical measurements.
     */
    static void addStepDerivedInsights(final ActivitySummaryData data, final int[] stepSlots,
                                       final int totalDaySteps, final int ageYears, final int gender) {
        if (stepSlots != null && stepSlots.length > 0) {
            final double metMin = metMinutesFromSlots(stepSlots, 30);
            if (metMin > 0d) {
                data.add(ActivitySummaryEntries.MET_MINUTES, round1(metMin), ActivitySummaryEntries.UNIT_NONE);
            }
        }
        if (totalDaySteps > 0) {
            final int pct = stepPercentile(totalDaySteps, ageYears, gender);
            data.add(ActivitySummaryEntries.STEPS_AGE_PERCENTILE, pct, ActivitySummaryEntries.UNIT_PERCENTAGE);
        }
    }

    /**
     * Estimate MET-minutes (metabolic-equivalent minutes) from a per-slot step
     * histogram. For each slot, cadence = slotSteps / slotMinutes (steps/min) maps
     * to a MET value:
     * <pre>
     *   &lt; 20 spm  -&gt; sedentary (ignored, no active minutes credited)
     *   20-79 spm  -&gt; 2.5 MET (light)
     *   80-119 spm -&gt; 3.5 MET (moderate)
     *   &gt;=120 spm -&gt; 5.0 MET (vigorous)
     * </pre>
     * A slot only contributes its minutes when cadence indicates real movement
     * (&gt;= 20 spm). MET-min += MET * slotMinutes, summed over the day.
     */
    static double metMinutesFromSlots(final int[] slotSteps, final int slotMinutes) {
        if (slotSteps == null || slotMinutes <= 0) return 0d;
        double metMin = 0d;
        for (final int steps : slotSteps) {
            if (steps <= 0) continue;
            final double cadence = (double) steps / slotMinutes; // steps/min
            final double met;
            if (cadence < 20d) {
                continue; // sedentary — no active minutes
            } else if (cadence < 80d) {
                met = 2.5d;  // light
            } else if (cadence < 120d) {
                met = 3.5d;  // moderate
            } else {
                met = 5.0d;  // vigorous
            }
            metMin += met * slotMinutes;
        }
        return metMin;
    }

    /**
     * ESTIMATE the percentile of a day's total steps against a typical adult
     * daily-step distribution, assumed Normal(mean, sd) with an age-dependent mean
     * (7000 for &lt;50, 6000 for 50-65, 5000 for &gt;65) and sd = 3000. Returns a
     * whole percentage clamped to 1..99. The gender argument is accepted for future
     * refinement but not currently differentiated (the population model is coarse).
     */
    static int stepPercentile(final int steps, final int ageYears, final int gender) {
        final double mean;
        if (ageYears <= 0 || ageYears < 50) {
            mean = 7000d;
        } else if (ageYears <= 65) {
            mean = 6000d;
        } else {
            mean = 5000d;
        }
        final double sd = 3000d;
        final double z = (steps - mean) / sd;
        final double pct = normalCdf(z) * 100d;
        return (int) Math.max(1, Math.min(99, Math.round(pct)));
    }

    /** Standard normal CDF Phi(x) = 0.5 * (1 + erf(x / sqrt(2))). */
    static double normalCdf(final double x) {
        return 0.5d * (1d + erf(x / Math.sqrt(2d)));
    }

    /**
     * Error function erf(x) via Abramowitz &amp; Stegun 7.1.26 (max abs error ~1.5e-7),
     * sufficient for a coarse percentile estimate.
     */
    static double erf(final double x) {
        final double t = 1d / (1d + 0.3275911d * Math.abs(x));
        final double y = 1d - (((((1.061405429d * t - 1.453152027d) * t) + 1.421413741d) * t
                - 0.284496736d) * t + 0.254829592d) * t * Math.exp(-x * x);
        return x >= 0d ? y : -y;
    }

    /** Add only when value is a plausible positive number (drops the -1 sentinels). */
    private static void addIfPositive(final ActivitySummaryData data, final String key,
                                      final int value, final String unit) {
        if (value > 0) data.add(key, value, unit);
    }

    /** 5th/percentile of HR samples (bpm); -1 when empty. */
    private static int percentileHr(final List<? extends HeartRateSample> hr, final double p) {
        if (hr == null || hr.isEmpty()) return -1;
        final List<Integer> bpms = new ArrayList<>(hr.size());
        for (final HeartRateSample s : hr) bpms.add(s.getHeartRate());
        Collections.sort(bpms);
        final int idx = Math.max(0, Math.min(bpms.size() - 1, (int) Math.floor(bpms.size() * p)));
        return bpms.get(idx);
    }

    /** The longest sleep session (by wall-clock span) — the night we score. */
    private static SleepSession longestSession(final List<SleepSession> sessions) {
        SleepSession best = sessions.get(0);
        long bestSpan = best.endTimeMs - best.startTimeMs;
        for (final SleepSession s : sessions) {
            final long span = s.endTimeMs - s.startTimeMs;
            if (span > bestSpan) { bestSpan = span; best = s; }
        }
        return best;
    }

    private static double round1(final double v) {
        return Math.round(v * 10d) / 10d;
    }

    private static boolean hasValues(final double today, final double baseline) {
        return Double.isFinite(today) && Double.isFinite(baseline);
    }

    private static boolean hasBaseline(final double today, final double baseline) {
        return hasValues(today, baseline) && baseline > 0d;
    }

    private static boolean hasPositiveValues(final double today, final double baseline) {
        return hasBaseline(today, baseline) && today > 0d;
    }
}
