/*  Copyright (C) 2025 José Rebelo, Martin Schitter

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

import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.entities.User;
import nodomain.freeyourgadget.gadgetbridge.entities.XiaomiActivitySample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.devices.SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.XiaomiSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityFileId;
import nodomain.freeyourgadget.gadgetbridge.service.devices.xiaomi.activity.XiaomiActivityParser;
import nodomain.freeyourgadget.gadgetbridge.util.FileUtils;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class WorkoutDetailsParser extends XiaomiActivityParser {
    private static final Logger LOG = LoggerFactory.getLogger(WorkoutDetailsParser.class);

    @Override
    public boolean parse(final XiaomiSupport support, final XiaomiActivityFileId fileId, final byte[] bytes) {
        final int version = fileId.getVersion();
        final int segmentHeaderSize;
        final int recordSize;
        final int tsPosition;
        final byte[] expectedSignature;

        HexFormat hexFormat = HexFormat.of();
        
        LOG.debug("Parse workout details: {}", fileId.getFilename());

        switch (version){
            case 2:
                expectedSignature = hexFormat.parseHex("c0");
                segmentHeaderSize = 9;
                recordSize = 2;
                tsPosition = 4; // Position of timestamp in segment header
                break;
            case 3:
                expectedSignature = hexFormat.parseHex("ccccc0");
                segmentHeaderSize = 17;
                recordSize = 6;
                tsPosition = 8;
                break;
            case 5:
                expectedSignature = hexFormat.parseHex("ecccc00cc0");
                segmentHeaderSize = 17;
                recordSize = 8;
                tsPosition = 8;
                break;
            default:
                LOG.warn("Unable to parse workout details version {}", fileId.getVersion());
                return false;
        }

        // check signature compatibility
        final byte[] signature = Arrays.copyOfRange(bytes, 8, 8 + expectedSignature.length);
        if (Arrays.compare(expectedSignature, signature) != 0){
            LOG.warn("Unsupported signature: {} version: {}", hexFormat.formatHex(signature), version);
            return false;
        }

        // position of field with number of records in segment header
        final int nrPosition = tsPosition - 4;

        final ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.limit(buf.limit() - 4); // discard crc at the end

        // FileId (8 bytes)
        buf.get(new byte[7]); // skip fileId bytes
        final byte fileIdPadding = buf.get();
        if (fileIdPadding != 0) {
            LOG.warn("Expected 0 padding after fileId, got {} - parsing might fail", fileIdPadding);
        }

        // skip Record Signature
        buf.get(new byte[expectedSignature.length]);

        final List<XiaomiActivitySample> samples = new ArrayList<>();

        // loop over segments
        while (buf.position() < buf.limit()) {

            final byte[] segmentHeaderArray = new byte[segmentHeaderSize];
            buf.get(segmentHeaderArray);
            final ByteBuffer segmentHeader = ByteBuffer.wrap(segmentHeaderArray).order(ByteOrder.LITTLE_ENDIAN);
            int nr = segmentHeader.getInt(nrPosition);
            int ts = segmentHeader.getInt(tsPosition);

            final int segmentEnd = buf.position() + nr * recordSize;

            LOG.debug("Parse segment of {} entries", nr);

            // loop over records
            while (buf.position() < segmentEnd) {

                final XiaomiActivitySample sample = new XiaomiActivitySample();
                sample.setTimestamp(ts);

                switch (version) {
                    case 2:
                        sample.setHeartRate((int) buf.get() & 0xff);
                        buf.get(); // calories
                        break;
                    case 3:
                        buf.get(); // calories ( / 16)
                        sample.setHeartRate((int) buf.get() & 0xff);
                        buf.getInt(); // speed ( / (2*16 * 10))
                        break;
                    case 5:
                        buf.get(); // steps
                        sample.setHeartRate((int) buf.get() & 0xff);
                        buf.get(); // events
                        buf.get(); // calories
                        buf.get(); // spo2 (the offset of isn't constant, but often around 11)
                        buf.get(); // cadence
                        buf.getShort(); // pace
                        break;
                }

                samples.add(sample);

                LOG.trace("XiaomiActivitySample: ts={} hr={}",
                        sample.getTimestamp(),
                        sample.getHeartRate()
                );
                ts++;
            }
        }

       try (DBHandler dbHandler = GBApplication.acquireDB()) {
            final DaoSession session = dbHandler.getDaoSession();
            final GBDevice gbDevice = support.getDevice();
            final DeviceCoordinator coordinator = gbDevice.getDeviceCoordinator();
            final SampleProvider<XiaomiActivitySample> sampleProvider = (SampleProvider<XiaomiActivitySample>)  coordinator.getSampleProvider(gbDevice, session);
            final Device device = DBHelper.getDevice(support.getDevice(), session);
            final User user = DBHelper.getUser(session);

            LOG.debug("Write {} workout detail samples to db", samples.size());
            for (final XiaomiActivitySample sample : samples) {
               sample.setDevice(device);
               sample.setUser(user);
               sample.setProvider(sampleProvider);
            }
            sampleProvider.addGBActivitySamples(samples.toArray(new XiaomiActivitySample[0]));

           return true;
        } catch (final Exception e) {
            LOG.error("Error saving workout details: {}", e);
            return false;
        }
    }
}
