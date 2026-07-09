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

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.EndurainTokenManager
import nodomain.freeyourgadget.gadgetbridge.activities.endurain.WandererTokenManager
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.util.ActivitySummaryUtils
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Date

/**
 * Uploads newly-synced workouts to Endurain (FIT) and/or Wanderer (GPX) after a device fetch.
 * Enqueued (debounced) by [nodomain.freeyourgadget.gadgetbridge.externalevents.NewDataReceiver]
 * when at least one auto-upload toggle is on.
 *
 * Only workouts from the recent window are considered, and each service keeps its own set of
 * already-uploaded summary IDs so a workout is never uploaded twice. On failure a notification is
 * posted whose text explains the reason (no internet / server unreachable / HTTP error).
 */
class WorkoutUploadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    private val LOG = LoggerFactory.getLogger(WorkoutUploadWorker::class.java)

    override fun doWork(): Result {
        val prefs = GBApplication.getPrefs()
        val endurainOn = prefs.getBoolean(GBPrefs.ENDURAIN_AUTO_UPLOAD_ENABLED, false)
        val wandererOn = prefs.getBoolean(GBPrefs.WANDERER_AUTO_UPLOAD_ENABLED, false)
        if (!endurainOn && !wandererOn) {
            return Result.success()
        }

        val address = inputData.getString(INPUT_DEVICE_ADDRESS)
        if (address.isNullOrBlank()) {
            LOG.warn("WorkoutUploadWorker started without a device address, skipping")
            return Result.success()
        }
        val gbDevice = GBApplication.app().deviceManager.getDeviceByAddress(address)
        if (gbDevice == null) {
            LOG.warn("WorkoutUploadWorker: device {} not found", address)
            return Result.success()
        }

        val context = applicationContext
        val summaries = queryRecentSummaries(gbDevice)
        if (summaries.isEmpty()) {
            return Result.success()
        }

        val endurainLoggedIn = endurainOn && EndurainTokenManager(context).isLoggedIn()
        val wandererLoggedIn = wandererOn && WandererTokenManager(context).isLoggedIn()
        if (!endurainLoggedIn && !wandererLoggedIn) {
            return Result.success()
        }

        val enduDone = if (endurainLoggedIn) {
            WorkoutUploadStore.uploadedSummaryIds(WorkoutUploadStore.SERVICE_ENDURAIN)
        } else {
            emptySet()
        }
        val wandDone = if (wandererLoggedIn) {
            WorkoutUploadStore.uploadedSummaryIds(WorkoutUploadStore.SERVICE_WANDERER)
        } else {
            emptySet()
        }

        val provider = gbDevice.deviceCoordinator.getActivityTrackProvider(gbDevice, context)
        var endurainFailure: String? = null
        var wandererFailure: String? = null

        for (summary in summaries) {
            val id = summary.id ?: continue

            if (endurainLoggedIn && !enduDone.contains(id)) {
                val result = uploadEndurainBlocking(context, gbDevice, summary)
                if (result.success) {
                    WorkoutUploadStore.recordSuccess(id, WorkoutUploadStore.SERVICE_ENDURAIN, result.remoteActivityId)
                } else {
                    endurainFailure = result.reason
                    LOG.warn("Auto-upload to Endurain failed for summary {}: {}", id, result.reason)
                }
            }

            if (wandererLoggedIn && !wandDone.contains(id)) {
                val gpx = provider?.let { ActivitySummaryUtils.getShareableGpxFile(it, summary) }
                if (gpx != null) {
                    val result = WorkoutUploader.uploadToWandererBlocking(context, gpx)
                    if (result.success) {
                        WorkoutUploadStore.recordSuccess(id, WorkoutUploadStore.SERVICE_WANDERER, result.remoteActivityId)
                    } else {
                        wandererFailure = result.reason
                        LOG.warn("Auto-upload to Wanderer failed for summary {}: {}", id, result.reason)
                    }
                }
            }
        }

        endurainFailure?.let {
            notifyFailure(context, NOTIFICATION_ID_ENDURAIN, context.getString(R.string.auto_upload_failed_endurain, it))
        }
        wandererFailure?.let {
            notifyFailure(context, NOTIFICATION_ID_WANDERER, context.getString(R.string.auto_upload_failed_wanderer, it))
        }

        return Result.success()
    }

    private fun queryRecentSummaries(gbDevice: GBDevice): List<BaseActivitySummary> {
        return try {
            GBApplication.acquireDbReadOnly().use { db ->
                val device = DBHelper.getDevice(gbDevice, db.daoSession) ?: return emptyList()
                val since = Date(System.currentTimeMillis() - RECENT_WINDOW_MS)
                db.daoSession.baseActivitySummaryDao.queryBuilder()
                    .where(
                        BaseActivitySummaryDao.Properties.DeviceId.eq(device.id),
                        BaseActivitySummaryDao.Properties.StartTime.ge(since)
                    )
                    .orderAsc(BaseActivitySummaryDao.Properties.StartTime)
                    .build()
                    .list()
            }
        } catch (e: Exception) {
            LOG.error("Error querying summaries for auto-upload", e)
            emptyList()
        }
    }

    private fun uploadEndurainBlocking(
        context: Context,
        gbDevice: GBDevice,
        summary: BaseActivitySummary
    ): WorkoutUploader.UploadResult {
        val fit: File = try {
            WorkoutUploader.buildFitFile(context, gbDevice, summary)
        } catch (e: Exception) {
            return WorkoutUploader.UploadResult(false, null, e.localizedMessage)
        }
        return WorkoutUploader.uploadToEndurainBlocking(context, summary, fit)
    }

    private fun notifyFailure(context: Context, notificationId: Int, text: String) {
        val notification = NotificationCompat.Builder(context, GB.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.auto_upload_failed_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()
        GB.notify(notificationId, notification, context)
    }

    companion object {
        const val INPUT_DEVICE_ADDRESS = "device_address"
        private const val RECENT_WINDOW_MS = 14L * 24 * 60 * 60 * 1000
        private const val NOTIFICATION_ID_ENDURAIN = 4711
        private const val NOTIFICATION_ID_WANDERER = 4712
    }
}
