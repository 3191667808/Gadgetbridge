package nodomain.freeyourgadget.gadgetbridge.database.schema;

import android.database.sqlite.SQLiteDatabase;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;

public class GadgetbridgeUpdate_131 implements DBUpdateScript {
    @Override
    public void upgradeSchema(SQLiteDatabase db) {
        if (!DBHelper.existsColumn(BaseActivitySummaryDao.TABLENAME, BaseActivitySummaryDao.Properties.HasGps.columnName, db)) {
            db.execSQL(
                    "ALTER TABLE " + BaseActivitySummaryDao.TABLENAME + " ADD COLUMN "
                            + BaseActivitySummaryDao.Properties.HasGps.columnName + " BOOLEAN;"
            );
        }
    }

    @Override
    public void downgradeSchema(SQLiteDatabase db) {
    }
}
