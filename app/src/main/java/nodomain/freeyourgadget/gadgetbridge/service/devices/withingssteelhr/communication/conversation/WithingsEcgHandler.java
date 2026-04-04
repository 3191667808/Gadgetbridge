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
import java.util.Arrays;
import java.util.List;

import de.greenrobot.dao.query.DeleteQuery;
import de.greenrobot.dao.query.QueryBuilder;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgDataSample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgDataSampleDao;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgSummarySample;
import nodomain.freeyourgadget.gadgetbridge.entities.HuaweiEcgSummarySampleDao;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.WithingsBaseDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.EndOfTransmission;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.MeasureCategory;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.MeasureLiveAppStatus;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.RawWithingsStructure;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.StoredMeasureData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.StoredMeasureMeta;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.StoredSignalData;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.StoredSignalMeta;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.datastructures.WithingsStructure;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.ExpectedResponse;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.Message;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessage;
import nodomain.freeyourgadget.gadgetbridge.service.devices.withingssteelhr.communication.message.WithingsMessageType;

public class WithingsEcgHandler implements ResponseHandler {
    private static final Logger logger = LoggerFactory.getLogger(WithingsEcgHandler.class);

    private static final int ECG_SIGNAL_TYPE = 0x0004;
    private static final int ECG_MEASUREMENT_TYPE = 0x0103;
    private static final int ECG_AVERAGE_HR_TYPE = 0x000b;
    private static final int ECG_WAVEFORM_SIGNAL_TYPE = 0x0001;
    private static final int ECG_SAMPLE_RATE_HZ = 300;
    private static final int MAX_DISCOVERY_PAGES = 32;
    private static final String APP_VERSION_PLACEHOLDER = "withings";

    private final WithingsBaseDeviceSupport support;
    private final GBDevice device;
    private final List<byte[]> seenRecordKeys = new ArrayList<>();

    private int discoveryPagesSeen;
    private boolean waveformFetchQueued;
    private EcgWaveformHandler activeWaveformHandler;

    public WithingsEcgHandler(final WithingsBaseDeviceSupport support, final GBDevice device) {
        this.support = support;
        this.device = device;
    }

    public void start() {
        if (waveformFetchQueued) {
            logger.debug("Withings ECG fetch already in progress, keeping current handler state");
            return;
        }

        seenRecordKeys.clear();
        discoveryPagesSeen = 0;
        waveformFetchQueued = false;
        queueDiscoveryProbe(true);
    }

    public boolean isWaveformFetchActive() {
        return activeWaveformHandler != null;
    }

    @Override
    public void handleResponse(final Message response) {
        if (response.getType() == WithingsMessageType.MEASURE_STOP) {
            handleDiscoveryResponse(response);
        }
    }

    private void queueDiscoveryProbe(final boolean initial) {
        final WithingsMessage message = new WithingsMessage(WithingsMessageType.MEASURE_START, ExpectedResponse.EOT);
        message.addDataStructure(new MeasureCategory(MeasureCategory.ECG));
        message.addDataStructure(new MeasureLiveAppStatus(initial ? 1 : 0));
        support.addSimpleConversationFirst(message, null);
    }

    public boolean maybeHandleMeasurementMessage(final Message response) {
        if (response.getType() == WithingsMessageType.MEASURE_STOP) {
            handleDiscoveryResponse(response);
            return true;
        }

        return false;
    }

