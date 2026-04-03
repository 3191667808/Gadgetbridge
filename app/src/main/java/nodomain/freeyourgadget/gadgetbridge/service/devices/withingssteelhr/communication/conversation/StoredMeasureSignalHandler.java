/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.GenericSpo2SampleProvider;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.GenericSpo2Sample;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.StoredMeasureData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.StoredMeasureMeta;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.StoredSignalMeta;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WithingsStructure;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.Message;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;

public class StoredMeasureSignalHandler implements ResponseHandler {
    private static final Logger logger = LoggerFactory.getLogger(StoredMeasureSignalHandler.class);
    private static final int MEASUREMENT_TYPE_SPO2 = 54;
    private static final int MAX_DELETE_ATTEMPTS = 32;
    private static final int MAX_REPEATED_PAGE_RETRIES = 4;

    private final WithingsBaseDeviceSupport support;
    private final GBDevice device;
    private final int signalType;
    private int pendingPageCursor = -1;
    private int pendingPageSignalFlags = 0;
    private boolean pendingSawSignalMeta = false;
    private boolean pendingSawStoredData = false;
    private long pendingLastSampleTimestampMs = -1;
    private int pendingLastSampleSpo2 = -1;
    private int lastDeletedCursor = -1;
    private int lastDeletedSignalFlags = -1;
    private long lastDeletedTimestampMs = -1;
    private int lastDeletedSpo2 = -1;
    private int repeatedPageRetries;
    private boolean forceSequentialCursor = false;

    public StoredMeasureSignalHandler(final WithingsBaseDeviceSupport support, final GBDevice device, final int signalType) {
        this.support = support;
        this.device = device;
        this.signalType = signalType;
    }

