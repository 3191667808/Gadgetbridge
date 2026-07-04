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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;

public class CorrectedSleepSession {
    private final Long id;
    private final long deviceId;
    private final long userId;
    private final long sourceStartTs;
    private final long sourceEndTs;
    private final long startTs;
    private final long endTs;
    private final long createdAt;
    private final long updatedAt;
    private final boolean edited;
    private final List<SleepStage> stages = new ArrayList<>();

    public CorrectedSleepSession(final Long id,
                                 final long deviceId,
                                 final long userId,
                                 final long sourceStartTs,
                                 final long sourceEndTs,
                                 final long startTs,
                                 final long endTs,
                                 final long createdAt,
                                 final long updatedAt,
                                 final boolean edited,
                                 final List<SleepStage> stages) {
        this.id = id;
        this.deviceId = deviceId;
        this.userId = userId;
        this.sourceStartTs = sourceStartTs;
        this.sourceEndTs = sourceEndTs;
        this.startTs = startTs;
        this.endTs = endTs;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.edited = edited;
        if (stages != null) {
            for (SleepStage stage : stages) {
                this.stages.add(new SleepStage(stage));
            }
        }
    }

    public CorrectedSleepSession(final CorrectedSleepSession other) {
        this(other.id, other.deviceId, other.userId, other.sourceStartTs, other.sourceEndTs,
                other.startTs, other.endTs, other.createdAt, other.updatedAt, other.edited, other.stages);
    }

    public Long getId() {
        return id;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public long getUserId() {
        return userId;
    }

    public long getSourceStartTs() {
        return sourceStartTs;
    }

    public long getSourceEndTs() {
        return sourceEndTs;
    }

    public long getStartTs() {
        return startTs;
    }

    public long getEndTs() {
        return endTs;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean isEdited() {
        return edited;
    }

    public List<SleepStage> getStages() {
        return copyStages(stages);
    }

    public boolean hasStage(final ActivityKind kind) {
        for (SleepStage stage : stages) {
            if (stage.getKind() == kind) {
                return true;
            }
        }
        return false;
    }

    public long getDurationSeconds(final ActivityKind kind) {
        long total = 0;
        for (SleepStage stage : stages) {
            if (stage.getKind() == kind) {
                total += stage.getDurationSeconds();
            }
        }
        return total;
    }

    public long getTotalSleepSeconds() {
        return getDurationSeconds(ActivityKind.LIGHT_SLEEP)
                + getDurationSeconds(ActivityKind.DEEP_SLEEP)
                + getDurationSeconds(ActivityKind.REM_SLEEP);
    }

    public long getTotalAwakeSeconds() {
        return getDurationSeconds(ActivityKind.AWAKE_SLEEP);
    }

    private static List<SleepStage> copyStages(final List<SleepStage> stages) {
        final List<SleepStage> copies = new ArrayList<>(stages.size());
        for (SleepStage stage : stages) {
            copies.add(new SleepStage(stage));
        }
        return Collections.unmodifiableList(copies);
    }
}
