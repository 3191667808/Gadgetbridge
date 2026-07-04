/*  Copyright (C) 2026 Freeyourgadget

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
package nodomain.freeyourgadget.gadgetbridge.model.sleep;

import java.util.Calendar;
import java.util.GregorianCalendar;

/**
 * Keeps raw activity sample query boundaries separate from corrected sleep interval boundaries.
 */
public final class SleepRange {
    private static final int DAY_SECONDS = 24 * 60 * 60;

    private final int sampleQueryStartTs;
    private final int sampleQueryEndTs;
    private final int intervalStartTs;
    private final int intervalEndTs;

    private SleepRange(final int sampleQueryStartTs,
                       final int sampleQueryEndTs,
                       final int intervalStartTs,
                       final int intervalEndTs) {
        this.sampleQueryStartTs = sampleQueryStartTs;
        this.sampleQueryEndTs = sampleQueryEndTs;
        this.intervalStartTs = intervalStartTs;
        this.intervalEndTs = intervalEndTs;
    }

    public static SleepRange fromInclusiveSampleQuery(final int startTs, final int sampleQueryEndTs) {
        return new SleepRange(startTs, sampleQueryEndTs, startTs, sampleQueryEndTs + 1);
    }

    public static SleepRange fromMatchingSampleAndIntervalEnd(final int startTs, final int endTs) {
        return new SleepRange(startTs, endTs, startTs, endTs);
    }

    public static SleepRange forCalendarDay(Calendar day, final int offsetHours) {
        day = (Calendar) day.clone();
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.add(Calendar.HOUR, offsetHours);

        final int startTs = toTimestamp(day);
        return fromInclusiveSampleQuery(startTs, startTs + DAY_SECONDS - 1);
    }

    public static SleepRange forCutoffWindow(final int startReferenceTs,
                                             final int endReferenceTs,
                                             final int cutoffHour) {
        final Calendar day = GregorianCalendar.getInstance();
        day.setTimeInMillis(startReferenceTs * 1000L);
        final int startTs = toCutoffTimestamp(day, cutoffHour);

        day.setTimeInMillis(endReferenceTs * 1000L);
        final int endTs = toCutoffTimestamp(day, cutoffHour);

        return fromMatchingSampleAndIntervalEnd(startTs, endTs);
    }

    public static SleepRange forDayEndingAtCutoff(Calendar day, final int cutoffHour) {
        day = (Calendar) day.clone();

        final int endTs = toCutoffTimestamp(day, cutoffHour);

        int startTs = endTs - DAY_SECONDS;
        day.setTimeInMillis(startTs * 1000L);
        startTs = toCutoffTimestamp(day, cutoffHour);

        return fromMatchingSampleAndIntervalEnd(startTs, endTs);
    }

    public int getSampleQueryStartTs() {
        return sampleQueryStartTs;
    }

    public int getSampleQueryEndTs() {
        return sampleQueryEndTs;
    }

    public int getIntervalStartTs() {
        return intervalStartTs;
    }

    public int getIntervalEndTs() {
        return intervalEndTs;
    }

    private static int toCutoffTimestamp(final Calendar day, final int cutoffHour) {
        day.set(Calendar.HOUR_OF_DAY, cutoffHour);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        return toTimestamp(day);
    }

    private static int toTimestamp(final Calendar day) {
        return (int) (day.getTimeInMillis() / 1000);
    }
}
