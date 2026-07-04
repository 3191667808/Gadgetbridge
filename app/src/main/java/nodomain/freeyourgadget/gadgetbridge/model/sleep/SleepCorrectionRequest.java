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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SleepCorrectionRequest {
    private final Long id;
    private final long sourceStartTs;
    private final long sourceEndTs;
    private final long startTs;
    private final long endTs;
    private final long createdAt;
    private final List<SleepStage> stages;

    public SleepCorrectionRequest(@Nullable final Long id,
                                  final long sourceStartTs,
                                  final long sourceEndTs,
                                  final long startTs,
                                  final long endTs,
                                  final long createdAt,
                                  @NonNull final List<SleepStage> stages) {
        this.id = id;
        this.sourceStartTs = sourceStartTs;
        this.sourceEndTs = sourceEndTs;
        this.startTs = startTs;
        this.endTs = endTs;
        this.createdAt = createdAt;
        final List<SleepStage> stageCopies = new ArrayList<>(stages.size());
        for (SleepStage stage : stages) {
            stageCopies.add(new SleepStage(stage));
        }
        this.stages = Collections.unmodifiableList(stageCopies);
    }

    @NonNull
    public static SleepCorrectionRequest fromSession(@NonNull final CorrectedSleepSession session) {
        return fromSession(session, session.getStartTs(), session.getEndTs(), session.getStages());
    }

    @NonNull
    public static SleepCorrectionRequest fromSession(@NonNull final CorrectedSleepSession session,
                                                     final long startTs,
                                                     final long endTs,
                                                     @NonNull final List<SleepStage> stages) {
        return new SleepCorrectionRequest(
                session.getId(),
                session.getSourceStartTs(),
                session.getSourceEndTs(),
                startTs,
                endTs,
                session.getCreatedAt(),
                stages
        );
    }

    @Nullable
    public Long getId() {
        return id;
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

    @NonNull
    public List<SleepStage> getStages() {
        final List<SleepStage> copies = new ArrayList<>(stages.size());
        for (SleepStage stage : stages) {
            copies.add(new SleepStage(stage));
        }
        return Collections.unmodifiableList(copies);
    }
}
