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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;

/**
 * Device-agnostic software-derived health metrics computed from any
 * combination of:
 * <ul>
 *   <li>a list of {@link HeartRateSample} (uses {@code getTimestamp()} ms +
 *       {@code getHeartRate()} bpm — works with every device DAO)</li>
 *   <li>a list of neutral {@link SleepSession} + {@link SleepStage} values
 *       (drivers write a thin adapter mapping their raw data in)</li>
 *   <li>scalar age / weight / sex inputs from the user profile</li>
 * </ul>
 *
 * <p>All methods are pure functions, no side-effects, no DB access — the
 * caller decides what to persist (typically into one of the existing
 * generic sample DAOs: {@code GenericHrvValueSample}, {@code GenericStressSample},
 * {@code GenericVo2MaxSample}, etc.).
 *
 * <p>Per-metric opt-in design: every approximation here documents its
 * confidence band.  Devices with native sensor data (chest-strap RR for
 * true HRV, dedicated thermistor for skin temperature, dedicated PPG-DSP
 * respiratory rate) should prefer their hardware value over the
 * approximations in this module.  This class never pretends to replace
 * direct measurement — it gives lower-end devices a graceful fallback so
 * the dashboard isn't empty.
 *
 * <p>Medical literature references for each metric are inline in the
 * Javadoc of the corresponding method.
 */
public final class DerivedHealthMetrics {

    private DerivedHealthMetrics() { }

    // ====================================================================
    //  TIER 1 — direct calculations (high confidence)
    // ====================================================================

    /**
     * Resting heart rate (bpm) — 5th-percentile of HR samples taken
     * during the supplied sleep windows.  Robust against the occasional
     * motion-artifact dip.
     *
     * <p>Method aligned with Quer et al, "Inter-individual variation in
     * objective measure of reactogenicity following COVID-19 vaccination",
     * <i>NPJ Digit. Med.</i> 4:155 (2021).
     *
     * @return resting HR, or -1 if there are no HR samples in any sleep window
     */
    public static int restingHrFromSleep(List<? extends HeartRateSample> hr,
                                         List<SleepSession> sleep) {
        if (hr == null || hr.isEmpty() || sleep == null || sleep.isEmpty()) return -1;
        List<Integer> bpms = new ArrayList<>();
        for (HeartRateSample r : hr) {
            long ts = r.getTimestamp();
            for (SleepSession s : sleep) {
                if (ts >= s.startTimeMs && ts <= s.endTimeMs) {
                    bpms.add(r.getHeartRate());
                    break;
                }
            }
        }
        if (bpms.isEmpty()) return -1;
        Collections.sort(bpms);
        int idx = Math.max(0, (int) Math.floor(bpms.size() * 0.05));
        return bpms.get(idx);
    }

    /**
     * Resting HR computed using ONLY deep-sleep epochs (Plews et al,
     * <i>Int. J. Sports Physiol. Perform.</i> 9(6):1026-1032 (2014)) —
     * higher day-to-day repeatability than whole-night averages.  Falls
     * back to {@link #restingHrFromSleep} if no deep-sleep windows exist.
     */
    public static int restingHrFromDeepSleep(List<? extends HeartRateSample> hr,
                                             List<SleepSession> sleep) {
        if (hr == null || hr.isEmpty() || sleep == null || sleep.isEmpty()) return -1;
        List<Integer> bpms = new ArrayList<>();
        for (HeartRateSample r : hr) {
            long ts = r.getTimestamp();
            for (SleepSession s : sleep) {
                for (SleepStage st : s.stages) {
                    if (st.type != SleepStage.TYPE_DEEP) continue;
                    long end = st.startTimeMs + st.durationSec * 1000L;
                    if (ts >= st.startTimeMs && ts <= end) {
                        bpms.add(r.getHeartRate());
                    }
                }
            }
        }
        if (bpms.size() < 5) return restingHrFromSleep(hr, sleep);
        Collections.sort(bpms);
        int idx = Math.max(0, (int) Math.floor(bpms.size() * 0.05));
        return bpms.get(idx);
    }

    /** Observed peak HR in the supplied samples (bpm). */
    public static int maxHr(List<? extends HeartRateSample> hr) {
        if (hr == null || hr.isEmpty()) return -1;
        int max = 0;
        for (HeartRateSample r : hr) if (r.getHeartRate() > max) max = r.getHeartRate();
        return max;
    }

