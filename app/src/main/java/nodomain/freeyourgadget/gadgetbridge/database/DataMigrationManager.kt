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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import nodomain.freeyourgadget.gadgetbridge.database.migrations.BaseActivitySummaryGpsVo2Migration

/**
 * Registry of all [DataMigration]s and entry-point to schedule their background execution.
 *
 * Add new migrations to [migrations] in the order they should run. Each migration is only
 * executed once; completion is tracked in the [PREFS_NAME] shared preferences file.
 */
object DataMigrationManager {
    private const val WORK_NAME = "DataMigration"

    const val PREFS_NAME = "data_migration"
    const val PREFS_KEY_DONE_PREFIX = "done_"
    const val PREFS_KEY_POSITION_PREFIX = "position_"

    val migrations: List<DataMigration> = listOf(
        BaseActivitySummaryGpsVo2Migration(),
    )

    /**
     * Enqueue the [DataMigrationWorker] as a unique one-time task. If a task is already pending
     * or running it is left unchanged ([ExistingWorkPolicy.KEEP]).
     */
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<DataMigrationWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
