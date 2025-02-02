package nodomain.freeyourgadget.gadgetbridge.export;

import java.io.File;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GPXActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.entities.GPXActivityPointDao;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;

public class GPXActivityPointExporter {
    private static final Logger LOG = LoggerFactory.getLogger(GPXActivityPointExporter.class);

    private final User user;
    private final Device device;
    private final long fileTimestamp;

    public GPXActivityPointExporter(Device device, User user, long fileTimestamp){
        this.user = user;
        this.device = device;
        this.fileTimestamp = fileTimestamp;
    }

    public GPXActivityPointExporter(Device device, User user, XiaomiActivityFileId fileId){
        this(device, user, fileId.getTimestamp().getTime());
    }

    public boolean hasGPXdata(){
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            final DaoSession session = dbHandler.getDaoSession();
            final GPXActivityPointDao gpxDao = session.getGPXActivityPointDao();
            final QueryBuilder<GPXActivityPoint> qb2 = gpxDao.queryBuilder()
                .where(
                    GPXActivityPointDao.Properties.FileTimestamp.eq(this.fileTimestamp),
                    GPXActivityPointDao.Properties.DeviceId.eq(this.device.getId()),
                    GPXActivityPointDao.Properties.UserId.eq(this.user.getId()),
                    GPXActivityPointDao.Properties.Latitude.isNotNull(),
                    GPXActivityPointDao.Properties.Longitude.isNotNull()
                );
            final long nr = qb2.count();
            if (nr > 0) {
                LOG.debug("{} GPX entries found", nr);
                return true;
            } else {
                LOG.debug("no GPX entries found");
                return false;
            }
        } catch (final Exception e) {
            LOG.error("Error while looking for GPX database entries: {}", e);
            return false;
        }
    }

    public void exportGPX(BaseActivitySummary summary) {
        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            final DaoSession session = dbHandler.getDaoSession();
            final GPXActivityPointDao gpxDao = session.getGPXActivityPointDao();
            final QueryBuilder<GPXActivityPoint> qb = gpxDao.queryBuilder()
                .where(
                    GPXActivityPointDao.Properties.FileTimestamp.eq(this.fileTimestamp),
                    GPXActivityPointDao.Properties.DeviceId.eq(this.device.getId()),
                    GPXActivityPointDao.Properties.UserId.eq(this.user.getId()),
                    GPXActivityPointDao.Properties.Latitude.isNotNull(),
                    GPXActivityPointDao.Properties.Longitude.isNotNull()
                ).orderAsc(GPXActivityPointDao.Properties.Timestamp);
            final List<GPXActivityPoint> points = qb.build().list();

            if (points.isEmpty()){
                LOG.error("Couldn't write GPX: No GPX points found.");
                return;
            }

            final ActivityTrack activityTrack = new ActivityTrack();

            for (final GPXActivityPoint point : points){

                final ActivityPoint ap = new ActivityPoint(new Date(point.getTimestamp() * 1000L));
                final GPSCoordinate gpsc = new GPSCoordinate(point.getLongitude(), point.getLatitude());
                
                if (point.getHdop() != null) {
                    gpsc.setHdop(point.getHdop());
                }
                ap.setLocation(gpsc);

                if (point.getHeartRate() != null) {
                    ap.setHeartRate(point.getHeartRate());
                }
                if (point.getSpeed() != null) {
                    ap.setSpeed(point.getSpeed());
                }
                if (point.getCadence() != null) {
                    ap.setCadence(point.getCadence());
                }
                activityTrack.addTrackPoint(ap);
            }

            // Set the info on the activity track
            activityTrack.setUser(user);
            activityTrack.setDevice(device);

            // Always export GPS data (The GPX files can be rewritten later if activity details are still missing)
            final GPXExporter exporter = new GPXExporter();

            final String gpxFileName = FileUtils.makeValidFileName("gadgetbridge-" + DateTimeUtils.formatIso8601(new Date(this.fileTimestamp)) + ".gpx");
            final File gpxTargetFile = new File(FileUtils.getExternalMediaDir(), gpxFileName);

            boolean exportGpxSuccess = true;
            try {
                exporter.performExport(activityTrack, gpxTargetFile);
            } catch (final ActivityTrackExporter.GPXTrackEmptyException ex) {
                exportGpxSuccess = false;
                LOG.warn("This activity does not contain GPX tracks.", ex);
            }
            if (exportGpxSuccess) {
                summary.setGpxTrack(gpxTargetFile.getAbsolutePath());
            }
            session.getBaseActivitySummaryDao().insertOrReplace(summary);

        } catch (final Exception e) {
            LOG.error("Error exporting GPX: {}", e);
        }
    }
}
