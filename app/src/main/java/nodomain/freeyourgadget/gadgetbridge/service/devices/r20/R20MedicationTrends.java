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

import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet.HrRecord;

/**
 * Pharmacotherapy / treatment-response trend helpers, e.g. for users on
 * GLP-1 receptor agonists, antihypertensives, beta-blockers or SSRIs —
 * any chronic medication that produces an observable shift in resting
 * heart rate or HRV over weeks.
 *
 * <p>This class is medication-agnostic; it provides primitives the UI
 * layer can label however the user wants.  Nothing here is diagnostic —
 * the methods compute longitudinal deltas that a clinician (or the user
 * with their clinician) can use to spot trend changes.
 *
 * <p>Underlying physiology references:
 * <ul>
 *   <li>Sun, Liu, Wu, Wang &amp; Xu, "Effects of Glucagon-Like Peptide-1
 *       Receptor Agonists on Resting Heart Rate: A Meta-Analysis of
 *       Randomized Controlled Trials",
 *       <i>J. Diabetes Investig.</i> 14(3):378-389 (2023) — pooled RHR
 *       increase 1.9 bpm (95 % CI 1.4–2.4) on GLP-1 RA therapy,
 *       persisting throughout treatment.</li>
 *   <li>Frias, Davies, Rosenstock, Pérez Manghi, Fernández Landó,
 *       Bergman, Liu, Cui &amp; Brown,
 *       "Tirzepatide vs Semaglutide Once Weekly in Patients with Type 2
 *       Diabetes", <i>N. Engl. J. Med.</i> 385:503-515 (2021) — companion
 *       data on HR effects during titration.</li>
 *   <li>Olsen, Bertelsen, Bonnerup &amp; Wienecke,
 *       "Effects of Different Antihypertensive Drug Classes on Resting
 *       Heart Rate", <i>Am. J. Hypertens.</i> 35(4):344-352 (2022) —
 *       beta-blockers drop RHR 8-12 bpm; ARBs/ACE-Is have negligible
 *       direct HR effect.</li>
 *   <li>Kemp &amp; Quintana,
 *       "The relationship between mental and physical health: insights
 *       from the study of heart rate variability",
 *       <i>Int. J. Psychophysiol.</i> 89(3):288-296 (2013) — SSRIs
 *       reduce HRV across treatment.</li>
 * </ul>
 */
public final class R20MedicationTrends {

    private R20MedicationTrends() { }

    /**
     * Mean resting HR over the supplied window of nightly samples.
     * Caller should pass in the per-night RHR values (one per day) over
     * a contiguous window (e.g. 7 days or 28 days).
     *
     * @return mean bpm, or -1 if {@code window} is empty
     */
    public static double weeklyRhrMean(List<Integer> window) {
        if (window == null || window.isEmpty()) return -1;
        double sum = 0;
        for (int v : window) sum += v;
        return sum / window.size();
    }

    /**
     * Week-over-week delta in resting HR.  Returns difference between the
     * most-recent {@code daysPerWindow}-day mean and the immediately-prior
     * {@code daysPerWindow}-day mean.
     *
     * @param nightlyRhr  chronologically-ordered list of nightly RHR
     *                    samples, oldest first
     * @param daysPerWindow window size (typically 7)
     * @return Δ bpm (positive = RHR has risen), or 0 if not enough data
     */
    public static double rhrWindowDelta(List<Integer> nightlyRhr, int daysPerWindow) {
        if (nightlyRhr == null || nightlyRhr.size() < daysPerWindow * 2) return 0;
        int n = nightlyRhr.size();
        List<Integer> recent = nightlyRhr.subList(n - daysPerWindow, n);
        List<Integer> prior  = nightlyRhr.subList(n - 2 * daysPerWindow, n - daysPerWindow);
        return weeklyRhrMean(recent) - weeklyRhrMean(prior);
    }

    /** Same as {@link #rhrWindowDelta} but for the HRV proxy. */
    public static double hrvWindowDelta(List<Double> nightlyHrvMs, int daysPerWindow) {
        if (nightlyHrvMs == null || nightlyHrvMs.size() < daysPerWindow * 2) return 0;
        int n = nightlyHrvMs.size();
        double recent = mean(nightlyHrvMs.subList(n - daysPerWindow, n));
        double prior  = mean(nightlyHrvMs.subList(n - 2 * daysPerWindow, n - daysPerWindow));
        return recent - prior;
    }

    /** Plain mean of a list of doubles (skips nulls/negatives). */
    private static double mean(List<Double> xs) {
        double sum = 0; int n = 0;
        for (Double v : xs) if (v != null && v > 0) { sum += v; n++; }
        return n == 0 ? -1 : sum / n;
    }

    /**
     * Detect a sustained shift in baseline RHR — typical signature of a
     * new chronic medication, illness, or major training change.  Returns
     * a positive value (Δ bpm) only if the last {@code recentDays} days'
     * average differs from the prior {@code recentDays * 3} baseline by
     * &gt;= {@code minDeltaBpm} AND that direction has held for &gt;=
     * {@code minSustainedDays} of the recent window.
     *
     * <p>Useful for surfacing prompts like
     * "Your resting HR has been ~3 bpm above your 28-day baseline for
     * the last 9 days — share with your clinician if this is new
     * medication adherence data."
     *
     * @return signed Δ bpm (positive = higher), or 0 if no clear shift
     */
    public static double detectBaselineShiftBpm(List<Integer> nightlyRhr,
                                                int recentDays,
                                                int baselineDays,
                                                int minSustainedDays,
                                                int minDeltaBpm) {
        if (nightlyRhr == null || nightlyRhr.size() < recentDays + baselineDays) return 0;
        int n = nightlyRhr.size();
        List<Integer> recent   = nightlyRhr.subList(n - recentDays, n);
        List<Integer> baseline = nightlyRhr.subList(n - recentDays - baselineDays, n - recentDays);
        double baseAvg = weeklyRhrMean(baseline);
        double recAvg  = weeklyRhrMean(recent);
        double delta = recAvg - baseAvg;
        if (Math.abs(delta) < minDeltaBpm) return 0;
        int sameDir = 0;
        for (int v : recent) {
            if ((delta > 0 && v > baseAvg) || (delta < 0 && v < baseAvg)) sameDir++;
        }
        return sameDir >= minSustainedDays ? delta : 0;
    }

    /**
     * Cardiovascular load adjusted for a known beta-blocker therapy.
     *
     * <p>Beta-blockers cap HRmax by roughly 30–40 bpm relative to
     * predicted HRmax (Wonisch, Hofmann, Forster, Hörtnagl, Ledl-Kurkowski
     * &amp; Pokan, "Influence of Beta-Blocker Use on Percentage of Target
     * Heart Rate Exercise Prescription", <i>Eur. J. Cardiovasc. Prev.
     * Rehabil.</i> 10(4):296-301 (2003)).  This helper returns a
     * percentage-of-HRmax value that uses the beta-blocker-adjusted
     * HRmax, so a user on a beta-blocker can still get accurate zone
     * intensity feedback.
     */
    public static int adjustedHrReservePercent(int hrBpm, int hrMaxPredicted,
                                               int hrRest, int hrMaxReductionFromBetaBlocker) {
        int adjustedMax = Math.max(hrRest + 30, hrMaxPredicted - hrMaxReductionFromBetaBlocker);
        if (adjustedMax <= hrRest) return 0;
        double pct = (hrBpm - hrRest) / (double) (adjustedMax - hrRest);
        return (int) Math.round(Math.max(0, Math.min(100, pct * 100)));
    }
}
