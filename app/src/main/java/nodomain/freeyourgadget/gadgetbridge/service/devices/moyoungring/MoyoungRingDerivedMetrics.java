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

import nodomain.freeyourgadget.gadgetbridge.devices.moyoungring.MoyoungRingConstants;
import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.DerivedHealthMetrics;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepStage;

/**
 * Thin MoYoung-ring adapter over the shared {@link DerivedHealthMetrics} engine:
 * it maps the CRRepa/MoYoung vendor packet
 * types onto the neutral derived-metrics types and delegates the math so every
 * device shares the same formulas.
 *
 * <p>In addition it exposes a few ring-native, evidence-based wellness insights
 * that use the ring's own HRV/SpO2 streams (rather than an HR-proxy):
 * mean nocturnal HRV, HRV baseline deviation, and an SpO2 desaturation index.
 *
 * <p><b>Not medical:</b> all outputs here are wellness estimates for trend
 * tracking, not diagnostic values. HRV/stress/SpO2-screening figures must not
 * be presented as clinical measurements (see project medical-review notes).
 */
public final class MoyoungRingDerivedMetrics {

    private MoyoungRingDerivedMetrics() { }

    // ---------------------------------------------------------------- adapters

    public static HeartRateSample wrap(final MoyoungRingPacket.HrRecord r) {
        return new HeartRateSample() {
            @Override public int  getHeartRate() { return r.bpm; }
            @Override public long getTimestamp() { return r.timestampMs; }
        };
    }

    public static List<HeartRateSample> wrapAll(final List<MoyoungRingPacket.HrRecord> hr) {
        final List<HeartRateSample> out = new ArrayList<>(hr.size());
        // Defence in depth: raw parser records are unclamped, so drop implausible
        // HR (sentinels / out-of-range) before feeding the derived-metric formulas.
        for (final MoyoungRingPacket.HrRecord r : hr) {
            if (MoyoungRingConstants.plausibleHr(r.bpm)) out.add(wrap(r));
        }
        return out;
    }

    /**
     * Build a neutral {@link SleepSession} from the ring's flat list of sleep
     * segments (state/startTime/duration). Segments must be time-ordered.
     */
    public static SleepSession toSleepSession(final List<MoyoungRingPacket.SleepSegment> segs) {
        final SleepSession s = new SleepSession();
        if (segs == null || segs.isEmpty()) return s;
        s.startTimeMs = segs.get(0).startTimeMs;
        long lastEnd = s.startTimeMs;
        for (final MoyoungRingPacket.SleepSegment seg : segs) {
            final long end = seg.startTimeMs + (long) seg.durationSec * 1000L;
            if (end > lastEnd) lastEnd = end;
            switch (seg.state) {
                case MoyoungRingPacket.SleepSegment.STATE_DEEP:
                    s.deepSleepSec  += seg.durationSec; s.deepSleepCount++;
                    s.stages.add(new SleepStage(SleepStage.TYPE_DEEP,  seg.startTimeMs, seg.durationSec));
                    break;
                case MoyoungRingPacket.SleepSegment.STATE_LIGHT:
                    s.lightSleepSec += seg.durationSec; s.lightSleepCount++;
                    s.stages.add(new SleepStage(SleepStage.TYPE_LIGHT, seg.startTimeMs, seg.durationSec));
                    break;
                case MoyoungRingPacket.SleepSegment.STATE_REM:
                    s.remSleepSec   += seg.durationSec;
                    s.stages.add(new SleepStage(SleepStage.TYPE_REM,   seg.startTimeMs, seg.durationSec));
                    break;
                case MoyoungRingPacket.SleepSegment.STATE_AWAKE:
                default:
                    s.wakeDurationSec += seg.durationSec; s.wakeCount++;
                    s.stages.add(new SleepStage(SleepStage.TYPE_AWAKE, seg.startTimeMs, seg.durationSec));
                    break;
            }
        }
        s.endTimeMs = lastEnd;
        return s;
    }

    // ------------------------------------------------- thin shared delegations

    public static int restingHrFromSleep(final List<MoyoungRingPacket.HrRecord> hr,
                                         final List<MoyoungRingPacket.SleepSegment> sleep) {
        return DerivedHealthMetrics.restingHrFromSleep(wrapAll(hr),
                Collections.singletonList(toSleepSession(sleep)));
    }

    public static int restingHrFromDeepSleep(final List<MoyoungRingPacket.HrRecord> hr,
                                             final List<MoyoungRingPacket.SleepSegment> sleep) {
        return DerivedHealthMetrics.restingHrFromDeepSleep(wrapAll(hr),
                Collections.singletonList(toSleepSession(sleep)));
    }

    public static int maxHr(final List<MoyoungRingPacket.HrRecord> hr) {
        return DerivedHealthMetrics.maxHr(wrapAll(hr));
    }

    public static int tanakaHrMax(final int ageYears) {
        return DerivedHealthMetrics.tanakaHrMax(ageYears);
    }

