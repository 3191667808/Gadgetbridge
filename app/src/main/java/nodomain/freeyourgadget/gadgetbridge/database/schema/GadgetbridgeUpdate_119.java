package nodomain.freeyourgadget.gadgetbridge.database.schema;

import android.database.sqlite.SQLiteDatabase;

import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.database.DBUpdateScript;
import nodomain.freeyourgadget.gadgetbridge.entities.NotificationFilterDao;

public class GadgetbridgeUpdate_119 implements DBUpdateScript {
    @Override
    public void upgradeSchema(SQLiteDatabase db) {
        if (!DBHelper.existsColumn(NotificationFilterDao.TABLENAME, NotificationFilterDao.Properties.NotificationFilterMessagePrivacyOverride.columnName, db)) {
            String ADD_PRIVACY_OVERRIDE_COLUMN_SQL = "ALTER TABLE " + NotificationFilterDao.TABLENAME + " ADD COLUMN "
                    + NotificationFilterDao.Properties.NotificationFilterMessagePrivacyOverride.columnName + " INTEGER NOT NULL DEFAULT 0;";
            db.execSQL(ADD_PRIVACY_OVERRIDE_COLUMN_SQL);
        }
    }

    @Override
    public void downgradeSchema(SQLiteDatabase db) {
    }
}