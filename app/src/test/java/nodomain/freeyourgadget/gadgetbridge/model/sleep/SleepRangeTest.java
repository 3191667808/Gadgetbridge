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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class SleepRangeTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test
    public void inclusiveSampleQueryUsesExclusiveIntervalEnd() {
        final SleepRange range = SleepRange.fromInclusiveSampleQuery(100, 199);

        assertRange(range, 100, 199, 100, 200);
    }

    @Test
    public void matchingSampleAndIntervalEndKeepsChartBoundary() {
        final SleepRange range = SleepRange.fromMatchingSampleAndIntervalEnd(100, 200);

        assertRange(range, 100, 200, 100, 200);
    }

    @Test
    public void calendarDayUsesOffsetStartAndInclusiveSampleEnd() {
        final Calendar day = calendar(2026, Calendar.JANUARY, 2, 9, 45, 30);

        final SleepRange range = SleepRange.forCalendarDay(day, -12);

        final int expectedStartTs = timestamp(2026, Calendar.JANUARY, 1, 12, 0, 0);
        assertRange(range,
                expectedStartTs,
                expectedStartTs + 86_400 - 1,
                expectedStartTs,
                expectedStartTs + 86_400);
        assertEquals(2026, day.get(Calendar.YEAR));
        assertEquals(Calendar.JANUARY, day.get(Calendar.MONTH));
        assertEquals(2, day.get(Calendar.DAY_OF_MONTH));
        assertEquals(9, day.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    public void cutoffWindowAndPeriodDayShareCutoffBoundaries() {
        final TimeZone originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(UTC);
        try {
            final SleepRange dailyRange = SleepRange.forCutoffWindow(
                    timestamp(2026, Calendar.JANUARY, 2, 0, 0, 0),
                    timestamp(2026, Calendar.JANUARY, 3, 0, 0, 0),
                    18
            );
            final SleepRange periodRange = SleepRange.forDayEndingAtCutoff(
                    calendar(2026, Calendar.JANUARY, 3, 9, 45, 30),
                    18
            );

            final int expectedStartTs = timestamp(2026, Calendar.JANUARY, 2, 18, 0, 0);
            final int expectedEndTs = timestamp(2026, Calendar.JANUARY, 3, 18, 0, 0);
            assertRange(dailyRange, expectedStartTs, expectedEndTs, expectedStartTs, expectedEndTs);
            assertRange(periodRange, expectedStartTs, expectedEndTs, expectedStartTs, expectedEndTs);
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    private static void assertRange(final SleepRange range,
                                    final int sampleQueryStartTs,
                                    final int sampleQueryEndTs,
                                    final int intervalStartTs,
                                    final int intervalEndTs) {
        assertEquals(sampleQueryStartTs, range.getSampleQueryStartTs());
        assertEquals(sampleQueryEndTs, range.getSampleQueryEndTs());
        assertEquals(intervalStartTs, range.getIntervalStartTs());
        assertEquals(intervalEndTs, range.getIntervalEndTs());
    }

    private static Calendar calendar(final int year,
                                     final int month,
                                     final int day,
                                     final int hour,
                                     final int minute,
                                     final int second) {
        final Calendar calendar = Calendar.getInstance(UTC);
        calendar.set(year, month, day, hour, minute, second);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar;
    }

    private static int timestamp(final int year,
                                 final int month,
                                 final int day,
                                 final int hour,
                                 final int minute,
                                 final int second) {
        return (int) (calendar(year, month, day, hour, minute, second).getTimeInMillis() / 1000);
    }
}