    /**
     * Predicted maximum heart rate. Tanaka, Monahan &amp; Seals,
     * "Age-Predicted Maximal Heart Rate Revisited",
     * <i>J. Am. Coll. Cardiol.</i> 37(1):153-156 (2001).
     */
    public static int tanakaHrMax(int ageYears) {
        return (int) Math.round(208 - 0.7 * ageYears);
    }

    /**
     * Karvonen reserve-method HR-zone thresholds. Karvonen et al,
     * <i>Ann. Med. Exp. Biol. Fenn.</i> 35(3):307-315 (1957).
     */
    public static int[] heartRateZones(int hrMax, int restingHr) {
        double reserve = Math.max(0, hrMax - restingHr);
        return new int[]{
                (int) Math.round(restingHr + reserve * 0.50),
                (int) Math.round(restingHr + reserve * 0.60),
                (int) Math.round(restingHr + reserve * 0.70),
                (int) Math.round(restingHr + reserve * 0.80),
                (int) Math.round(restingHr + reserve * 0.90),
        };
    }

    /**
     * Active calories burned (kcal) — Keytel et al,
     * <i>J. Sports Sci.</i> 23(3):289-297 (2005), gender-specific HR
     * regression.
     */
    public static double activeCalories(List<? extends HeartRateSample> hr,
                                        int weightKg, int ageYears, boolean isMale) {
        if (hr == null || hr.size() < 2) return 0;
        double total = 0;
        for (int i = 1; i < hr.size(); i++) {
            double bpm = (hr.get(i).getHeartRate() + hr.get(i - 1).getHeartRate()) / 2.0;
            double dtMin = (hr.get(i).getTimestamp() - hr.get(i - 1).getTimestamp()) / 60_000.0;
            if (dtMin <= 0 || dtMin > 5) continue;
            double kcalPerMin = isMale
                    ? (-55.0969 + 0.6309 * bpm + 0.1988 * weightKg + 0.2017 * ageYears) / 4.184
                    : (-20.4022 + 0.4472 * bpm - 0.1263 * weightKg + 0.0740 * ageYears) / 4.184;
            total += Math.max(0, kcalPerMin * dtMin);
        }
        return total;
    }

    /**
     * Stride-length distance (metres). Bohannon,
     * <i>Age Ageing</i> 26(1):15-19 (1997).
     */
    public static double distanceMetres(int steps, int heightCm, boolean isMale) {
        if (steps <= 0 || heightCm <= 0) return 0;
        double stride = (isMale ? 0.415 : 0.413) * (heightCm / 100.0);
        return steps * stride;
    }

    /**
     * Sleep score (0–100). Composite of duration / efficiency / REM% /
     * deep% / awakening count — Watson et al, <i>SLEEP</i> 38(6):843-844
     * (2015); Ohayon et al, <i>Sleep Health</i> 3(1):6-19 (2017).
     */
    public static int sleepScore(SleepSession s) {
        if (s == null) return -1;
        long totalSec = (s.endTimeMs - s.startTimeMs) / 1000;
        if (totalSec <= 0) return -1;
        int sleepSec = s.deepSleepSec + s.lightSleepSec + s.remSleepSec;
        double efficiency = sleepSec / (double) totalSec;
        double durationH = sleepSec / 3600.0;

        double dur = Math.max(0, Math.min(1.0,
                durationH >= 7 && durationH <= 9 ? 1.0
                        : durationH < 7 ? durationH / 7.0
                        : 1.0 - Math.min(1.0, (durationH - 9) / 3.0)));
        double eff = Math.max(0, Math.min(1.0, efficiency / 0.85));
        double remPct  = sleepSec == 0 ? 0 : s.remSleepSec / (double) sleepSec;
        double rem     = Math.max(0, Math.min(1.0, remPct / 0.22));
        double deepPct = sleepSec == 0 ? 0 : s.deepSleepSec / (double) sleepSec;
        double deep    = Math.max(0, Math.min(1.0, deepPct / 0.18));
        double awakePenalty = Math.min(1.0, s.wakeCount / 5.0);

        double score = 40 * dur + 25 * eff + 15 * rem + 15 * deep + 5 * (1.0 - awakePenalty);
        return (int) Math.round(score);
    }

