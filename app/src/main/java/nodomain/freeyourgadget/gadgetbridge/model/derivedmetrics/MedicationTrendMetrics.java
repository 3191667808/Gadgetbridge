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
 * Pharmacotherapy / treatment-response trend helpers for chronic medications that produce
 * observable shifts in resting heart rate or HRV over weeks.
 *
 * <p>This class is medication-agnostic; it provides primitives the UI layer can label however
 * the user wants. Nothing here is diagnostic — the methods compute longitudinal deltas that a
 * clinician (or the user with their clinician) can use to spot trend changes.
 *
 * <p>Underlying physiology references:
 * <ul>
 *   <li>Sun et al., "Effects of Glucagon-Like Peptide-1 Receptor Agonists on Resting Heart
 *       Rate: A Meta-Analysis of Randomized Controlled Trials", <i>J. Diabetes Investig.</i>
 *       14(3):378-389 (2023) — pooled RHR increase 1.9 bpm.</li>
 *   <li>Olsen et al., "Effects of Different Antihypertensive Drug Classes on Resting Heart
 *       Rate", <i>Am. J. Hypertens.</i> 35(4):344-352 (2022) — beta-blockers drop RHR
 *       8-12 bpm.</li>
 *   <li>Kemp &amp; Quintana, "The relationship between mental and physical health: insights
 *       from the study of heart rate variability", <i>Int. J. Psychophysiol.</i> 89(3):288-296
 *       (2013) — SSRIs reduce HRV across treatment.</li>
 *   <li>Wonisch et al., "Influence of Beta-Blocker Use on Percentage of Target Heart Rate
 *       Exercise Prescription", <i>Eur. J. Cardiovasc. Prev. Rehabil.</i> 10(4):296-301
 *       (2003) — beta-blockers cap HRmax by roughly 30–40 bpm.</li>
 * </ul>
 */
public final class MedicationTrendMetrics {
    private MedicationTrendMetrics() {
    }

    /**
     * Mean resting HR over the supplied window of nightly samples.
     * Caller should pass in the per-night RHR values (one per day) over a contiguous window
     * (e.g. 7 days or 28 days).
     *
     * @return mean bpm, or -1 if {@code window} is empty
     */
    public static double weeklyRhrMean(final List<Integer> window) {
        if (window == null || window.isEmpty()) return -1;
        double sum = 0;
        for (final int v : window) sum += v;
        return sum / window.size();
    }

    /**
     * Week-over-week delta in resting HR. Returns difference between the most-recent
     * {@code daysPerWindow}-day mean and the immediately-prior {@code daysPerWindow}-day mean.
     *
     * @return Δ bpm (positive = RHR has risen), or 0 if not enough data
     */
    public static double rhrWindowDelta(final List<Integer> nightlyRhr, final int daysPerWindow) {
        if (nightlyRhr == null || nightlyRhr.size() < daysPerWindow * 2) return 0;
        final int n = nightlyRhr.size();
        final List<Integer> recent = nightlyRhr.subList(n - daysPerWindow, n);
        final List<Integer> prior = nightlyRhr.subList(n - 2 * daysPerWindow, n - daysPerWindow);
        return weeklyRhrMean(recent) - weeklyRhrMean(prior);
    }

    /** Same RHR window delta using neutral HR and sleep inputs. */
    public static double rhrWindowDelta(final List<? extends HeartRateSample> hr,
                                        final List<SleepSession> sleep,
                                        final int daysPerWindow) {
        return rhrWindowDelta(nightlyRhr(hr, sleep), daysPerWindow);
    }

    /** Same as {@link #rhrWindowDelta(List, int)} but for the HRV proxy. */
    public static double hrvWindowDelta(final List<Double> nightlyHrvMs, final int daysPerWindow) {
        if (nightlyHrvMs == null || nightlyHrvMs.size() < daysPerWindow * 2) return 0;
        final int n = nightlyHrvMs.size();
        final double recent = mean(nightlyHrvMs.subList(n - daysPerWindow, n));
        final double prior = mean(nightlyHrvMs.subList(n - 2 * daysPerWindow, n - daysPerWindow));
        return recent - prior;
    }

