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
package nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.impl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.widget.Toast;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.entities.BaseActivitySummary;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.GPXActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.entities.GPXActivityPointDao;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySample;
import nodomain.freeyourgadget.gadgetbridge.export.ActivityTrackExporter;
import nodomain.freeyourgadget.gadgetbridge.export.GPXActivityPointExporter;
import nodomain.freeyourgadget.gadgetbridge.export.GPXExporter;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityKind;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityPoint;
import nodomain.freeyourgadget.gadgetbridge.model.ActivityTrack;
import nodomain.freeyourgadget.gadgetbridge.model.GPSCoordinate;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityParser;
import nodomain.freeyourgadget.gadgetbridge.util.DateTimeUtils;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class WorkoutGpsParser extends XiaomiActivityParser {
    private static final Logger LOG = LoggerFactory.getLogger(WorkoutGpsParser.class);

    @Override
    public boolean parse(final XiaomiSupport support, final XiaomiActivityFileId fileId, final byte[] bytes) {
        final int version = fileId.getVersion();
        final int headerSize;
        final int sampleSize;
        switch (version) {
            case 1:
                headerSize = 1;
                sampleSize = 12;
                break;
            case 2:
                headerSize = 1;
                sampleSize = 18;
                break;
            default:
                LOG.warn("Unable to parse workout gps version {}", fileId.getVersion());
                return false;
        }

        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.limit(buf.limit() - 4); // discard crc at the end
        buf.get(new byte[7]); // skip fileId bytes
        final byte fileIdPadding = buf.get();
        if (fileIdPadding != 0) {
            LOG.warn("Expected 0 padding after fileId, got {} - parsing might fail", fileIdPadding);
        }
        final byte[] header = new byte[headerSize];
        buf.get(header);

        LOG.debug("Workout gps header signature: {}", GB.hexdump(header));

        if ((buf.limit() - buf.position()) % sampleSize != 0) {
            LOG.warn("Remaining data in the buffer is not a multiple of {}", sampleSize);
        }

        final List<GPXActivityPoint> points = new ArrayList<>();

        try (DBHandler dbHandler = GBApplication.acquireDB()) {
            final DaoSession session = dbHandler.getDaoSession();
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);

            while (buf.position() < buf.limit()) {
                final int ts = buf.getInt();

                final GPXActivityPoint point = findOrCreateGpxActivityPoint(session, device, user, fileId, ts);

                point.setLongitude(buf.getFloat());
                point.setLatitude(buf.getFloat());
                if (version == 2) {
                    point.setHdop(buf.getFloat() / 4.8f);
                    point.setSpeed((buf.getShort() >> 2) / 36.0f); // [m/s]
                }
                points.add(point);
                LOG.trace("ActivityPoint v{}: ts={} lon={} lat={} hdop={} speed={}",
                        version,
                        point.getTimestamp(),
                        point.getLongitude(),
                        point.getLatitude(),
                        point.getHdop(),
                        point.getSpeed()
                    );
            }
            session.getGPXActivityPointDao().insertOrReplaceInTx(points);

            final GPXActivityPointExporter gpxe = new GPXActivityPointExporter(device, user, fileId);
            final BaseActivitySummary summary = findOrCreateBaseActivitySummary(session, device, user, fileId);
            gpxe.exportGPX(summary);

        } catch (final Exception e) {
            LOG.error("Error saving workout gps: {}", e);
            GB.toast(support.getContext(), "Error saving workout gps", Toast.LENGTH_LONG, GB.ERROR, e);
            return false;
        }
        return true;
    }
}