    private void handleDiscoveryResponse(final Message response) {
        final List<WithingsStructure> structures = response.getDataStructures();
        if (structures == null || structures.isEmpty()) {
            return;
        }

        StoredMeasureMeta recordKey = null;
        boolean hasEcgMarkers = false;
        boolean hasEot = false;

        for (final WithingsStructure structure : structures) {
            if (structure instanceof StoredMeasureMeta) {
                final StoredMeasureMeta meta = (StoredMeasureMeta) structure;
                if (meta.getMeasurementType() == ECG_MEASUREMENT_TYPE) {
                    recordKey = meta;
                    hasEcgMarkers = true;
                }
            } else if (structure instanceof StoredMeasureData) {
                final StoredMeasureData data = (StoredMeasureData) structure;
                if (data.getMeasurementType() != 54) {
                    hasEcgMarkers = true;
                }
            } else if (structure instanceof RawWithingsStructure) {
                final short type = structure.getType();
                if (type == (short) 0x0149 || type == (short) 0x014a || type == (short) 0x097e || type == (short) 0x097b) {
                    hasEcgMarkers = true;
                }
            } else if (structure instanceof EndOfTransmission) {
                hasEot = true;
            }
        }

        if (recordKey != null && isNewRecordKey(recordKey)) {
            seenRecordKeys.add(recordKey.getRawPayload());
            logger.info("Discovered Withings ECG record ts={} type={}", recordKey.getTimestampMs(), recordKey.getMeasurementType());
            waveformFetchQueued = true;
            queueWaveformFetch(recordKey);
        }

        acknowledgeMeasurementStop();

        if (hasEot) {
            discoveryPagesSeen++;
            if (!waveformFetchQueued && hasEcgMarkers && discoveryPagesSeen < MAX_DISCOVERY_PAGES) {
                queueDiscoveryProbe(false);
            }
        }
    }

    private void acknowledgeMeasurementStop() {
        final WithingsMessage ack = new WithingsMessage(WithingsMessageType.MEASURE_STOP, ExpectedResponse.NONE);
        ack.addDataStructure(new EndOfTransmission());
        support.addSimpleConversationFirst(ack, null);
    }

    private void queueWaveformFetch(final StoredMeasureMeta recordKey) {
        queueWaveformFetch(recordKey, false);
    }

    private void queueWaveformFetch(final StoredMeasureMeta recordKey, final boolean verifyDeletion) {
        activeWaveformHandler = new EcgWaveformHandler(recordKey, verifyDeletion);

        final WithingsMessage message = new WithingsMessage(WithingsMessageType.GET_STORED_MEASURE_SIGNAL, ExpectedResponse.EOT);
        message.addDataStructure(recordKey);
        support.addSimpleConversationToQueue(message, activeWaveformHandler);
    }

    private boolean isNewRecordKey(final StoredMeasureMeta recordKey) {
        final byte[] rawPayload = recordKey.getRawPayload();
        for (final byte[] seen : seenRecordKeys) {
            if (Arrays.equals(seen, rawPayload)) {
                return false;
            }
        }
        return true;
    }

    private final class EcgWaveformHandler implements ResponseHandler {
        private final StoredMeasureMeta requestedRecordKey;
        private final boolean verifyDeletion;
        private final List<Float> waveform = new ArrayList<>();
        private StoredSignalMeta deleteKey;
        private long startTimestampMs;
        private int averageHeartRate = -1;

        private EcgWaveformHandler(final StoredMeasureMeta requestedRecordKey, final boolean verifyDeletion) {
            this.requestedRecordKey = requestedRecordKey;
            this.verifyDeletion = verifyDeletion;
        }

