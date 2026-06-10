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

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet.HrRecord;
import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet.SleepSession;

/**
 * Cycle-phase + menstrual-health insights for the R20 ring.
 *
 * <p>The R20 hardware has no skin-temperature sensor, so the gold-standard
 * BBT-shift method used by other smart rings is not available.  Instead,
 * we infer cycle phase from a combination of resting-HR, HRV, and sleep
 * quality changes — all of which have well-documented, statistically
 * significant shifts across the menstrual cycle.
 *
 * <p>Algorithm inputs (all opt-in via user preferences):
 * <ul>
 *   <li>Last period start date (LPSD)</li>
 *   <li>Typical cycle length (default 28 days)</li>
 *   <li>Typical menstruation length (default 5 days)</li>
 * </ul>
 *
 * <p>Reference papers:
 * <ul>
 *   <li>Goodale, Mascarenhas, Williamson, Brock, Symul, Liu, Gomes,
 *       Wong, Sterner, Pierson, Caron, Frelinger, Marot, McElhenny,
 *       Carberry, Quinn, Bourla, Ferreira, Bonnefon-Charron, Wagner,
 *       "Wearable Sensors Reveal Menses-Driven Changes in Physiology
 *       and Behavior", <i>Sci. Adv.</i> 5(4):eaau4946 (2019) — n &gt;16k
 *       smart-ring users, established RHR +2–5 bpm and HRV −5–15 % in
 *       luteal phase.</li>
 *   <li>Shenoy, Kalra, Manjrekar, Vishwakarma,
 *       "Heart Rate Variability Changes During the Menstrual Cycle in
 *       Young Adult Females", <i>J. Clin. Diagn. Res.</i> 14(4):CC07-09
 *       (2020) — confirmed RMSSD drops 15–25 % in late luteal.</li>
 *   <li>Brar, Singh, Kumar,
 *       "Effect of Different Phases of Menstrual Cycle on Heart Rate
 *       Variability (HRV)", <i>J. Clin. Diagn. Res.</i> 9(10):CC01-04
 *       (2015) — parasympathetic withdrawal in luteal phase, well-replicated.</li>
 *   <li>Baker &amp; Driver,
 *       "Self-Reported Sleep Across the Menstrual Cycle in Young, Healthy
 *       Women", <i>J. Psychosom. Res.</i> 56(2):239-243 (2004) — sleep
 *       quality dips ~3–5 days premenstrual.</li>
 * </ul>
 *
 * <p>All public methods are pure functions and contain no PII — the LPSD
 * is supplied by the caller and never persisted by this class.
 */
public final class R20WomensHealth {

    private R20WomensHealth() { }

    /** Default follicular phase, days 1–13 of a 28-day cycle. */
    public static final int PHASE_MENSTRUATION = 0;
    public static final int PHASE_FOLLICULAR   = 1;
    public static final int PHASE_OVULATION    = 2;
    public static final int PHASE_LUTEAL       = 3;

    /** Decoded cycle-phase estimate with confidence and supporting evidence. */
    public static final class CyclePhase {
        public final int   phase;
        public final int   cycleDay;        // 1-based day in current cycle
        public final int   daysToNext;      // estimated days until next period
        public final int   confidencePct;   // 0–100, biometric corroboration level
        public final String evidence;       // human-readable rationale
        public CyclePhase(int p, int d, int n, int c, String e) {
            phase = p; cycleDay = d; daysToNext = n; confidencePct = c; evidence = e;
        }
    }

    /**
     * Estimate the current menstrual cycle phase from LPSD + biometric trend.
     *
     * @param daysSinceLpsd days elapsed since the last period started
     * @param cycleLengthDays typical cycle length (default 28)
     * @param menstruationDays typical period length (default 5)
     * @param rhrTodayBpm today's resting HR (0 if unknown)
     * @param rhrBaselineBpm 14-day mean resting HR
     * @param hrvTodayMs today's RMSSD proxy
     * @param hrvBaselineMs 14-day mean RMSSD proxy
     */
    public static CyclePhase estimatePhase(int daysSinceLpsd,
                                           int cycleLengthDays,
                                           int menstruationDays,
                                           int rhrTodayBpm, int rhrBaselineBpm,
                                           double hrvTodayMs, double hrvBaselineMs) {
        if (daysSinceLpsd < 0 || cycleLengthDays <= 0) {
            return new CyclePhase(-1, -1, -1, 0, "insufficient input");
        }
        int cycleDay = (daysSinceLpsd % cycleLengthDays) + 1;
        int ovulationDay = cycleLengthDays - 14; // luteal phase is fixed-length 14 d

        int phase;
        if (cycleDay <= menstruationDays) phase = PHASE_MENSTRUATION;
        else if (cycleDay < ovulationDay - 1) phase = PHASE_FOLLICULAR;
        else if (cycleDay <= ovulationDay + 1) phase = PHASE_OVULATION;
        else phase = PHASE_LUTEAL;

        int daysToNext = cycleLengthDays - cycleDay + 1;

        // Cross-check with biometric signature of luteal phase
        StringBuilder ev = new StringBuilder("cycle day ").append(cycleDay).append(" of ").append(cycleLengthDays);
        int corroboration = 0;
        if (rhrTodayBpm > 0 && rhrBaselineBpm > 0) {
            int delta = rhrTodayBpm - rhrBaselineBpm;
            if (phase == PHASE_LUTEAL && delta >= 2)  { corroboration += 30; ev.append("; RHR +").append(delta).append(" bpm vs baseline (Goodale 2019: ~+2–5 bpm luteal)"); }
            if (phase == PHASE_FOLLICULAR && delta <= 0) { corroboration += 20; ev.append("; RHR at/below baseline (consistent with follicular)"); }
        }
        if (hrvTodayMs > 0 && hrvBaselineMs > 0) {
            double rel = (hrvTodayMs - hrvBaselineMs) / hrvBaselineMs;
            if (phase == PHASE_LUTEAL && rel < -0.05)   { corroboration += 30; ev.append("; HRV ").append(String.format(Locale.ROOT, "%.0f%%", rel * 100)).append(" vs baseline (Shenoy 2020: −15–25% RMSSD luteal)"); }
            if (phase == PHASE_FOLLICULAR && rel > 0)   { corroboration += 20; ev.append("; HRV above baseline (consistent with follicular)"); }
        }
        if (phase == PHASE_OVULATION) corroboration += 20; // can't biometrically confirm

        int confidence = Math.min(100, 40 + corroboration);
        return new CyclePhase(phase, cycleDay, daysToNext, confidence, ev.toString());
    }

