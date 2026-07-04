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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect;

import androidx.annotation.NonNull;

import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSyncState;
import nodomain.freeyourgadget.gadgetbridge.entities.HealthConnectSyncStateDao;
import nodomain.freeyourgadget.gadgetbridge.model.sleep.SleepCorrectionChange;

public final class HealthConnectSleepSyncInvalidator {
    private static final String SLEEP_DATA_TYPE = HealthConnectPermissionManager.HealthConnectDataType.SLEEP.name();

    private HealthConnectSleepSyncInvalidator() {
    }

    public static void rewindSleepSync(@NonNull final DaoSession daoSession,
                                       @NonNull final SleepCorrectionChange change) {
        if (!change.shouldRewind()) {
            return;
        }

        final QueryBuilder<HealthConnectSyncState> query = daoSession.getHealthConnectSyncStateDao().queryBuilder();
        query.where(
                HealthConnectSyncStateDao.Properties.DeviceId.eq(change.getDeviceId()),
                HealthConnectSyncStateDao.Properties.DataType.eq(SLEEP_DATA_TYPE)
        );
        final HealthConnectSyncState syncState = query.unique();
        if (syncState != null && syncState.getLastSyncTimestamp() > change.getRewindStartTs()) {
            syncState.setLastSyncTimestamp(change.getRewindStartTs());
            daoSession.getHealthConnectSyncStateDao().insertOrReplace(syncState);
        }
    }
}
