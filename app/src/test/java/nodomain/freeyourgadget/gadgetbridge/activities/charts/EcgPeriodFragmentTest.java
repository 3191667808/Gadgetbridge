/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.activities.charts;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class EcgPeriodFragmentTest {
    @Test
    public void groupsMeasurementsAcrossSpringDstBoundary() {
        final TimeZone zone = TimeZone.getTimeZone("Europe/Berlin");
        final long firstDay = localMidnight(zone, 2026, Calendar.MARCH, 28);
        final long secondDay = localMidnight(zone, 2026, Calendar.MARCH, 29);
        final long thirdDay = localMidnight(zone, 2026, Calendar.MARCH, 30);
        final long[] boundaries = {firstDay, secondDay, thirdDay};

        assertEquals(23L * 60L * 60L * 1000L, thirdDay - secondDay);
        assertEquals(0, EcgPeriodFragment.getDayIndex(secondDay - 1, boundaries));
        assertEquals(1, EcgPeriodFragment.getDayIndex(secondDay, boundaries));
        assertEquals(1, EcgPeriodFragment.getDayIndex(thirdDay - 1, boundaries));
        assertEquals(-1, EcgPeriodFragment.getDayIndex(thirdDay, boundaries));
    }

    private static long localMidnight(final TimeZone zone, final int year, final int month, final int day) {
        final Calendar calendar = Calendar.getInstance(zone);
        calendar.clear();
        calendar.set(year, month, day);
        return calendar.getTimeInMillis();
    }
}
