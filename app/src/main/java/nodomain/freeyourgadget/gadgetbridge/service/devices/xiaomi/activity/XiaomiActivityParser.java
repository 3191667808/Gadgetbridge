/*  Copyright (C) 2023-2025 José Rebelo, Martin Schitter

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity;

import androidx.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummaryDao;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GPXActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.entities.GPXActivityPointDao;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.export.GPXActivityPointExporter;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.ManualSamplesParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.DailyDetailsParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.DailySummaryParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.SleepDetailsParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.SleepStagesParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.WorkoutGpsParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.WorkoutSummaryParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl.WorkoutDetailsParser;

public abstract class XiaomiActivityParser {
    private static final Logger LOG = LoggerFactory.getLogger(XiaomiActivityParser.class);

    public abstract boolean parse(final XiaomiSupport support, final XiaomiActivityFileId fileId, final byte[] bytes);

    protected BaseActivitySummary findOrCreateBaseActivitySummary(final DaoSession session,
                                                                  final Device device,
                                                                  final User user,
                                                                  final XiaomiActivityFileId fileId) {
        final BaseActivitySummaryDao summaryDao = session.getBaseActivitySummaryDao();
        final QueryBuilder<BaseActivitySummary> qb = summaryDao.queryBuilder()
            .where(
                BaseActivitySummaryDao.Properties.StartTime.eq(fileId.getTimestamp()),
                BaseActivitySummaryDao.Properties.DeviceId.eq(device.getId()),
                BaseActivitySummaryDao.Properties.UserId.eq(user.getId())
            );
        final List<BaseActivitySummary> summaries = qb.build().list();
        if (summaries.isEmpty()) {
            final BaseActivitySummary summary = new BaseActivitySummary();
            summary.setStartTime(fileId.getTimestamp());
            summary.setDevice(device);
            summary.setUser(user);

            // These will be set later, once we parse the summary
            summary.setEndTime(fileId.getTimestamp());
            summary.setActivityKind(ActivityKind.UNKNOWN.getCode());

            return summary;
        }
        if (summaries.size() > 1) {
            LOG.warn("Found multiple summaries for {}", fileId);
        }
        return summaries.get(0);
    }

    protected GPXActivityPoint findOrCreateGpxActivityPoint(final DaoSession session,
                                                            final Device device,
                                                            final User user,
                                                            final XiaomiActivityFileId fileId,
                                                            final int timestamp){
        final GPXActivityPointDao gpxDao = session.getGPXActivityPointDao();
        final QueryBuilder<GPXActivityPoint> qb = gpxDao.queryBuilder()
            .where(
                GPXActivityPointDao.Properties.Timestamp.eq(timestamp),
                GPXActivityPointDao.Properties.FileTimestamp.eq(fileId.getTimestamp().getTime()),
                GPXActivityPointDao.Properties.DeviceId.eq(device.getId()),
                GPXActivityPointDao.Properties.UserId.eq(user.getId())
            );
        final List<GPXActivityPoint> points = qb.build().list();
        if (points.isEmpty()){
            final GPXActivityPoint point = new GPXActivityPoint();
            point.setFileTimestamp(fileId.getTimestamp().getTime());
            point.setTimestamp(timestamp);
            point.setUserId(user.getId());
            point.setDeviceId(device.getId());
            return point;
        }
        if (points.size() > 1) {
            LOG.warn("Found multiple GPXActivityPoints for ts={} {}", timestamp, fileId);
        }
        return points.get(0);
    }

    /**
     * Rewrite GPX export if new details or summary information arrived
     * later than the GPS data.
     */
    protected void GPXRewrite(final DaoSession session, final Device device, final User user, final XiaomiActivityFileId fileId) {

        final BaseActivitySummary summary = findOrCreateBaseActivitySummary(session, device, user, fileId);

        // check for already parsed gps data
        GPXActivityPointExporter gpxe = new GPXActivityPointExporter(device, user, fileId);
        if (! gpxe.hasGPXdata()) {
            LOG.debug("GPS parsing is still pending.");
            return;
        }
        LOG.debug("Rewrite GPX export. {}", fileId);
        gpxe.exportGPX(summary);
    }

    @Nullable
    public static XiaomiActivityParser create(final XiaomiActivityFileId fileId) {
        switch (fileId.getType()) {
            case ACTIVITY:
                return createForActivity(fileId);
            case SPORTS:
                return createForSports(fileId);
        }

        LOG.warn("Unknown file type for {}", fileId);
        return null;
    }

    private static XiaomiActivityParser createForActivity(final XiaomiActivityFileId fileId) {
        assert fileId.getType() == XiaomiActivityFileId.Type.ACTIVITY;

        switch (fileId.getSubtype()) {
            case ACTIVITY_DAILY:
                if (fileId.getDetailType() == XiaomiActivityFileId.DetailType.DETAILS) {
                    return new DailyDetailsParser();
                }
                if (fileId.getDetailType() == XiaomiActivityFileId.DetailType.SUMMARY) {
                    return new DailySummaryParser();
                }

                break;
            case ACTIVITY_SLEEP_STAGES:
                if (fileId.getDetailType() == XiaomiActivityFileId.DetailType.DETAILS) {
                    return new SleepStagesParser();
                }

                break;
            case ACTIVITY_MANUAL_SAMPLES:
                if (fileId.getDetailType() == XiaomiActivityFileId.DetailType.DETAILS) {
                    return new ManualSamplesParser();
                }

                break;
            case ACTIVITY_SLEEP:
                return new SleepDetailsParser();
        }

        return null;
    }

    private static XiaomiActivityParser createForSports(final XiaomiActivityFileId fileId) {
        assert fileId.getType() == XiaomiActivityFileId.Type.SPORTS;

        switch (fileId.getDetailType()) {
            case SUMMARY:
                return new WorkoutSummaryParser();
            case GPS_TRACK:
                return new WorkoutGpsParser();
            case DETAILS:
                return new WorkoutDetailsParser();
        }

        return null;
    }
}
