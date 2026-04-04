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

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.database.DBHandler;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.database.repository.EcgRepository;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.EcgRecord;
import nodomain.freeyourgadget.gadgetbridge.model.EcgSample;
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
import nodomain.freeyourgadget.gadgetbridge.util.WithingsEcgWaveformUtil;

public class WithingsEcgHandler implements ResponseHandler {
    private static final Logger logger = LoggerFactory.getLogger(WithingsEcgHandler.class);

    private static final int ECG_MEASUREMENT_TYPE = 0x0103;
    private static final int ECG_AVERAGE_HR_TYPE = 0x000b;
    private static final int ECG_RESULT_TYPE = 0x0082;
    private static final int ECG_WAVEFORM_SIGNAL_TYPE = 0x0001;
    private static final int ECG_SAMPLE_RATE_HZ = 300;
    private static final String APP_VERSION_PLACEHOLDER = "withings";

    private static final int MAX_DISCOVERY_PAGES = 10;

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

    public boolean hasDiscoveredRecord(final StoredMeasureMeta recordKey) {
        if (recordKey == null) {
            return false;
        }
        for (final byte[] seenKey : seenRecordKeys) {
            if (Arrays.equals(seenKey, recordKey.getRawPayload())) {
                return true;
            }
        }
        return false;
    }

    public void handleDiscoveredRecord(final StoredMeasureMeta recordKey, final int originatingSignalType) {
        final boolean alreadyStored = recordKey != null && isRecordAlreadyStored(recordKey.getTimestampMs());
        final boolean isNewRecord = recordKey != null && !alreadyStored && isNewRecordKey(recordKey);
        logger.info("handleDiscoveredRecord called: recordKey != null = {}, isNewRecordKey = {}, alreadyStored = {}, seenRecordKeys.size = {}",
                recordKey != null,
                isNewRecord,
                alreadyStored,
                seenRecordKeys.size());

        if (recordKey == null) {
            return;
        }

        if (isNewRecord) {
            seenRecordKeys.add(recordKey.getRawPayload());
            logger.info("Discovered Withings ECG record via fallback ts={} type={}", recordKey.getTimestampMs(), recordKey.getMeasurementType());
        } else {
            logger.info("Re-fetching previously seen Withings ECG record ts={} so it can still be deleted", recordKey.getTimestampMs());
        }

        activeWaveformHandler = new EcgWaveformHandler(recordKey, isNewRecord, false, true, originatingSignalType);
        final WithingsMessage message = new WithingsMessage(WithingsMessageType.GET_STORED_MEASURE_SIGNAL, ExpectedResponse.EOT);
        message.addDataStructure(recordKey);
        support.addSimpleConversationFirst(message, activeWaveformHandler);
    }

    public void reset() {
        activeWaveformHandler = null;
        seenRecordKeys.clear();
    }

    @Override
    public void handleResponse(final Message response) {
        if (response.getType() == WithingsMessageType.MEASURE_START
                || response.getType() == WithingsMessageType.MEASURE_STOP) {
            handleDiscoveryResponse(response);
        }
    }

    public boolean maybeHandleMeasurementMessage(final Message response) {
        if (response.getType() == WithingsMessageType.MEASURE_START
                || response.getType() == WithingsMessageType.MEASURE_STOP) {
            handleDiscoveryResponse(response);
            return true;
        }
        return false;
    }

    private void queueDiscoveryProbe(final boolean initial) {
        final WithingsMessage message = new WithingsMessage(WithingsMessageType.MEASURE_START, ExpectedResponse.SIMPLE);
        message.addDataStructure(new MeasureCategory(MeasureCategory.ECG));
        message.addDataStructure(new MeasureLiveAppStatus(initial ? 1 : 0));
        support.addSimpleConversationFirst(message, this);
    }

