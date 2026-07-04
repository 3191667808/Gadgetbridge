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

public class SleepCorrectionChange {
    private static final SleepCorrectionChange NONE = new SleepCorrectionChange(null, 0, 0);

    private final CorrectedSleepSession session;
    private final long deviceId;
    private final long rewindStartTs;

    private SleepCorrectionChange(@Nullable final CorrectedSleepSession session,
                                  final long deviceId,
                                  final long rewindStartTs) {
        this.session = session;
        this.deviceId = deviceId;
        this.rewindStartTs = rewindStartTs;
    }

    @NonNull
    public static SleepCorrectionChange none() {
        return NONE;
    }

    @NonNull
    public static SleepCorrectionChange saved(@NonNull final CorrectedSleepSession session,
                                              final long rewindStartTs) {
        return new SleepCorrectionChange(session, session.getDeviceId(), rewindStartTs);
    }

    @NonNull
    public static SleepCorrectionChange deleted(final long deviceId,
                                                final long rewindStartTs) {
        return new SleepCorrectionChange(null, deviceId, rewindStartTs);
    }

    @Nullable
    public CorrectedSleepSession getSession() {
        return session;
    }

    public long getDeviceId() {
        return deviceId;
    }

    public long getRewindStartTs() {
        return rewindStartTs;
    }

    public boolean shouldRewind() {
        return deviceId > 0 && rewindStartTs > 0;
    }
}
