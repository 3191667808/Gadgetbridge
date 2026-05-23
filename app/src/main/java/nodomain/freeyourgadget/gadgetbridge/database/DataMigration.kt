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

/**
 * Represents a long-running data migration that runs incrementally in the background after app
 * startup. Implementations must be idempotent and resumable - they will be called in a loop until
 * they report completion, and must be able to pick up where they left off if the process is killed.
 */
interface DataMigration {
    /**
     * Unique identifier for this migration, used to persist its completion state across restarts.
     */
    val id: String

    /**
     * Perform one batch of work for this migration.
     *
     * Implementations should process a fixed-size batch of rows and return quickly so that the
     * caller can check whether it should be stopped between batches. The [status] should be
     * updated to reflect current progress - it is persisted across executions.
     */
    fun migrate(context: Context, status: DataMigrationStatus)
}
