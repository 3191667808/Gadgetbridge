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
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;

/**
 * Cycle-phase + menstrual-health insights computed from device-neutral HR and sleep inputs.
 *
 * <p>Devices without skin-temperature sensors can infer cycle phase from a combination of
 * resting-HR, HRV, and sleep quality changes — all of which have well-documented,
 * statistically significant shifts across the menstrual cycle.
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
 *   <li>Goodale et al., "Wearable Sensors Reveal Menses-Driven Changes in Physiology
 *       and Behavior", <i>Sci. Adv.</i> 5(4):eaau4946 (2019) — n &gt;16k smart-ring users,
 *       established RHR +2–5 bpm and HRV −5–15 % in luteal phase.</li>
 *   <li>Shenoy et al., "Heart Rate Variability Changes During the Menstrual Cycle in
 *       Young Adult Females", <i>J. Clin. Diagn. Res.</i> 14(4):CC07-09 (2020) — confirmed
 *       RMSSD drops 15–25 % in late luteal.</li>
 *   <li>Brar et al., "Effect of Different Phases of Menstrual Cycle on Heart Rate
 *       Variability (HRV)", <i>J. Clin. Diagn. Res.</i> 9(10):CC01-04 (2015) —
 *       parasympathetic withdrawal in luteal phase, well-replicated.</li>
 *   <li>Baker &amp; Driver, "Self-Reported Sleep Across the Menstrual Cycle in Young,
 *       Healthy Women", <i>J. Psychosom. Res.</i> 56(2):239-243 (2004) — sleep quality dips
 *       ~3–5 days premenstrual.</li>
 * </ul>
 *
 * <p>All public methods are pure functions and contain no PII — the LPSD is supplied by
 * the caller and never persisted by this class.
 */
public final class WomensHealthMetrics {
    private WomensHealthMetrics() {
    }

    /** Default follicular phase, days 1–13 of a 28-day cycle. */
    public static final int PHASE_MENSTRUATION = 0;
    public static final int PHASE_FOLLICULAR = 1;
    public static final int PHASE_OVULATION = 2;
    public static final int PHASE_LUTEAL = 3;

    /** Decoded cycle-phase estimate with confidence and supporting evidence. */
    public static final class CyclePhase {
        public final int phase;
        public final int cycleDay;
        public final int daysToNext;
        public final int confidencePct;
        public final String evidence;

