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

/**
 * Neutral sleep-stage value object for the derived-metrics package.
 */
public final class SleepStage {
    public static final int TYPE_DEEP = 241;
    public static final int TYPE_LIGHT = 242;
    public static final int TYPE_REM = 243;
    public static final int TYPE_AWAKE = 244;

    public final int type;
    public final long startTimeMs;
    public final int durationSec;

    public SleepStage(final int type, final long startTimeMs, final int durationSec) {
        this.type = type;
        this.startTimeMs = startTimeMs;
        this.durationSec = durationSec;
    }
}