    public static int[] heartRateZones(final int hrMax, final int restingHr) {
        return DerivedHealthMetrics.heartRateZones(hrMax, restingHr);
    }

    public static double activeCalories(final List<MoyoungRingPacket.HrRecord> hr,
                                        final int weightKg, final int ageYears, final boolean isMale) {
        return DerivedHealthMetrics.activeCalories(wrapAll(hr), weightKg, ageYears, isMale);
    }

    public static double distanceMetres(final int steps, final int heightCm, final boolean isMale) {
        return DerivedHealthMetrics.distanceMetres(steps, heightCm, isMale);
    }

    public static int sleepScore(final List<MoyoungRingPacket.SleepSegment> sleep) {
        return DerivedHealthMetrics.sleepScore(toSleepSession(sleep));
    }

    /** HR-derived HRV proxy (RMSSD, ms) — fallback when no native HRV stream. */
    public static double hrvProxyRmssd(final List<MoyoungRingPacket.HrRecord> hr) {
        return DerivedHealthMetrics.hrvProxyRmssd(wrapAll(hr));
    }

    public static double hrvProxySdnn(final List<MoyoungRingPacket.HrRecord> hr) {
        return DerivedHealthMetrics.hrvProxySdnn(wrapAll(hr));
    }

    public static int respiratoryRateProxy(final List<MoyoungRingPacket.HrRecord> hr) {
        return DerivedHealthMetrics.respiratoryRateProxy(wrapAll(hr));
    }

    public static int readinessScore(final int sleepScore, final int rhrToday, final int rhrBaseline,
                                     final double hrvToday, final double hrvBaseline) {
        return DerivedHealthMetrics.readinessScore(sleepScore, rhrToday, rhrBaseline, hrvToday, hrvBaseline);
    }

    public static int bodyBattery(final int sleepScore, final int dailyStrain, final int dailyStress) {
        return DerivedHealthMetrics.bodyBattery(sleepScore, dailyStrain, dailyStress);
    }

    public static double vo2MaxUth(final int hrMax, final int restingHr) {
        return DerivedHealthMetrics.vo2MaxUth(hrMax, restingHr);
    }

    // ---------------------------------------- ring-native evidence-based extras

    /** Mean HRV (ms) from the ring's own HRV stream (RMSSD-like). 0 if none. */
    public static double meanHrvMs(final List<MoyoungRingPacket.HrvRecord> hrv) {
        if (hrv == null || hrv.isEmpty()) return 0d;
        long sum = 0; int n = 0;
        for (final MoyoungRingPacket.HrvRecord r : hrv) {
            if (r.hrv >= 3 && r.hrv <= 200) { sum += r.hrv; n++; }
        }
        return n == 0 ? 0d : (double) sum / n;
    }

    /**
     * HRV deviation from a personal baseline, in percent. A sustained drop
     * &gt;20% below a 7/30-day baseline is a validated early signal of stress,
     * illness, or overtraining (Shaffer &amp; Ginsberg 2017; Task Force 1996).
     * Negative = below baseline (worse recovery).
     */
    public static double hrvBaselineDeviationPct(final double todayMs, final double baselineMs) {
        if (baselineMs <= 0d) return 0d;
        return (todayMs - baselineMs) / baselineMs * 100d;
    }

    /**
     * SpO2 desaturation index (events per hour) — a coarse OSA <b>screening</b>
     * proxy (not diagnostic). Counts samples dropping at least {@code dropPct}
     * below the night's median SpO2, normalised to the measured span in hours.
     * Correlates with AHI for moderate/severe OSA only (Sun et al. 2021).
     *
     * @param spo2      time-ordered nocturnal SpO2 records
     * @param dropPct   desaturation threshold (typically 3 or 4)
     */
    public static double spo2DesaturationIndex(final List<MoyoungRingPacket.Spo2Record> spo2, final int dropPct) {
        if (spo2 == null || spo2.size() < 2) return 0d;
        final List<Integer> vals = new ArrayList<>(spo2.size());
        long minTs = Long.MAX_VALUE, maxTs = Long.MIN_VALUE;
        for (final MoyoungRingPacket.Spo2Record r : spo2) {
            if (r.spo2 < 70 || r.spo2 > 100) continue;           // clamp implausible
            vals.add(r.spo2);
            if (r.timestampMs < minTs) minTs = r.timestampMs;
            if (r.timestampMs > maxTs) maxTs = r.timestampMs;
        }
        if (vals.size() < 2 || maxTs <= minTs) return 0d;
        final List<Integer> sorted = new ArrayList<>(vals);
        Collections.sort(sorted);
        final int median = sorted.get(sorted.size() / 2);
        int events = 0;
        for (final int v : vals) {
            if (median - v >= dropPct) events++;
        }
        final double hours = (maxTs - minTs) / 3_600_000d;
        return hours <= 0d ? 0d : events / hours;
    }
}
