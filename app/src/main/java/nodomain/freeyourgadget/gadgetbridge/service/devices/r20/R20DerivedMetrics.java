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

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.HeartRateSample;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.DerivedHealthMetrics;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.derivedmetrics.SleepStage;

/**
 * Thin R20-specific adapter over {@link DerivedHealthMetrics}.  Maps the
 * R20-vendor packet types onto the neutral derived-metrics types and
 * delegates the math, so the R20 driver can keep its own value objects
 * while sharing every formula with other devices.
 */
public final class R20DerivedMetrics {

    private R20DerivedMetrics() { }

    // -------- adapters --------

    public static HeartRateSample wrap(final R20Packet.HrRecord r) {
        return new HeartRateSample() {
            @Override public int  getHeartRate() { return r.bpm; }
            @Override public long getTimestamp() { return r.timestampMs; }
        };
    }

    public static List<HeartRateSample> wrapAll(List<R20Packet.HrRecord> hr) {
        List<HeartRateSample> out = new ArrayList<>(hr.size());
        for (R20Packet.HrRecord r : hr) out.add(wrap(r));
        return out;
    }

    public static SleepSession wrap(R20Packet.SleepSession s) {
        SleepSession n = new SleepSession();
        n.startTimeMs    = s.startTimeMs;
        n.endTimeMs      = s.endTimeMs;
        n.deepSleepCount = s.deepSleepCount;
        n.lightSleepCount= s.lightSleepCount;
        n.deepSleepSec   = s.deepSleepSec;
        n.lightSleepSec  = s.lightSleepSec;
        n.remSleepSec    = s.remSleepSec;
        n.wakeCount      = s.wakeCount;
        n.wakeDurationSec= s.wakeDurationSec;
        for (R20Packet.SleepStage st : s.stages) {
            n.stages.add(new SleepStage(st.type, st.startTimeMs, st.durationSec));
        }
        return n;
    }

    public static List<SleepSession> wrapSessions(List<R20Packet.SleepSession> ss) {
        List<SleepSession> out = new ArrayList<>(ss.size());
        for (R20Packet.SleepSession s : ss) out.add(wrap(s));
        return out;
    }

    // -------- thin delegation (R20-typed inputs) --------

    public static int restingHrFromSleep(List<R20Packet.HrRecord> hr, List<R20Packet.SleepSession> sleep) {
        return DerivedHealthMetrics.restingHrFromSleep(wrapAll(hr), wrapSessions(sleep));
    }
    public static int restingHrFromDeepSleep(List<R20Packet.HrRecord> hr, List<R20Packet.SleepSession> sleep) {
        return DerivedHealthMetrics.restingHrFromDeepSleep(wrapAll(hr), wrapSessions(sleep));
    }
    public static int maxHr(List<R20Packet.HrRecord> hr) { return DerivedHealthMetrics.maxHr(wrapAll(hr)); }
    public static int tanakaHrMax(int age) { return DerivedHealthMetrics.tanakaHrMax(age); }
    public static int[] heartRateZones(int hrMax, int rhr) { return DerivedHealthMetrics.heartRateZones(hrMax, rhr); }

    public static double activeCalories(List<R20Packet.HrRecord> hr, int weightKg, int ageYears, boolean isMale) {
        return DerivedHealthMetrics.activeCalories(wrapAll(hr), weightKg, ageYears, isMale);
    }
    public static double distanceMetres(int steps, int heightCm, boolean isMale) {
        return DerivedHealthMetrics.distanceMetres(steps, heightCm, isMale);
    }

    public static int sleepScore(R20Packet.SleepSession s) { return DerivedHealthMetrics.sleepScore(wrap(s)); }
    public static double sleepDebtHours(List<R20Packet.SleepSession> ss, double target) {
        return DerivedHealthMetrics.sleepDebtHours(wrapSessions(ss), target);
    }

    public static double hrvProxyRmssd(List<R20Packet.HrRecord> hr) {
        return DerivedHealthMetrics.hrvProxyRmssd(wrapAll(hr));
    }
    public static double hrvProxySdnn(List<R20Packet.HrRecord> hr) {
        return DerivedHealthMetrics.hrvProxySdnn(wrapAll(hr));
    }
    public static int respiratoryRateProxy(List<R20Packet.HrRecord> hr) {
        return DerivedHealthMetrics.respiratoryRateProxy(wrapAll(hr));
    }

    public static int dailyStress(List<R20Packet.HrRecord> today, List<R20Packet.HrRecord> baseline) {
        return DerivedHealthMetrics.dailyStress(wrapAll(today), wrapAll(baseline));
    }
    public static int readinessScore(int sleepScore, int rhrToday, int rhrBaseline,
                                     double hrvToday, double hrvBaseline) {
        return DerivedHealthMetrics.readinessScore(sleepScore, rhrToday, rhrBaseline, hrvToday, hrvBaseline);
    }
    public static int energyScore(int sleepScore, int rhrT, int rhrB, double hrvT, double hrvB,
                                  int stress, double kcalT, double kcalB) {
        return DerivedHealthMetrics.energyScore(sleepScore, rhrT, rhrB, hrvT, hrvB, stress, kcalT, kcalB);
    }
    public static int bodyBattery(int sleep, int strain, int stress) {
        return DerivedHealthMetrics.bodyBattery(sleep, strain, stress);
    }

    public static double vo2MaxUth(int hrMax, int rhr) { return DerivedHealthMetrics.vo2MaxUth(hrMax, rhr); }
    public static double cardiovascularAgeDelta(double vo2, int age) {
        return DerivedHealthMetrics.cardiovascularAgeDelta(vo2, age);
    }
    public static int heartRateRecovery(List<R20Packet.HrRecord> hr, int lagS) {
        return DerivedHealthMetrics.heartRateRecovery(wrapAll(hr), lagS);
    }
    public static int cardiacStrainEdwards(List<R20Packet.HrRecord> hr, int hrMax, int rhr) {
        return DerivedHealthMetrics.cardiacStrainEdwards(wrapAll(hr), hrMax, rhr);
    }
    public static int sleepRegularityIndex(List<R20Packet.SleepSession> ss) {
        return DerivedHealthMetrics.sleepRegularityIndex(wrapSessions(ss));
    }
}