    /** Sleep debt (hours) against a per-night target. */
    public static double sleepDebtHours(List<SleepSession> sessions, double targetHoursPerNight) {
        if (sessions == null || sessions.isEmpty()) return 0;
        double debt = 0;
        for (SleepSession s : sessions) {
            double actualH = (s.deepSleepSec + s.lightSleepSec + s.remSleepSec) / 3600.0;
            debt += Math.max(0, targetHoursPerNight - actualH);
        }
        return debt;
    }

    // ====================================================================
    //  TIER 1.5 — additional vitals
    // ====================================================================

    /**
     * Cardiorespiratory fitness estimate — VO2max (ml/kg/min).
     * Uth et al, <i>Eur. J. Appl. Physiol.</i> 91(1):111-115 (2004):
     * {@code VO2max ≈ 15.3 · HRmax / HRrest}.
     *
     * <p><b>Per-metric opt-in:</b> devices with direct VO2max measurement
     * (treadmill protocol, sub-max exercise tests) should prefer that.
     */
    public static double vo2MaxUth(int hrMax, int hrRest) {
        if (hrMax <= 0 || hrRest <= 0 || hrMax <= hrRest) return -1;
        return 15.3 * ((double) hrMax / hrRest);
    }

    /** Cardiovascular-age delta (years). Jackson et al, <i>MSSE</i> 22:863-870 (1990). */
    public static double cardiovascularAgeDelta(double vo2max, int chronologicalAge) {
        if (vo2max <= 0 || chronologicalAge <= 0) return 0;
        return (60.0 - vo2max) / 0.55 - chronologicalAge;
    }

    /**
     * Heart-rate recovery (HRR) — drop in HR at {@code lagSeconds} after
     * peak. Cole et al, <i>N. Engl. J. Med.</i> 341(18):1351-1357 (1999).
     */
    public static int heartRateRecovery(List<? extends HeartRateSample> hr, int lagSeconds) {
        if (hr == null || hr.size() < 5) return -1;
        int peakIdx = 0, peakBpm = 0;
        for (int i = 0; i < hr.size(); i++) {
            if (hr.get(i).getHeartRate() > peakBpm) { peakBpm = hr.get(i).getHeartRate(); peakIdx = i; }
        }
        if (peakBpm <= 0) return -1;
        long targetMs = hr.get(peakIdx).getTimestamp() + lagSeconds * 1000L;
        for (int i = peakIdx + 1; i < hr.size(); i++) {
            if (Math.abs(hr.get(i).getTimestamp() - targetMs) <= 10_000) {
                return peakBpm - hr.get(i).getHeartRate();
            }
            if (hr.get(i).getTimestamp() > targetMs + 10_000) break;
        }
        return -1;
    }

    /**
     * Sleep Regularity Index (SRI, 0–100). Phillips et al,
     * <i>Sci. Rep.</i> 7:3216 (2017); Windred et al,
     * <i>SLEEP</i> 47(1):zsad253 (2024).
     */
    public static int sleepRegularityIndex(List<SleepSession> sessions) {
        if (sessions == null || sessions.size() < 2) return -1;
        List<boolean[]> days = new ArrayList<>();
        for (SleepSession s : sessions) {
            boolean[] minutes = new boolean[1440];
            for (SleepStage st : s.stages) {
                if (st.type == SleepStage.TYPE_AWAKE) continue;
                long t = st.startTimeMs;
                int startMin = (int) ((t / 60_000L) % 1440);
                int endMin = Math.min(1440, startMin + (st.durationSec / 60));
                for (int m = startMin; m < endMin; m++) minutes[m] = true;
            }
            days.add(minutes);
        }
        int agreements = 0, comparisons = 0;
        for (int i = 1; i < days.size(); i++) {
            boolean[] a = days.get(i - 1), b = days.get(i);
            for (int m = 0; m < 1440; m++) {
                if (a[m] == b[m]) agreements++;
                comparisons++;
            }
        }
        if (comparisons == 0) return -1;
        double pct = 100.0 * agreements / comparisons;
        return (int) Math.round(clamp(2.0 * pct - 100.0, 0, 100));
    }

