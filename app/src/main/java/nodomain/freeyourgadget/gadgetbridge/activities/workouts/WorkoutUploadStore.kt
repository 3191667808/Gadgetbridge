/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.activities.workouts

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUpload
import nodomain.freeyourgadget.gadgetbridge.entities.WorkoutUploadDao
import org.slf4j.LoggerFactory

/**
 * Persistence for the "already uploaded" state of workout summaries, backed by the
 * [WorkoutUpload] table (keyed by summary id + service). Replaces the earlier unbounded
 * `StringSet` preferences: rows are pruned with their summary and additionally carry the remote
 * activity id, so the upload status can be surfaced in the workout list and later edits re-synced.
 */
object WorkoutUploadStore {
    const val SERVICE_ENDURAIN = 0
    const val SERVICE_WANDERER = 1

    const val STATUS_SUCCESS = 1

    private val LOG = LoggerFactory.getLogger(WorkoutUploadStore::class.java)

    /** Summary ids already successfully uploaded to [service]. */
    fun uploadedSummaryIds(service: Int): Set<Long> {
        return try {
            GBApplication.acquireDbReadOnly().use { db ->
                db.daoSession.workoutUploadDao.queryBuilder()
                    .where(
                        WorkoutUploadDao.Properties.Service.eq(service),
                        WorkoutUploadDao.Properties.Status.eq(STATUS_SUCCESS)
                    )
                    .list()
                    .map { it.summaryId }
                    .toSet()
            }
        } catch (e: Exception) {
            LOG.error("Failed to read uploaded workout ids for service {}", service, e)
            emptySet()
        }
    }

    /** Records a successful upload of [summaryId] to [service], overwriting any prior row. */
    fun recordSuccess(summaryId: Long, service: Int, remoteActivityId: String?) {
        try {
            GBApplication.acquireDB().use { db ->
                db.daoSession.workoutUploadDao.insertOrReplace(
                    WorkoutUpload(
                        summaryId,
                        service,
                        remoteActivityId,
                        STATUS_SUCCESS,
                        System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            LOG.error("Failed to record upload of summary {} to service {}", summaryId, service, e)
        }
    }
}
