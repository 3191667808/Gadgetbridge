/*  Copyright (C) 2026 José Rebelo

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
package nodomain.freeyourgadget.gadgetbridge.database

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.notifications.GBProgressNotification
import org.slf4j.LoggerFactory
import androidx.core.content.edit

/**
 * Runs all pending [DataMigration]s registered in [DataMigrationManager], one batch at a time,
 * showing a progress notification to the user.
 *
 * Each migration is run to completion before moving to the next. If the worker is stopped mid-way
 * it will be retried; individual migrations are responsible for resuming from where they left off.
 */
class DataMigrationWorker(
    private val context: Context,
    workerParams: WorkerParameters,
) : Worker(context, workerParams) {
    override fun doWork(): Result {
        val prefs = context.getSharedPreferences(DataMigrationManager.PREFS_NAME, Context.MODE_PRIVATE)

        val pendingMigrations = DataMigrationManager.migrations.filter { migration ->
            !prefs.getBoolean(DataMigrationManager.PREFS_KEY_DONE_PREFIX + migration.id, false)
        }

        if (pendingMigrations.isEmpty()) {
            LOG.debug("No pending data migrations")
            return Result.success()
        }

        LOG.info("There are {} pending data migration(s)", pendingMigrations.size)

        val notification = GBProgressNotification(context, GB.NOTIFICATION_CHANNEL_ID_DATA_MIGRATION)
        notification.start(R.string.data_migration_notification_title, 0)

        try {
            for (migration in pendingMigrations) {
                if (isStopped) {
                    LOG.warn("Data migration worker stopped before completing all migrations")
                    notification.finish()
                    return Result.retry()
                }

                val status = DataMigrationStatus(
                    done = false,
                    processed = 0,
                    total = 0,
                    position = prefs.getLong(DataMigrationManager.PREFS_KEY_POSITION_PREFIX + migration.id, -1)
                )

                LOG.info("Starting data migration: {}, existing position = {}", migration.id, status.position)

                val startTime = System.currentTimeMillis()

                while (!status.done && !isStopped) {
                    migration.migrate(context, status)
                    notification.setTotal(status.processed, status.total)
                }

                val stopTime = System.currentTimeMillis()
                val totalTime = stopTime - startTime

                if (status.done) {
                    prefs.edit {
                        putBoolean(DataMigrationManager.PREFS_KEY_DONE_PREFIX + migration.id, true)
                    }
                    LOG.info("Data migration {} completed after {}ms", migration.id, totalTime)
                } else {
                    LOG.warn("Data migration {} interrupted after {}ms", migration.id, totalTime)
                    notification.finish()
                    return Result.retry()
                }
            }
        } catch (e: Exception) {
            LOG.error("Error during data migration", e)
            notification.finish()
            return Result.failure()
        }

        notification.finish()
        return Result.success()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(DataMigrationWorker::class.java)
    }
}
