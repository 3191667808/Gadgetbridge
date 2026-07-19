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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SleepBreathingQualityAssessmentTest {
    @Test
    public void usesOfficialAssessmentBands() {
        assertEquals(100, SleepDailyFragment.SleepBreathingQualityAssessment.fromAverageProbability(0).getScore());
        assertEquals(61, SleepDailyFragment.SleepBreathingQualityAssessment.fromAverageProbability(49.5).getScore());
        assertEquals(31, SleepDailyFragment.SleepBreathingQualityAssessment.fromAverageProbability(87.5).getScore());
        assertEquals(0, SleepDailyFragment.SleepBreathingQualityAssessment.fromAverageProbability(127).getScore());
    }

    @Test
    public void capturedAverageProducesOptimalEstimate() {
        assertEquals(79, SleepDailyFragment.SleepBreathingQualityAssessment.fromAverageProbability(26.35).getScore());
    }
}
