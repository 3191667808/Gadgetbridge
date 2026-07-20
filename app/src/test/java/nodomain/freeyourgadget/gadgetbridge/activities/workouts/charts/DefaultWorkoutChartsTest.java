/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts.charts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;

/**
 * Some sources (Xiaomi/Bangle GPS tracks) expose position but no distance field, so the distance
 * chart used to stay empty for them. {@link DefaultWorkoutCharts#deriveCumulativeDistances} fills a
 * running distance from the fixes when none is provided. These checks pin that derivation (haversine,
 * cumulative metres) without touching Android's {@code Location} or the chart rendering.
 */
public class DefaultWorkoutChartsTest {

    private static ActivityPoint point(final long ts, final double lon, final double lat) {
        final ActivityPoint ap = new ActivityPoint(new Date(ts));
        ap.setLocation(new GPSCoordinate(lon, lat));
        return ap;
    }

    @Test
    public void firstFixIsZeroAndDistanceAccumulates() {
        // 45.000,5.000 -> 45.000,5.001 is ~78.6 m of easting at this latitude;
        // -> 45.001,5.001 adds ~111.2 m of northing.
        final List<ActivityPoint> points = Arrays.asList(
                point(1000, 5.000, 45.000),
                point(2000, 5.001, 45.000),
                point(3000, 5.001, 45.001));

        final double[] d = DefaultWorkoutCharts.deriveCumulativeDistances(points);

        assertEquals(0.0, d[0], 1e-9);
        assertEquals(78.6, d[1], 1.0);
        assertEquals(78.6 + 111.2, d[2], 1.5);
        assertTrue("monotonic non-decreasing", d[2] > d[1]);
    }

    @Test
    public void nullIslandFixesAreSkippedAndDoNotJumpDistance() {
        final List<ActivityPoint> points = Arrays.asList(
                point(1000, 5.001, 45.000),
                point(2000, 0.0, 0.0),      // no-fix placeholder
                point(3000, 5.001, 45.001));

        final double[] d = DefaultWorkoutCharts.deriveCumulativeDistances(points);

        assertEquals(0.0, d[0], 1e-9);
        assertTrue("no-fix point has no distance", Double.isNaN(d[1]));
        // distance across the dropout is p0->p2 (~111.2 m northing), not a jump via 0,0
        assertEquals(111.2, d[2], 1.5);
    }

    @Test
    public void pointsWithoutLocationAreSkipped() {
        final List<ActivityPoint> points = Arrays.asList(
                point(1000, 5.000, 45.000),
                new ActivityPoint(new Date(2000)), // no location
                point(3000, 5.001, 45.000));

        final double[] d = DefaultWorkoutCharts.deriveCumulativeDistances(points);

        assertTrue("point without location has no distance", Double.isNaN(d[1]));
        assertEquals(78.6, d[2], 1.0);
    }

    @Test
    public void nativeDistanceIsHonouredNotDerived() {
        final ActivityPoint withDistance = point(1000, 5.000, 45.000);
        withDistance.setDistance(42.0);
        final List<ActivityPoint> points = Arrays.asList(
                withDistance,
                point(2000, 5.001, 45.000));

        // Any point carrying a distance => derivation stands down entirely.
        assertNull(DefaultWorkoutCharts.deriveCumulativeDistances(points));
    }

    @Test
    public void noUsableFixesReturnsNull() {
        final List<ActivityPoint> points = Arrays.asList(
                new ActivityPoint(new Date(1000)),
                point(2000, 0.0, 0.0));

        assertNull(DefaultWorkoutCharts.deriveCumulativeDistances(points));
    }
}
