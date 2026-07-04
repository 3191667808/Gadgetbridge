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

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class SleepStage {
    private long startTs;
    private long endTs;
    private ActivityKind kind;

    public SleepStage(final long startTs, final long endTs, final ActivityKind kind) {
        this.startTs = startTs;
        this.endTs = endTs;
        this.kind = kind;
    }

    public SleepStage(final SleepStage other) {
        this(other.startTs, other.endTs, other.kind);
    }

    public long getStartTs() {
        return startTs;
    }

    public void setStartTs(final long startTs) {
        this.startTs = startTs;
    }

    public long getEndTs() {
        return endTs;
    }

    public void setEndTs(final long endTs) {
        this.endTs = endTs;
    }

    public ActivityKind getKind() {
        return kind;
    }

    public void setKind(final ActivityKind kind) {
        this.kind = kind;
    }

    public long getDurationSeconds() {
        return Math.max(0, endTs - startTs);
    }
}