    @Override
    public void handleResponse(final Message response) {
        StoredMeasureMeta currentMeta = null;
        boolean sawAnyStoredData = false;
        boolean sawSignalMetaThisPage = false;
        int pageCursor = -1;

        // Collect all valid SpO2 samples from this response; there may be multiple
        // (StoredMeasureMeta, StoredMeasureData) pairs in a single BLE message.
        final List<long[]> collectedSamples = new ArrayList<>(); // [timestampMs, spo2]

        for (final WithingsStructure structure : response.getDataStructures()) {
            if (structure instanceof StoredSignalMeta) {
                final StoredSignalMeta storedSignalMeta = (StoredSignalMeta) structure;
                if (storedSignalMeta.getSignalType() == signalType && storedSignalMeta.getCursor() >= 0) {
                    sawSignalMetaThisPage = true;
                    pageCursor = storedSignalMeta.getCursor();
                    pendingPageSignalFlags = storedSignalMeta.getSignalFlags();
                }
                continue;
            }

            if (structure instanceof StoredMeasureMeta) {
                currentMeta = (StoredMeasureMeta) structure;
                sawAnyStoredData = true;
                continue;
            }

            if (!(structure instanceof StoredMeasureData)) {
                continue;
            }

            final StoredMeasureData data = (StoredMeasureData) structure;
            sawAnyStoredData = true;

            if (currentMeta == null) {
                logger.debug("Skipping stored measure data without meta: spo2Percent={} hasEOT={}", data.getSpo2Percent(), support.hasEndOfTransmission(response));
                continue;
            }

            if (!isLikelySpo2Measurement(currentMeta, data)) {
                logger.debug(
                        "Skipping stored measure sample: metaType={} dataType={} spo2Percent={} hasEOT={}",
                        currentMeta.getMeasurementType(),
                        data.getMeasurementType(),
                        data.getSpo2Percent(),
                        support.hasEndOfTransmission(response)
                );
                continue;
            }

            final int spo2 = data.getSpo2Percent();
            if (spo2 < 1 || spo2 > 100) {
                continue;
            }

            final long timestampMs = currentMeta.getTimestampMs();
            if (timestampMs <= 0) {
                continue;
            }

            logger.debug("Collected Withings SpO2 sample: ts={} spo2={} metaType={} dataType={}",
                    timestampMs, spo2, currentMeta.getMeasurementType(), data.getMeasurementType());
            collectedSamples.add(new long[]{timestampMs, spo2});
            pendingLastSampleTimestampMs = timestampMs;
            pendingLastSampleSpo2 = spo2;
        }

        if (!collectedSamples.isEmpty()) {
            try (DBHandler dbHandler = GBApplication.acquireDB()) {
                final Long userId = DBHelper.getUser(dbHandler.getDaoSession()).getId();
                final Long deviceId = DBHelper.getDevice(device, dbHandler.getDaoSession()).getId();
                final GenericSpo2SampleProvider provider = new GenericSpo2SampleProvider(device, dbHandler.getDaoSession());
                final List<GenericSpo2Sample> samples = new ArrayList<>(collectedSamples.size());
                for (final long[] entry : collectedSamples) {
                    samples.add(new GenericSpo2Sample(entry[0], deviceId, userId, (int) entry[1]));
                }
                provider.addSamples(samples);
                logger.debug("Stored {} Withings SpO2 sample(s)", samples.size());
            } catch (final Exception ex) {
                logger.warn("Failed storing Withings SpO2 samples", ex);
            }
        }

        if (!sawAnyStoredData) {
            logger.debug(
                    "Skipping stored measure response: hasMetaOrData=false hasEOT={}",
                    support.hasEndOfTransmission(response)
            );
        }

        if (sawSignalMetaThisPage) {
            pendingSawSignalMeta = true;
            pendingPageCursor = pageCursor;
        }
        if (sawAnyStoredData) {
            pendingSawStoredData = true;
        }

        final boolean hasEot = support.hasEndOfTransmission(response);
        if (!hasEot) {
            return;
        }

        if (!pendingSawSignalMeta || pendingPageCursor < 0) {
            resetPendingPageState();
            return;
        }

        final int currentCursor = pendingPageCursor;
        final int currentSignalFlags = pendingPageSignalFlags;
        final boolean sawStoredData = pendingSawStoredData;
        final long lastSampleTimestampMs = pendingLastSampleTimestampMs;
        final int lastSampleSpo2 = pendingLastSampleSpo2;
        resetPendingPageState();

        if (isRepeatedPage(currentCursor, currentSignalFlags, lastSampleTimestampMs, lastSampleSpo2)) {
            if (!sawStoredData) {
                logger.warn("Stopping stored measure loop for signalType={} after repeated empty page cursor={}", signalType, currentCursor);
                return;
            }
            if (repeatedPageRetries >= MAX_REPEATED_PAGE_RETRIES) {
                logger.warn("Watch refused to advance after deletes. Bypassing stuck record by forcing sequential cursor from {}", currentCursor);
                repeatedPageRetries = 0;
                forceSequentialCursor = true;
                support.queueGetStoredMeasureSignal(signalType, currentCursor + 1, StoredMeasureSignalHandler.this);
                return;
            }

            repeatedPageRetries++;
            final int deleteAttempts = support.incrementStoredMeasureDeleteAttempts();
            if (deleteAttempts > MAX_DELETE_ATTEMPTS) {
                logger.warn("Stopping stored measure loop for signalType={} after {} delete attempts", signalType, deleteAttempts);
                return;
            }

            logger.warn("Retrying delete for repeated stored measure page signalType={} cursor={} flags={} ts={} spo2={} retry={}",
                    signalType, currentCursor, currentSignalFlags, lastSampleTimestampMs, lastSampleSpo2, repeatedPageRetries);
            support.queueDeleteStoredMeasureSignal(signalType, currentSignalFlags, currentCursor, deleteResponse ->
                    support.queueGetStoredMeasureSignal(signalType, 0, StoredMeasureSignalHandler.this));
            return;
        }

        repeatedPageRetries = 0;

        lastDeletedCursor = currentCursor;
        lastDeletedSignalFlags = currentSignalFlags;
        lastDeletedTimestampMs = lastSampleTimestampMs;
        lastDeletedSpo2 = lastSampleSpo2;

        final int deleteAttempts = support.incrementStoredMeasureDeleteAttempts();
        if (deleteAttempts > MAX_DELETE_ATTEMPTS) {
            logger.warn("Stopping stored measure loop for signalType={} after {} delete attempts", signalType, deleteAttempts);
            return;
        }

        support.queueDeleteStoredMeasureSignal(signalType, currentSignalFlags, currentCursor, deleteResponse -> {
            if (sawStoredData) {
                final int nextReqCursor = forceSequentialCursor ? (currentCursor + 1) : 0;
                support.queueGetStoredMeasureSignal(signalType, nextReqCursor, StoredMeasureSignalHandler.this);
            }
        });
    }

    private void resetPendingPageState() {
        pendingPageCursor = -1;
        pendingPageSignalFlags = 0;
        pendingSawSignalMeta = false;
        pendingSawStoredData = false;
        pendingLastSampleTimestampMs = -1;
        pendingLastSampleSpo2 = -1;
    }

    private boolean isRepeatedPage(final int cursor,
                                   final int signalFlags,
                                   final long timestampMs,
                                   final int spo2) {
        return cursor == lastDeletedCursor
                && signalFlags == lastDeletedSignalFlags
                && timestampMs == lastDeletedTimestampMs
                && spo2 == lastDeletedSpo2;
    }

    private static boolean isLikelySpo2Measurement(final StoredMeasureMeta meta, final StoredMeasureData data) {
        if (data.getMeasurementType() == MEASUREMENT_TYPE_SPO2) {
            return true;
        }

        return meta.getMeasurementType() == MEASUREMENT_TYPE_SPO2;
    }
}
