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
package nodomain.freeyourgadget.gadgetbridge.util.sleep;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.CorrectedSleepSession;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepCorrectionChange;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepCorrectionRequest;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepSessionService;
import nodomain.freeyourgadget.gadgetbridge.util.healthconnect.HealthConnectSleepSyncInvalidator;

public final class SleepCorrectionWriter {
    private SleepCorrectionWriter() {
    }

    @NonNull
    public static CorrectedSleepSession saveCorrection(@NonNull final DaoSession daoSession,
                                                       @NonNull final GBDevice gbDevice,
                                                       @NonNull final SleepCorrectionRequest requestedSession) {
        final SleepCorrectionChange change = SleepSessionService.saveCorrection(
                daoSession,
                gbDevice,
                requestedSession,
                correctionChange -> HealthConnectSleepSyncInvalidator.rewindSleepSync(daoSession, correctionChange)
        );

        final CorrectedSleepSession savedSession = change.getSession();
        if (savedSession == null) {
            throw new IllegalStateException("Saved sleep correction did not return a session");
        }
        return savedSession;
    }

    public static void deleteCorrection(@NonNull final DaoSession daoSession,
                                        @Nullable final Long sessionId) {
        SleepSessionService.deleteCorrection(
                daoSession,
                sessionId,
                correctionChange -> HealthConnectSleepSyncInvalidator.rewindSleepSync(daoSession, correctionChange)
        );
    }
}
