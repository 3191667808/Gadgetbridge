package nodomain.freeyourgadget.gadgetbridge.prefs.migrators;

import static nodomain.freeyourgadget.gadgetbridge.model.DeviceType.MIBAND2;

import android.content.SharedPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;
import nodomain.freeyourgadget.gadgetbridge.prefs.AbstractPreferenceMigrator;

public class PreferenceMigrator59 extends AbstractPreferenceMigrator {
    private static final Logger LOG = LoggerFactory.getLogger(PreferenceMigrator59.class);

    @Override
    public void migrate(final int oldVersion, final SharedPreferences sharedPrefs, final SharedPreferences.Editor editor) {
        try (DBHandler db = GBApplication.acquireDB()) {
            final DaoSession daoSession = db.getDaoSession();
            final List<Device> activeDevices = DBHelper.getActiveDevices(daoSession);

            for (Device dbDevice : activeDevices) {
                // #6070 - Migrate Casio GBD-200 to the new implementation
                if ("CASIOGBX100".equals(dbDevice.getTypeName())) {
                    final String name = dbDevice.getName();
                    if (name != null && name.startsWith("CASIO") && name.contains("GBD-200")) {
                        dbDevice.setTypeName(DeviceType.CASIOGBD200.name());
                        daoSession.getDeviceDao().update(dbDevice);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to migrate prefs to version 59", e);
        }
    }
}