    /**
     * Cardiac strain — Edwards' Training Impulse (TRIMP).
     * Edwards, <i>The Heart Rate Monitor Book</i> (1993); Foster et al,
     * <i>J. Strength Cond. Res.</i> 15(1):109-115 (2001).
     */
    public static int cardiacStrainEdwards(List<? extends HeartRateSample> hr, int hrMax, int hrRest) {
        if (hr == null || hr.size() < 2 || hrMax <= hrRest) return 0;
        int[] zoneCaps = heartRateZones(hrMax, hrRest);
        double total = 0;
        for (int i = 1; i < hr.size(); i++) {
            long dt = hr.get(i).getTimestamp() - hr.get(i - 1).getTimestamp();
            if (dt <= 0 || dt > 300_000) continue;
            int bpm = hr.get(i).getHeartRate();
            int weight = 0;
            for (int z = 0; z < zoneCaps.length; z++) {
                if (bpm >= zoneCaps[z]) weight = z + 1;
            }
            total += weight * (dt / 60_000.0);
        }
        return (int) Math.round(total);
    }

    // ====================================================================
    //  TIER 2 — approximations (no RR intervals)
    //
    //  Per-metric opt-in: any device that exposes native sensor data
    //  (RR-interval HRV, dedicated respiratory-rate algorithm, skin
    //  temperature thermistor) should prefer those over the approximations
    //  below.  These exist so HR-only devices can still light up the
    //  dashboard with a value, with the confidence band documented.
    // ====================================================================

    /**
     * HRV proxy (ms, RMSSD-like) — Shaffer &amp; Ginsberg,
     * <i>Front. Public Health</i> 5:258 (2017).  <b>Confidence:</b>
     * trend indicator only — absolute value differs systematically from a
     * chest-strap RMSSD.
     */
    public static double hrvProxyRmssd(List<? extends HeartRateSample> hr) {
        if (hr == null || hr.size() < 5) return -1;
        double sumSq = 0;
        int n = 0;
        for (int i = 1; i < hr.size(); i++) {
            long dt = hr.get(i).getTimestamp() - hr.get(i - 1).getTimestamp();
            if (dt <= 0 || dt > 60_000) continue;
            int b0 = hr.get(i - 1).getHeartRate();
            int b1 = hr.get(i).getHeartRate();
            if (b0 <= 0 || b1 <= 0) continue;
            double diff = 60_000.0 / b1 - 60_000.0 / b0;
            sumSq += diff * diff;
            n++;
        }
        if (n < 4) return -1;
        return Math.sqrt(sumSq / n);
    }

    /**
     * SDNN-style HRV proxy (ms) — Task Force ESC/NASPE,
     * <i>Circulation</i> 93(5):1043-1065 (1996).  Cross-check against
     * {@link #hrvProxyRmssd}; if the two diverge wildly, motion artefact
     * is likely.
     */
    public static double hrvProxySdnn(List<? extends HeartRateSample> hr) {
        if (hr == null || hr.size() < 5) return -1;
        List<Double> rrs = new ArrayList<>();
        for (int i = 0; i < hr.size(); i++) {
            if (hr.get(i).getHeartRate() <= 0) continue;
            if (i > 0) {
                long dt = hr.get(i).getTimestamp() - hr.get(i - 1).getTimestamp();
                if (dt <= 0 || dt > 60_000) continue;
            }
            rrs.add(60_000.0 / hr.get(i).getHeartRate());
        }
        if (rrs.size() < 4) return -1;
        double mean = 0;
        for (double v : rrs) mean += v;
        mean /= rrs.size();
        double sumSq = 0;
        for (double v : rrs) sumSq += (v - mean) * (v - mean);
        return Math.sqrt(sumSq / rrs.size());
    }

    /**
     * Respiratory rate estimate (breaths/min) from cyclic HR variation
     * (sinus arrhythmia).  Schäfer &amp; Kratky,
     * <i>Ann. Biomed. Eng.</i> 36(3):476-485 (2008).
     * <b>Confidence:</b> needs ≥1 Hz HR sampling rate; clamped to the
     * 6–30 br/min physiological range.
     */
    public static int respiratoryRateProxy(List<? extends HeartRateSample> hr) {
        if (hr == null || hr.size() < 60) return -1;
        double mean = avgBpm(hr);
        double std  = stdBpm(hr, mean);
        if (std < 1.0) return -1;
        int crossings = 0;
        boolean above = hr.get(0).getHeartRate() > mean;
        for (HeartRateSample r : hr) {
            boolean nowAbove = r.getHeartRate() > mean;
            if (nowAbove != above) { crossings++; above = nowAbove; }
        }
        long windowMs = hr.get(hr.size() - 1).getTimestamp() - hr.get(0).getTimestamp();
        if (windowMs <= 0) return -1;
        double cycles = crossings / 2.0;
        double rr = cycles * 60_000.0 / windowMs;
        return (int) Math.round(clamp(rr, 6, 30));
    }

