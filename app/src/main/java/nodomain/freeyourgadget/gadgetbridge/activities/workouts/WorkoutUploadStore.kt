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
import java.io.File
import java.security.MessageDigest

/**
 * Persistence for the "already uploaded" state of workout summaries, backed by the
 * [WorkoutUpload] table (keyed by summary id + service). Each row carries the remote activity
 * id so the upload status can be surfaced in the workout list and later edits re-synced; rows
 * are pruned together with their summary.
 */
object WorkoutUploadStore {
    const val SERVICE_ENDURAIN = 0
    const val SERVICE_WANDERER = 1

    const val STATUS_SUCCESS = 1

    private val LOG = LoggerFactory.getLogger(WorkoutUploadStore::class.java)

    /** Summary ids already successfully uploaded to [service], limited to id >= [minSummaryId]. */
    fun uploadedSummaryIds(service: Int, minSummaryId: Long): Set<Long> {
        return uploadedRows(service, minSummaryId).keys
    }

    /**
     * Successful-upload rows for [service], keyed by summary id. Exposes the stored remote
     * activity id and photo fingerprint so callers can detect a later photo change and re-sync it.
     *
     * Bounded to summary id >= [minSummaryId] so the query does not scan the whole table.
     */
    fun uploadedRows(service: Int, minSummaryId: Long): Map<Long, WorkoutUpload> {
        return try {
            GBApplication.acquireDbReadOnly().use { db ->
                db.daoSession.workoutUploadDao.queryBuilder()
                    .where(
                        WorkoutUploadDao.Properties.Service.eq(service),
                        WorkoutUploadDao.Properties.Status.eq(STATUS_SUCCESS),
                        WorkoutUploadDao.Properties.SummaryId.ge(minSummaryId)
                    )
                    .list()
                    .associateBy { it.summaryId }
            }
        } catch (e: Exception) {
            LOG.error("Failed to read uploaded workout rows for service {}", service, e)
            emptyMap()
        }
    }

    /**
     * Records a successful upload of [summaryId] to [service], overwriting any prior row.
     * [photoHash] is the fingerprint (see [photoHashOf]) of the header photo that was uploaded,
     * so a later change can be detected; null when the workout had no photo.
     */
    fun recordSuccess(summaryId: Long, service: Int, remoteActivityId: String?, photoHash: String?) {
        try {
            GBApplication.acquireDB().use { db ->
                db.daoSession.workoutUploadDao.insertOrReplace(
                    WorkoutUpload(
                        summaryId,
                        service,
                        remoteActivityId,
                        STATUS_SUCCESS,
                        System.currentTimeMillis(),
                        photoHash
                    )
                )
            }
        } catch (e: Exception) {
            LOG.error("Failed to record upload of summary {} to service {}", summaryId, service, e)
        }
    }

    /**
     * SHA-256 fingerprint (hex) of the file at [path], or null when [path] is blank/missing or the
     * file cannot be read. Used to detect whether a workout's header photo changed since its last
     * upload.
     */
    fun photoHashOf(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            LOG.warn("Failed to fingerprint photo {}", path, e)
            null
        }
    }
}
