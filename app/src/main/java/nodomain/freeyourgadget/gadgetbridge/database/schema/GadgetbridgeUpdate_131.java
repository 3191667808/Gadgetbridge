/*  Copyright (C) 2026 Ariel Saghiv

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
package nodomain.freeyourgadget.gadgetbridge.database.schema;

import android.database.sqlite.SQLiteDatabase;

import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;

/**
 * Schema bump to 131 for the new {@code VRING_R26_ACTIVITY_SAMPLE} entity.
 * <p>
 * The actual table creation is handled by {@code DaoMaster.createAllTables(db, ifNotExists=true)}
 * which runs before this script in {@link nodomain.freeyourgadget.gadgetbridge.database.DBOpenHelper#onUpgrade},
 * so nothing else is required here.
 */
public class GadgetbridgeUpdate_131 implements DBUpdateScript {
    @Override
    public void upgradeSchema(final SQLiteDatabase db) {
        // No-op: table is created by DaoMaster.createAllTables(...) on upgrade.
    }

    @Override
    public void downgradeSchema(final SQLiteDatabase db) {
        // No-op.
    }
}
