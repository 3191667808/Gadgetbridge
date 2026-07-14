/*  Copyright (C) 2025 Gideon Zenz

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
package nodomain.freeyourgadget.gadgetbridge.util.healthconnect

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import org.slf4j.LoggerFactory

private val LOG = LoggerFactory.getLogger(HealthConnectSyncWorker::class.java)

private const val NOTIFICATION_ID = 123

/**
 * Runs a Health Connect sync in the background.
 *
 * The worker's own coroutine scope owns the sync, so WorkManager cancelling this job cancels the
 * sync itself at the next chunk - there is no detached GlobalScope job left writing records after
 * the worker has been told to stop.
 */
class HealthConnectSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        LOG.info("Health Connect sync worker started")

        if (!HealthConnectSyncGate.isEnabled) {
            LOG.info("Health Connect is disabled, aborting sync.")
            return Result.failure()
        }

        // Best effort. On Android 12+ the system can refuse a foreground service start when the app
        // is in the background, and that refusal must not take the sync down with it - the sync is
        // the point, the notification is a courtesy.
        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            LOG.warn("Could not run the Health Connect sync in the foreground; syncing anyway.", e)
        }

        return try {
            withContext(Dispatchers.IO) { performSync() }
        } catch (e: Exception) {
            LOG.error("Health Connect sync worker failed", e)
            Result.failure()
        }
    }

    private suspend fun performSync(): Result {
        val deviceAddress = inputData.getString(INPUT_DEVICE_ADDRESS)
        if (!deviceAddress.isNullOrEmpty()) {
            LOG.info("SyncWorker: Syncing specific device: {}", deviceAddress)
        } else {
            LOG.info("SyncWorker: Syncing all selected devices")
        }

        val stats = HealthConnectManager.sync(applicationContext, deviceAddress) { summary, inProgress ->
            if (!inProgress) {
                val saved = GBApplication.getPrefs().preferences.edit()
                    .putString(GBPrefs.HEALTH_CONNECT_SYNC_STATUS, summary)
                    .commit()
                if (!saved) {
                    LOG.warn("Failed to save final sync status to SharedPreferences")
                }
            }
            setProgressAsync(Data.Builder().putString("progress", summary).build())
        }

        LOG.info("SyncWorker: HC data sync completed")

        // A quota rejection is not a data problem: the same records write fine once the quota
        // refills, so hand the job back to WorkManager rather than dropping it.
        return if (stats.rateLimited) Result.retry() else Result.success()
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val notification = NotificationCompat.Builder(context, GB.NOTIFICATION_CHANNEL_ID_HEALTH_CONNECT_SYNC)
            .setContentTitle(context.getString(R.string.health_connect_sync_notification_title))
            .setContentText(context.getString(R.string.health_connect_sync_notification_message))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val INPUT_DEVICE_ADDRESS: String = "device_address"
    }
}