        public CyclePhase(final int phase, final int cycleDay, final int daysToNext,
                          final int confidencePct, final String evidence) {
            this.phase = phase;
            this.cycleDay = cycleDay;
            this.daysToNext = daysToNext;
            this.confidencePct = confidencePct;
            this.evidence = evidence;
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
    public static CyclePhase estimatePhase(final int daysSinceLpsd,
                                           final int cycleLengthDays,
                                           final int menstruationDays,
                                           final int rhrTodayBpm, final int rhrBaselineBpm,
                                           final double hrvTodayMs, final double hrvBaselineMs) {
        if (daysSinceLpsd < 0 || cycleLengthDays <= 0) {
            return new CyclePhase(-1, -1, -1, 0, "insufficient input");
        }
        final int cycleDay = (daysSinceLpsd % cycleLengthDays) + 1;
        final int ovulationDay = cycleLengthDays - 14;

        final int phase;
        if (cycleDay <= menstruationDays) {
            phase = PHASE_MENSTRUATION;
        } else if (cycleDay < ovulationDay - 1) {
            phase = PHASE_FOLLICULAR;
        } else if (cycleDay <= ovulationDay + 1) {
            phase = PHASE_OVULATION;
        } else {
            phase = PHASE_LUTEAL;
        }

        final int daysToNext = cycleLengthDays - cycleDay + 1;

        final StringBuilder ev = new StringBuilder("cycle day ").append(cycleDay).append(" of ").append(cycleLengthDays);
        int corroboration = 0;
        if (rhrTodayBpm > 0 && rhrBaselineBpm > 0) {
            final int delta = rhrTodayBpm - rhrBaselineBpm;
            if (phase == PHASE_LUTEAL && delta >= 2) {
                corroboration += 30;
                ev.append("; RHR +").append(delta).append(" bpm vs baseline (Goodale 2019: ~+2–5 bpm luteal)");
            }
            if (phase == PHASE_FOLLICULAR && delta <= 0) {
                corroboration += 20;
                ev.append("; RHR at/below baseline (consistent with follicular)");
            }
        }
        if (hrvTodayMs > 0 && hrvBaselineMs > 0) {
            final double rel = (hrvTodayMs - hrvBaselineMs) / hrvBaselineMs;
            if (phase == PHASE_LUTEAL && rel < -0.05) {
                corroboration += 30;
                ev.append("; HRV ").append(String.format("%.0f%%", rel * 100))
                        .append(" vs baseline (Shenoy 2020: −15–25% RMSSD luteal)");
            }
            if (phase == PHASE_FOLLICULAR && rel > 0) {
                corroboration += 20;
                ev.append("; HRV above baseline (consistent with follicular)");
            }
        }
        if (phase == PHASE_OVULATION) {
            corroboration += 20;
        }

        final int confidence = Math.min(100, 40 + corroboration);
        return new CyclePhase(phase, cycleDay, daysToNext, confidence, ev.toString());
    }

    /**
     * PMS-symptom likelihood (0–100) for the next 3 days.
     *
     * <p>Combines proximity to the typical late-luteal window (cycle days 22–28 of a
     * 28-day cycle) with current biometric "luteal load" (RHR &amp; HRV deviations from
     * baseline + degraded sleep efficiency). Conservatively returns 0 outside the
     * late-luteal window.
     */
    public static int pmsLikelihood(final int cycleDay, final int cycleLengthDays,
                                    final int rhrDelta, final double hrvRelDelta,
                                    final double lastNightSleepEfficiency) {
        if (cycleDay < 1 || cycleLengthDays <= 0) return 0;
        final int daysFromPeriod = cycleLengthDays - cycleDay;
        if (daysFromPeriod > 5) return 0;

        final double base = 50.0 - daysFromPeriod * 8;
        final double rhrBoost = Math.max(0, rhrDelta) * 4;
        final double hrvBoost = hrvRelDelta < 0 ? Math.min(20, -hrvRelDelta * 100) : 0;
        final double sleepBoost = lastNightSleepEfficiency > 0 && lastNightSleepEfficiency < 0.80
                ? (0.80 - lastNightSleepEfficiency) * 50 : 0;
        return (int) Math.round(Math.min(100, base + rhrBoost + hrvBoost + sleepBoost));
    }

    /** Same PMS formula using neutral sleep-session input for the sleep-efficiency term. */
    public static int pmsLikelihood(final int cycleDay, final int cycleLengthDays,
                                    final int rhrTodayBpm, final int rhrBaselineBpm,
                                    final double hrvTodayMs, final double hrvBaselineMs,
                                    final SleepSession lastNightSleep) {
        final int rhrDelta = rhrTodayBpm > 0 && rhrBaselineBpm > 0 ? rhrTodayBpm - rhrBaselineBpm : 0;
        final double hrvRelDelta = hrvTodayMs > 0 && hrvBaselineMs > 0 ? (hrvTodayMs - hrvBaselineMs) / hrvBaselineMs : 0;
        return pmsLikelihood(cycleDay, cycleLengthDays, rhrDelta, hrvRelDelta, sleepEfficiency(lastNightSleep));
    }

    /**
     * Resting heart rate (bpm) from HR samples observed during neutral sleep sessions.
     * Uses the same 5th-percentile sleep-window calculation as other derived metrics.
     */
    public static int restingHrFromSleep(final List<? extends HeartRateSample> hr,
                                         final List<SleepSession> sleep) {
        if (hr == null || hr.isEmpty() || sleep == null || sleep.isEmpty()) return -1;
        final List<Integer> bpms = new ArrayList<>();
        for (final HeartRateSample r : hr) {
            final long ts = r.getTimestamp();
            for (final SleepSession s : sleep) {
                if (ts >= s.startTimeMs && ts <= s.endTimeMs) {
                    bpms.add(r.getHeartRate());
                    break;
                }
            }
        }
        if (bpms.isEmpty()) return -1;
        Collections.sort(bpms);
        final int idx = Math.max(0, (int) Math.floor(bpms.size() * 0.05));
        return bpms.get(idx);
    }

    /** Fertile-window estimate based on Wilcox, Weinberg &amp; Baird (1995). */
    public static boolean inFertileWindow(final int cycleDay, final int cycleLengthDays) {
        if (cycleDay < 1 || cycleLengthDays <= 0) return false;
        final int start = cycleLengthDays - 18;
        final int end = cycleLengthDays - 12;
        return cycleDay >= start && cycleDay <= end;
    }

    /** Sustained-elevation pregnancy hint (no diagnostic value). */
    public static boolean sustainedElevationPregnancyHint(final int daysSinceLpsd,
                                                          final int cycleLengthDays,
                                                          final int sustainedRhrElevatedDays,
                                                          final int rhrDeltaBpm) {
        final int threshold = cycleLengthDays + 7;
        return daysSinceLpsd >= threshold
                && sustainedRhrElevatedDays >= 7
                && rhrDeltaBpm >= 3;
    }

    /** Long-window cycle-irregularity index (0–100, higher = more irregular). */
    public static int cycleIrregularity(final List<Integer> recentCycleLengthsDays) {
        if (recentCycleLengthsDays == null || recentCycleLengthsDays.size() < 3) return -1;
        double mean = 0;
        for (final int n : recentCycleLengthsDays) mean += n;
        mean /= recentCycleLengthsDays.size();
        if (mean <= 0) return -1;
        double sumSq = 0;
        for (final int n : recentCycleLengthsDays) sumSq += (n - mean) * (n - mean);
        final double cv = Math.sqrt(sumSq / recentCycleLengthsDays.size()) / mean;
        return (int) Math.round(Math.min(100, cv * 100));
    }

    /** Days between two epoch-millis timestamps. */
    public static int daysBetween(final long earlierMs, final long laterMs) {
        return (int) TimeUnit.MILLISECONDS.toDays(laterMs - earlierMs);
    }

    public static double sleepEfficiency(final SleepSession session) {
        if (session == null || session.endTimeMs <= session.startTimeMs) return 0;
        final long totalSec = (session.endTimeMs - session.startTimeMs) / 1000;
        if (totalSec <= 0) return 0;
        return (session.deepSleepSec + session.lightSleepSec + session.remSleepSec) / (double) totalSec;
    }
}
