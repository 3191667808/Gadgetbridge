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
package nodomain.freeyourgadget.gadgetbridge.database.schema;

import android.database.sqlite.SQLiteDatabase;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiWorkoutSummarySampleDao;

public class GadgetbridgeUpdate_129 implements DBUpdateScript {
    @Override
    public void upgradeSchema(final SQLiteDatabase db) {
        if (!DBHelper.existsColumn(HuaweiWorkoutSummarySampleDao.TABLENAME, HuaweiWorkoutSummarySampleDao.Properties.RawGpsFileLocation.columnName, db)) {
            final String statement = "ALTER TABLE " + HuaweiWorkoutSummarySampleDao.TABLENAME + " ADD COLUMN \""
                    + HuaweiWorkoutSummarySampleDao.Properties.RawGpsFileLocation.columnName + "\" TEXT";
            db.execSQL(statement);
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS \"WITHINGS_SCANWATCH_ACTIVITY_SAMPLE\" (" +
                "\"TIMESTAMP\" INTEGER NOT NULL," +
                "\"DEVICE_ID\" INTEGER NOT NULL," +
                "\"USER_ID\" INTEGER NOT NULL," +
                "\"DURATION\" INTEGER NOT NULL DEFAULT -1," +
                "\"RAW_KIND\" INTEGER NOT NULL DEFAULT -1," +
                "\"STEPS\" INTEGER NOT NULL DEFAULT -1," +
                "\"DISTANCE\" INTEGER NOT NULL DEFAULT -1," +
                "\"CALORIES\" INTEGER NOT NULL DEFAULT -1," +
                "\"HEART_RATE\" INTEGER NOT NULL DEFAULT -1," +
                "\"RAW_INTENSITY\" INTEGER NOT NULL DEFAULT -1," +
                "PRIMARY KEY (\"TIMESTAMP\", \"DEVICE_ID\"));");
    }

    @Override
    public void downgradeSchema(final SQLiteDatabase db) {
    }
}