    private void handleDiscoveryResponse(final Message response) {
        final List<WithingsStructure> structures = response.getDataStructures();
        if (structures == null || structures.isEmpty()) {
            return;
        }

        StoredMeasureMeta recordKey = null;
        boolean hasEot = false;
        boolean hasEcgMarkers = false;

        for (final WithingsStructure structure : structures) {
            if (structure instanceof EndOfTransmission) {
                hasEot = true;
            } else if (structure instanceof StoredMeasureMeta) {
                hasEcgMarkers = true;
                final StoredMeasureMeta meta = (StoredMeasureMeta) structure;
                if (meta.getMeasurementType() == ECG_MEASUREMENT_TYPE) {
                    recordKey = meta;
                }
            }
        }

        if (recordKey != null && isNewRecordKey(recordKey)) {
            seenRecordKeys.add(recordKey.getRawPayload());
            logger.info("Discovered Withings ECG record ts={} type={}", recordKey.getTimestampMs(), recordKey.getMeasurementType());
            waveformFetchQueued = true;
            activeWaveformHandler = new EcgWaveformHandler(recordKey, true, false, false, -1);
            final WithingsMessage message = new WithingsMessage(WithingsMessageType.GET_STORED_MEASURE_SIGNAL, ExpectedResponse.EOT);
            message.addDataStructure(recordKey);
            support.addSimpleConversationToQueue(message, activeWaveformHandler);
        }

        if (response.getType() == WithingsMessageType.MEASURE_STOP) {
            final WithingsMessage ack = new WithingsMessage(WithingsMessageType.MEASURE_STOP, ExpectedResponse.NONE);
            ack.addDataStructure(new EndOfTransmission());
            support.addSimpleConversationFirst(ack, null);
        }

        if (hasEot) {
            discoveryPagesSeen++;
            if (!waveformFetchQueued && hasEcgMarkers && discoveryPagesSeen < MAX_DISCOVERY_PAGES) {
                queueDiscoveryProbe(false);
            }
        }
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

    private boolean isRecordAlreadyStored(final long startTimestampMs) {
        if (startTimestampMs <= 0) {
            return false;
        }

        try (DBHandler db = GBApplication.acquireDB()) {
            final Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            final Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();
            return EcgRepository.hasSession(db.getDaoSession(), userId, deviceId, startTimestampMs);
        } catch (final Exception e) {
            logger.warn("Failed checking whether Withings ECG ts={} already exists", startTimestampMs, e);
            return false;
        }
    }

    private final class EcgWaveformHandler implements ResponseHandler {
        private final StoredMeasureMeta requestedRecordKey;
        private final boolean storeWaveformAfterFetch;
        private final boolean verifyDeletion;
        private final boolean foundViaSpO2Loop;
        private final int originatingSignalType;
        private final List<Float> waveform = new ArrayList<>();
        private final WithingsEcgWaveformUtil.StreamingDecoder waveformDecoder = new WithingsEcgWaveformUtil.StreamingDecoder();
        private StoredSignalMeta deleteKey;
        private long startTimestampMs;
        private int averageHeartRate = -1;
        private long arrhythmiaType = 0;

        private EcgWaveformHandler(final StoredMeasureMeta requestedRecordKey,
                                   final boolean storeWaveformAfterFetch,
                                   final boolean verifyDeletion,
                                   final boolean foundViaSpO2Loop,
                                   final int originatingSignalType) {
            this.requestedRecordKey = requestedRecordKey;
            this.storeWaveformAfterFetch = storeWaveformAfterFetch;
            this.verifyDeletion = verifyDeletion;
            this.foundViaSpO2Loop = foundViaSpO2Loop;
            this.originatingSignalType = originatingSignalType;
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
                    } else if (data.getMeasurementType() == ECG_RESULT_TYPE) {
                        arrhythmiaType = data.getRawValue();
                    }
                } else if (structure instanceof StoredSignalMeta) {
                    final StoredSignalMeta signalMeta = (StoredSignalMeta) structure;
                    if (signalMeta.getSignalType() == ECG_WAVEFORM_SIGNAL_TYPE) {
                        deleteKey = signalMeta;
                    }
                } else if (structure instanceof StoredSignalData) {
                    final StoredSignalData signalData = (StoredSignalData) structure;
                    final List<Float> decodedSamples = WithingsEcgWaveformUtil.decodePacket(signalData.getSampleBytes(), waveformDecoder);
                    if (!decodedSamples.isEmpty()) {
                        waveform.addAll(decodedSamples);
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
                } else {
                    logger.info("Withings ECG delete verification: waveform gone for ts={}", startTimestampMs);
                }
                activeWaveformHandler = null;
                waveformFetchQueued = false;
                if (foundViaSpO2Loop) {
                    support.queueGetStoredMeasureSignal(originatingSignalType, 0, new StoredMeasureSignalHandler(support, device, originatingSignalType));
                } else if (discoveryPagesSeen < MAX_DISCOVERY_PAGES) {
                    queueDiscoveryProbe(false);
                }
                return;
            }

            if (!waveform.isEmpty()) {
                final long endTimestampMs = startTimestampMs + Math.round((waveform.size() * 1000d) / ECG_SAMPLE_RATE_HZ);
                storeWaveform(startTimestampMs, endTimestampMs, averageHeartRate, arrhythmiaType, waveform);
            } else if (storeWaveformAfterFetch) {
                logger.warn("Expected Withings ECG waveform for ts={} but got none", startTimestampMs);
            }

            if (deleteKey != null) {
                support.queueDeleteStoredMeasureSignal(
                        deleteKey.getSignalType(),
                        deleteKey.getSignalFlags(),
                        deleteKey.getCursor(),
                        deleteResponse -> {
                            activeWaveformHandler = new EcgWaveformHandler(requestedRecordKey, false, true, foundViaSpO2Loop, originatingSignalType);
                            final WithingsMessage deleteVerifyMsg = new WithingsMessage(WithingsMessageType.GET_STORED_MEASURE_SIGNAL, ExpectedResponse.EOT);
                            deleteVerifyMsg.addDataStructure(requestedRecordKey);
                            support.addSimpleConversationFirst(deleteVerifyMsg, activeWaveformHandler);
                        }
                );
                return;
            }

            activeWaveformHandler = null;
            waveformFetchQueued = false;
            logger.warn("Missing delete key for Withings ECG record ts={}, unable to delete from watch", startTimestampMs);
            if (foundViaSpO2Loop) {
                support.queueGetStoredMeasureSignal(originatingSignalType, 0, new StoredMeasureSignalHandler(support, device, originatingSignalType));
            } else if (discoveryPagesSeen < MAX_DISCOVERY_PAGES) {
                queueDiscoveryProbe(false);
            }
        }
    }

    private void storeWaveform(final long start,
                               final long end,
                               final int avgHr,
                               final long arrhythmiaType,
                               final List<Float> samples) {
        try (DBHandler db = GBApplication.acquireDB()) {
            final Long userId = DBHelper.getUser(db.getDaoSession()).getId();
            final Long deviceId = DBHelper.getDevice(device, db.getDaoSession()).getId();

            final EcgRecord summary = new EcgRecord(
                    EcgRepository.findSessionId(db.getDaoSession(), userId, deviceId, start),
                    deviceId,
                    userId,
                    start,
                    end,
                    APP_VERSION_PLACEHOLDER,
                    Math.max(avgHr, 0),
                    arrhythmiaType,
                    0
            );

            final List<EcgSample> waveformSamples = new ArrayList<>(samples.size());
            for (int i = 0; i < samples.size(); i++) {
                final Float sample = samples.get(i);
                final int delta = (int) Math.round((i * 1000d) / ECG_SAMPLE_RATE_HZ);
                waveformSamples.add(new EcgSample(delta, sample));
            }
            EcgRepository.upsertSession(db.getDaoSession(), summary, waveformSamples);
            logger.info("Stored Withings ECG ts={} samples={}", start, samples.size());
        } catch (final Exception e) {
            logger.error("Failed storing Withings ECG data for ts={}", start, e);
        }
    }
}