        @Override
        public void handleResponse(final Message response) {
            final List<WithingsStructure> structures = response.getDataStructures();
            if (structures == null || structures.isEmpty()) {
                return;
            }

            for (final WithingsStructure structure : structures) {
                if (structure instanceof StoredMeasureMeta) {
                    final StoredMeasureMeta meta = (StoredMeasureMeta) structure;
                    if (meta.getMeasurementType() == ECG_MEASUREMENT_TYPE) {
                        startTimestampMs = meta.getTimestampMs();
                    }
                } else if (structure instanceof StoredMeasureData) {
                    final StoredMeasureData data = (StoredMeasureData) structure;
                    if (data.getMeasurementType() == ECG_AVERAGE_HR_TYPE) {
                        averageHeartRate = data.getRawValue();
                    }
                } else if (structure instanceof StoredSignalMeta) {
                    final StoredSignalMeta signalMeta = (StoredSignalMeta) structure;
                    if (signalMeta.getSignalType() == ECG_WAVEFORM_SIGNAL_TYPE) {
                        deleteKey = signalMeta;
                    }
                } else if (structure instanceof StoredSignalData) {
                    final StoredSignalData signalData = (StoredSignalData) structure;
                    for (final byte sample : signalData.getSampleBytes()) {
                        waveform.add((float) sample);
                    }
                }
            }

            if (!support.hasEndOfTransmission(response)) {
                return;
            }

            if (startTimestampMs <= 0) {
                startTimestampMs = requestedRecordKey.getTimestampMs();
            }

            if (verifyDeletion) {
                if (!waveform.isEmpty()) {
                    logger.warn("Withings ECG delete verification still returned waveform data for ts={} samples={}", startTimestampMs, waveform.size());
                }
                activeWaveformHandler = null;
                waveformFetchQueued = false;
                if (discoveryPagesSeen < MAX_DISCOVERY_PAGES) {
                    queueDiscoveryProbe(false);
                }
                return;
            }

            if (!waveform.isEmpty()) {
                final long endTimestampMs = startTimestampMs + Math.round((waveform.size() * 1000d) / ECG_SAMPLE_RATE_HZ);
                storeWaveform(startTimestampMs, endTimestampMs, averageHeartRate, waveform);
            }

            if (deleteKey != null) {
                support.queueDeleteStoredMeasureSignal(
                        deleteKey.getSignalType(),
                        deleteKey.getSignalFlags(),
                        deleteKey.getCursor(),
                        0,
                        deleteResponse -> queueWaveformFetch(requestedRecordKey, true)
                );
                return;
            }

            activeWaveformHandler = null;
            waveformFetchQueued = false;
            if (discoveryPagesSeen < MAX_DISCOVERY_PAGES) {
                queueDiscoveryProbe(false);
            }
        }
    }

    private void storeWaveform(final long start,
                               final long end,
                               final int avgHr,
                               final List<Float> samples) {
        try (DBHandler db = GBApplication.acquireDB()) {
            final Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            final Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();

            final QueryBuilder<HuaweiEcgSummarySample> qb = db.getDaoSession().getHuaweiEcgSummarySampleDao().queryBuilder().where(
                    HuaweiEcgSummarySampleDao.Properties.UserId.eq(userId),
                    HuaweiEcgSummarySampleDao.Properties.DeviceId.eq(deviceId),
                    HuaweiEcgSummarySampleDao.Properties.StartTimestamp.eq(start)
            );
            final List<HuaweiEcgSummarySample> results = qb.build().list();
            Long ecgId = null;
            if (!results.isEmpty()) {
                ecgId = results.get(0).getEcgId();
            }

            final HuaweiEcgSummarySample summary = new HuaweiEcgSummarySample(
                    ecgId,
                    deviceId,
                    userId,
                    start,
                    end,
                    APP_VERSION_PLACEHOLDER,
                    Math.max(avgHr, 0),
                    0,
                    0
            );
            db.getDaoSession().getHuaweiEcgSummarySampleDao().insertOrReplace(summary);

            final DeleteQuery<HuaweiEcgDataSample> deleteQuery = db.getDaoSession().getHuaweiEcgDataSampleDao().queryBuilder()
                    .where(HuaweiEcgDataSampleDao.Properties.EcgId.eq(summary.getEcgId()))
                    .buildDelete();
            deleteQuery.executeDeleteWithoutDetachingEntities();

            final List<HuaweiEcgDataSample> waveformSamples = new ArrayList<>(samples.size());
            for (int i = 0; i < samples.size(); i++) {
                final Float sample = samples.get(i);
                final int delta = (int) Math.round((i * 1000d) / ECG_SAMPLE_RATE_HZ);
                waveformSamples.add(new HuaweiEcgDataSample(summary.getEcgId(), delta, sample));
            }
            db.getDaoSession().getHuaweiEcgDataSampleDao().insertInTx(waveformSamples);
            logger.info("Stored Withings ECG ts={} samples={}", start, samples.size());
        } catch (final Exception e) {
            logger.error("Failed storing Withings ECG data for ts={}", start, e);
        }
    }
}