    /** Plain mean of a list of doubles (skips nulls/negatives). */
    private static double mean(final List<Double> xs) {
        double sum = 0;
        int n = 0;
        for (final Double v : xs) if (v != null && v > 0) { sum += v; n++; }
        return n == 0 ? -1 : sum / n;
    }

    /**
     * Detect a sustained shift in baseline RHR — typical signature of a new chronic medication,
     * illness, or major training change.
     *
     * @return signed Δ bpm (positive = higher), or 0 if no clear shift
     */
    public static double detectBaselineShiftBpm(final List<Integer> nightlyRhr,
                                                final int recentDays,
                                                final int baselineDays,
                                                final int minSustainedDays,
                                                final int minDeltaBpm) {
        if (nightlyRhr == null || nightlyRhr.size() < recentDays + baselineDays) return 0;
        final int n = nightlyRhr.size();
        final List<Integer> recent = nightlyRhr.subList(n - recentDays, n);
        final List<Integer> baseline = nightlyRhr.subList(n - recentDays - baselineDays, n - recentDays);
        final double baseAvg = weeklyRhrMean(baseline);
        final double recAvg = weeklyRhrMean(recent);
        final double delta = recAvg - baseAvg;
        if (Math.abs(delta) < minDeltaBpm) return 0;
        int sameDir = 0;
        for (final int v : recent) {
            if ((delta > 0 && v > baseAvg) || (delta < 0 && v < baseAvg)) sameDir++;
        }
        return sameDir >= minSustainedDays ? delta : 0;
    }

    /** Same sustained-shift detection using neutral HR and sleep inputs. */
    public static double detectBaselineShiftBpm(final List<? extends HeartRateSample> hr,
                                                final List<SleepSession> sleep,
                                                final int recentDays,
                                                final int baselineDays,
                                                final int minSustainedDays,
                                                final int minDeltaBpm) {
        return detectBaselineShiftBpm(nightlyRhr(hr, sleep), recentDays, baselineDays, minSustainedDays, minDeltaBpm);
    }

    /**
     * Cardiovascular load adjusted for a known beta-blocker therapy.
     */
    public static int adjustedHrReservePercent(final int hrBpm, final int hrMaxPredicted,
                                               final int hrRest, final int hrMaxReductionFromBetaBlocker) {
        final int adjustedMax = Math.max(hrRest + 30, hrMaxPredicted - hrMaxReductionFromBetaBlocker);
        if (adjustedMax <= hrRest) return 0;
        final double pct = (hrBpm - hrRest) / (double) (adjustedMax - hrRest);
        return (int) Math.round(Math.max(0, Math.min(100, pct * 100)));
    }

    /** Builds chronological per-session resting-HR values from neutral HR + sleep data. */
    public static List<Integer> nightlyRhr(final List<? extends HeartRateSample> hr,
                                           final List<SleepSession> sleep) {
        if (hr == null || hr.isEmpty() || sleep == null || sleep.isEmpty()) return Collections.emptyList();
        final List<SleepSession> orderedSleep = new ArrayList<>(sleep);
        Collections.sort(orderedSleep, (a, b) -> Long.compare(a.startTimeMs, b.startTimeMs));
        final List<Integer> result = new ArrayList<>();
        for (final SleepSession session : orderedSleep) {
            final int rhr = restingHrForSession(hr, session);
            if (rhr > 0) result.add(rhr);
        }
        return result;
    }

    /** Resting HR for one neutral sleep session using the 5th-percentile sleep-window calculation. */
    public static int restingHrForSession(final List<? extends HeartRateSample> hr,
                                          final SleepSession sleep) {
        if (hr == null || hr.isEmpty() || sleep == null || sleep.endTimeMs <= sleep.startTimeMs) return -1;
        final List<Integer> bpms = new ArrayList<>();
        for (final HeartRateSample r : hr) {
            final long ts = r.getTimestamp();
            if (ts >= sleep.startTimeMs && ts <= sleep.endTimeMs && r.getHeartRate() > 0) {
                bpms.add(r.getHeartRate());
            }
        }
        if (bpms.isEmpty()) return -1;
        Collections.sort(bpms);
        final int idx = Math.max(0, (int) Math.floor(bpms.size() * 0.05));
        return bpms.get(idx);
    }
}