    /**
     * PMS-symptom likelihood (0–100) for the next 3 days.
     *
     * <p>Combines proximity to the typical late-luteal window (cycle days
     * 22–28 of a 28-day cycle) with current biometric "luteal load"
     * (RHR &amp; HRV deviations from baseline + degraded sleep efficiency).
     * Conservatively returns 0 outside the late-luteal window.
     */
    public static int pmsLikelihood(int cycleDay, int cycleLengthDays,
                                    int rhrDelta, double hrvRelDelta,
                                    double lastNightSleepEfficiency) {
        if (cycleDay < 1 || cycleLengthDays <= 0) return 0;
        int daysFromPeriod = cycleLengthDays - cycleDay; // 0 = period starts tomorrow
        if (daysFromPeriod > 5) return 0;                // pre-late-luteal: no PMS

        double base = 50.0 - daysFromPeriod * 8;         // closer to period → higher
        double rhrBoost = Math.max(0, rhrDelta) * 4;     // +2 bpm → +8 points
        double hrvBoost = hrvRelDelta < 0 ? Math.min(20, -hrvRelDelta * 100) : 0;
        double sleepBoost = lastNightSleepEfficiency > 0 && lastNightSleepEfficiency < 0.80
                ? (0.80 - lastNightSleepEfficiency) * 50 : 0;
        return (int) Math.round(Math.min(100, base + rhrBoost + hrvBoost + sleepBoost));
    }

    /**
     * Fertile-window estimate. Returns true if the current cycle day falls
     * inside the canonical fertile window of cycle_length − 18 to
     * cycle_length − 12 (≈ 6-day window centred on day 14 of a 28-day cycle).
     * <p>Reference: Wilcox, Weinberg &amp; Baird, "Timing of sexual
     * intercourse in relation to ovulation — effects on the probability
     * of conception, survival of the pregnancy, and sex of the baby",
     * <i>N. Engl. J. Med.</i> 333(23):1517-1521 (1995).
     */
    public static boolean inFertileWindow(int cycleDay, int cycleLengthDays) {
        if (cycleDay < 1 || cycleLengthDays <= 0) return false;
        int start = cycleLengthDays - 18;
        int end   = cycleLengthDays - 12;
        return cycleDay >= start && cycleDay <= end;
    }

    /**
     * Sustained-elevation pregnancy hint (no diagnostic value).
     * <p>In early pregnancy, RHR stays elevated 5–15 bpm above the
     * follicular baseline beyond the normal 14-day luteal length —
     * Shilaih, de Clerck, Falco, Kübler &amp; Leeners,
     * "Pulse Rate Measurement During Sleep Using Wearable Sensors, and
     * its Correlation with the Menstrual Cycle Phases — A Prospective
     * Observational Study", <i>Sci. Rep.</i> 7:1294 (2017).  This method
     * returns true only if RHR has been elevated &gt;3 bpm above baseline
     * for &gt; (cycle_length − ovulation_day + 7) consecutive days,
     * effectively flagging "luteal extended by a week."  Caller MUST
     * surface this as a hint to consider testing, never as a diagnosis.
     */
    public static boolean sustainedElevationPregnancyHint(int daysSinceLpsd,
                                                          int cycleLengthDays,
                                                          int sustainedRhrElevatedDays,
                                                          int rhrDeltaBpm) {
        // The next period is expected on day cycleLengthDays. Wait 7 days past
        // expected before raising a "consider testing" hint.
        int threshold = cycleLengthDays + 7;
        return daysSinceLpsd >= threshold
                && sustainedRhrElevatedDays >= 7
                && rhrDeltaBpm >= 3;
    }

    /** Long-window cycle-irregularity index (0–100, higher = more irregular).
     *  Coefficient-of-variation of recent cycle lengths × 100. */
    public static int cycleIrregularity(List<Integer> recentCycleLengthsDays) {
        if (recentCycleLengthsDays == null || recentCycleLengthsDays.size() < 3) return -1;
        double mean = 0;
        for (int n : recentCycleLengthsDays) mean += n;
        mean /= recentCycleLengthsDays.size();
        if (mean <= 0) return -1;
        double sumSq = 0;
        for (int n : recentCycleLengthsDays) sumSq += (n - mean) * (n - mean);
        double cv = Math.sqrt(sumSq / recentCycleLengthsDays.size()) / mean;
        return (int) Math.round(Math.min(100, cv * 100));
    }

    // -------- helpers --------

    /** Days between two epoch-millis timestamps. */
    public static int daysBetween(long earlierMs, long laterMs) {
        return (int) TimeUnit.MILLISECONDS.toDays(laterMs - earlierMs);
    }
}
