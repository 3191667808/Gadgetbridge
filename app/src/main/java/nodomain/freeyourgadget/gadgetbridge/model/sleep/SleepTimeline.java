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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.ActivitySample;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepSessionService.SleepTotals;

public class SleepTimeline {
    private final List<CorrectedSleepSession> sessions;
    private final List<CorrectedSleepSample> sleepSamples;
    private final List<ActivitySample> activitySamplesWithEditedSleep;
    private final List<ActivitySample> activitySamplesWithoutEditedSleep;
    private final List<CorrectedSleepSession> correctionRanges;
    private final SleepTotals totals;
    private final boolean hasCorrections;

    public SleepTimeline(@NonNull final List<CorrectedSleepSession> sessions,
                         @NonNull final List<CorrectedSleepSample> sleepSamples,
                         @NonNull final List<ActivitySample> activitySamplesWithEditedSleep,
                         @NonNull final List<ActivitySample> activitySamplesWithoutEditedSleep,
                         @NonNull final SleepTotals totals) {
        this(sessions, sleepSamples, activitySamplesWithEditedSleep, activitySamplesWithoutEditedSleep,
                totals, Collections.emptyList(), false);
    }

    public SleepTimeline(@NonNull final List<CorrectedSleepSession> sessions,
                         @NonNull final List<CorrectedSleepSample> sleepSamples,
                         @NonNull final List<ActivitySample> activitySamplesWithEditedSleep,
                         @NonNull final List<ActivitySample> activitySamplesWithoutEditedSleep,
                         @NonNull final SleepTotals totals,
                         final boolean hasCorrections) {
        this(sessions, sleepSamples, activitySamplesWithEditedSleep, activitySamplesWithoutEditedSleep,
                totals, Collections.emptyList(), hasCorrections);
    }

    public SleepTimeline(@NonNull final List<CorrectedSleepSession> sessions,
                         @NonNull final List<CorrectedSleepSample> sleepSamples,
                         @NonNull final List<ActivitySample> activitySamplesWithEditedSleep,
                         @NonNull final List<ActivitySample> activitySamplesWithoutEditedSleep,
                         @NonNull final SleepTotals totals,
                         @NonNull final List<CorrectedSleepSession> correctionRanges,
                         final boolean hasCorrections) {
        this.sessions = copySessions(sessions);
        this.sleepSamples = Collections.unmodifiableList(new ArrayList<>(sleepSamples));
        this.activitySamplesWithEditedSleep = Collections.unmodifiableList(new ArrayList<>(activitySamplesWithEditedSleep));
        this.activitySamplesWithoutEditedSleep = Collections.unmodifiableList(new ArrayList<>(activitySamplesWithoutEditedSleep));
        this.correctionRanges = copySessions(correctionRanges);
        this.totals = totals;
        this.hasCorrections = hasCorrections;
    }

    @NonNull
    public List<CorrectedSleepSession> getSessions() {
        return sessions;
    }

    @NonNull
    public List<CorrectedSleepSample> getSleepSamples() {
        return sleepSamples;
    }

    @NonNull
    public List<ActivitySample> getActivitySamplesWithEditedSleep() {
        return activitySamplesWithEditedSleep;
    }

    @NonNull
    public List<ActivitySample> getActivitySamplesWithoutEditedSleep() {
        return activitySamplesWithoutEditedSleep;
    }

    @NonNull
    public List<CorrectedSleepSession> getCorrectionRanges() {
        return correctionRanges;
    }

    @NonNull
    public SleepTotals getTotals() {
        return totals;
    }

    public boolean hasCorrections() {
        return hasCorrections;
    }

    @NonNull
    private static List<CorrectedSleepSession> copySessions(@NonNull final List<CorrectedSleepSession> sessions) {
        final List<CorrectedSleepSession> copies = new ArrayList<>(sessions.size());
        for (CorrectedSleepSession session : sessions) {
            copies.add(new CorrectedSleepSession(session));
        }
        return Collections.unmodifiableList(copies);
    }
}
