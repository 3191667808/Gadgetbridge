/*  Copyright (C) 2026 d3vv3

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

import static org.junit.Assert.assertArrayEquals;

import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class RespiratoryRateDailyFragmentTest {
    @Test
    public void usesGlobalDataBounds() {
        final LineData data = new LineData(
                new LineDataSet(Arrays.asList(new Entry(100f, 14f), new Entry(200f, 15f)), "a"),
                new LineDataSet(Arrays.asList(new Entry(50f, 16f), new Entry(300f, 17f)), "b"));

        assertArrayEquals(new float[]{50f, 300f}, RespiratoryRateDailyFragment.getAxisBounds(data), 0f);
    }

    @Test
    public void expandsSingleTimestamp() {
        final LineData data = new LineData(new LineDataSet(Collections.singletonList(new Entry(120f, 14f)), "a"));

        assertArrayEquals(new float[]{119f, 121f}, RespiratoryRateDailyFragment.getAxisBounds(data), 0f);
    }

    @Test
    public void resetsEmptyDataToFullDay() {
        assertArrayEquals(new float[]{0f, 86400f},
                RespiratoryRateDailyFragment.getAxisBounds(new LineData()), 0f);
    }
}