    /** Daytime stress (0–100) — z-score of today's HR vs baseline,
     *  logistic squash.  Kim et al, <i>Psychiatry Investig.</i>
     *  15(3):235-245 (2018). */
    public static int dailyStress(List<? extends HeartRateSample> today,
                                  List<? extends HeartRateSample> baseline) {
        if (today == null || today.isEmpty() || baseline == null || baseline.size() < 10) return -1;
        double tAvg = avgBpm(today);
        double bAvg = avgBpm(baseline);
        double bStd = stdBpm(baseline, bAvg);
        if (bStd < 0.5) bStd = 0.5;
        double z = (tAvg - bAvg) / bStd;
        return (int) Math.round(100.0 / (1.0 + Math.exp(-z)));
    }

    /**
     * Readiness (0–100) — sleep + ΔRHR + ΔHRV + activity composite.
     * Akenhead &amp; Nassis, <i>Int. J. Sports Physiol. Perform.</i>
     * 11(5):587-593 (2016).
     */
    public static int readinessScore(int sleepScore0to100,
                                     int restingHrToday, int restingHrBaseline,
                                     double hrvToday, double hrvBaseline) {
        if (sleepScore0to100 < 0) return -1;
        double rhrComp = restingHrBaseline <= 0 || restingHrToday <= 0
                ? 50 : clamp(50 + (restingHrBaseline - restingHrToday) * 5, 0, 100);
        double hrvComp = hrvBaseline <= 0 || hrvToday <= 0
                ? 50 : clamp(50 + (hrvToday - hrvBaseline) / hrvBaseline * 100, 0, 100);
        return (int) Math.round(clamp(
                0.40 * sleepScore0to100 + 0.25 * rhrComp + 0.25 * hrvComp + 0.10 * 75,
                0, 100));
    }

    /** Energy (0–100) — readiness + stress + activity balance. */
    public static int energyScore(int sleepScore0to100,
                                  int restingHrToday, int restingHrBaseline,
                                  double hrvToday, double hrvBaseline,
                                  int stress0to100,
                                  double activeKcalToday, double activeKcalBaseline) {
        if (sleepScore0to100 < 0) return -1;
        double rhrComp = restingHrBaseline <= 0 || restingHrToday <= 0
                ? 50 : clamp(50 + (restingHrBaseline - restingHrToday) * 5, 0, 100);
        double hrvComp = hrvBaseline <= 0 || hrvToday <= 0
                ? 50 : clamp(50 + (hrvToday - hrvBaseline) / hrvBaseline * 100, 0, 100);
        double stressComp = stress0to100 < 0 ? 50 : (100 - stress0to100);
        double activityComp;
        if (activeKcalBaseline <= 0) activityComp = 50;
        else {
            double dev = Math.abs(activeKcalToday / activeKcalBaseline - 1.0);
            activityComp = clamp(100 - dev * 60, 0, 100);
        }
        return (int) Math.round(clamp(
                0.30 * sleepScore0to100 + 0.20 * rhrComp + 0.20 * hrvComp
                        + 0.15 * stressComp + 0.15 * activityComp,
                0, 100));
    }

    /** Body battery (0–100) — running tank: sleep − strain − stress. */
    public static int bodyBattery(int sleepScore0to100, int dailyStrain, int dailyStress0to100) {
        double battery = sleepScore0to100;
        battery -= clamp(dailyStrain * 0.125, 0, 50);
        battery -= clamp((dailyStress0to100 - 50) * 0.6, 0, 30);
        return (int) Math.round(clamp(battery, 0, 100));
    }

    // -------- helpers --------

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static double avgBpm(List<? extends HeartRateSample> r) {
        long sum = 0;
        for (HeartRateSample x : r) sum += x.getHeartRate();
        return sum / (double) r.size();
    }

    private static double stdBpm(List<? extends HeartRateSample> r, double mean) {
        double sumSq = 0;
        for (HeartRateSample x : r) {
            double d = x.getHeartRate() - mean;
            sumSq += d * d;
        }
        return Math.sqrt(sumSq / r.size());
    }
}
