/*  Copyright (C) 2026 Rob Mutch

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

import java.util.Locale;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiSleepTimeSampleDao;

public class GadgetbridgeUpdate_134 implements DBUpdateScript {
    @Override
    public void upgradeSchema(final SQLiteDatabase db) {
        final String column = XiaomiSleepTimeSampleDao.Properties.IntoBedTime.columnName;
        if (!DBHelper.existsColumn(XiaomiSleepTimeSampleDao.TABLENAME, column, db)) {
            db.execSQL(String.format(
                    Locale.ROOT,
                    "ALTER TABLE %s ADD COLUMN \"%s\" INTEGER",
                    XiaomiSleepTimeSampleDao.TABLENAME,
                    column
            ));
        }
    }

    @Override
    public void downgradeSchema(final SQLiteDatabase db) {
    }
}
