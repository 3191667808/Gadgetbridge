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
import java.util.List;

/**
 * Neutral sleep-session value object for the derived-metrics package.
 */
public final class SleepSession {
    public long startTimeMs;
    public long endTimeMs;
    public int  deepSleepCount;
    public int  lightSleepCount;
    public int  deepSleepSec;
    public int  lightSleepSec;
    public int  remSleepSec;
    public int  wakeCount;
    public int  wakeDurationSec;
    public final List<SleepStage> stages = new ArrayList<>();
}
