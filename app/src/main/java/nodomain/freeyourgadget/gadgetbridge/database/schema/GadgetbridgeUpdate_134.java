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
package nodomain.freeyourgadget.gadgetbridge.database.schema;

import android.database.sqlite.SQLiteDatabase;

import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;

public class GadgetbridgeUpdate_134 implements DBUpdateScript {
    @Override
    public void upgradeSchema(final SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS \"USER_SLEEP_SESSION\" (" +
                "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT ," +
                "\"DEVICE_ID\" INTEGER NOT NULL ," +
                "\"USER_ID\" INTEGER NOT NULL ," +
                "\"SOURCE_START_TS\" INTEGER NOT NULL ," +
                "\"SOURCE_END_TS\" INTEGER NOT NULL ," +
                "\"START_TS\" INTEGER NOT NULL ," +
                "\"END_TS\" INTEGER NOT NULL ," +
                "\"CREATED_AT\" INTEGER NOT NULL ," +
                "\"UPDATED_AT\" INTEGER NOT NULL );");

        db.execSQL("CREATE INDEX IF NOT EXISTS \"IDX_USER_SLEEP_SESSION_DEVICE_START\" " +
                "ON \"USER_SLEEP_SESSION\" (\"DEVICE_ID\" ASC, \"START_TS\" ASC);");

        db.execSQL("CREATE TABLE IF NOT EXISTS \"USER_SLEEP_STAGE\" (" +
                "\"_id\" INTEGER PRIMARY KEY AUTOINCREMENT ," +
                "\"SESSION_ID\" INTEGER NOT NULL ," +
                "\"START_TS\" INTEGER NOT NULL ," +
                "\"END_TS\" INTEGER NOT NULL ," +
                "\"ACTIVITY_KIND_CODE\" INTEGER NOT NULL );");

        db.execSQL("CREATE INDEX IF NOT EXISTS \"IDX_USER_SLEEP_STAGE_SESSION_START\" " +
                "ON \"USER_SLEEP_STAGE\" (\"SESSION_ID\" ASC, \"START_TS\" ASC);");
    }

    @Override
    public void downgradeSchema(final SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS \"USER_SLEEP_STAGE\"");
        db.execSQL("DROP TABLE IF EXISTS \"USER_SLEEP_SESSION\"");
    }
}
