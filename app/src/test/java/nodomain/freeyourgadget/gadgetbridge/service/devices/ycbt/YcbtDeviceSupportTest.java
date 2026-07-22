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
package nodomain.freeyourgadget.gadgetbridge.service.devices.ycbt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothGattCharacteristic;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;

public class YcbtDeviceSupportTest {
    @Test
    public void requiresIndicateButAllowsNotifyAndIndicate() {
        assertTrue(YcbtDeviceSupport.supportsIndications(BluetoothGattCharacteristic.PROPERTY_INDICATE));
        assertTrue(YcbtDeviceSupport.supportsIndications(
                BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE
        ));
        assertFalse(YcbtDeviceSupport.supportsIndications(BluetoothGattCharacteristic.PROPERTY_NOTIFY));
    }

    @Test
    public void requiresWriteWithoutResponseForCapturedCommandTransport() {
        assertTrue(YcbtDeviceSupport.supportsWriteWithoutResponse(
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        ));
        assertTrue(YcbtDeviceSupport.supportsWriteWithoutResponse(
                BluetoothGattCharacteristic.PROPERTY_WRITE
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        | BluetoothGattCharacteristic.PROPERTY_INDICATE
        ));
        assertFalse(YcbtDeviceSupport.supportsWriteWithoutResponse(BluetoothGattCharacteristic.PROPERTY_WRITE));
        assertFalse(YcbtDeviceSupport.supportsWriteWithoutResponse(BluetoothGattCharacteristic.PROPERTY_INDICATE));
    }

    @Test
    public void latestRealtimeHeartRateRequestWinsAcrossPendingRepliesAndQuarantine() {
        final YcbtDeviceSupport.RealtimeHeartRateRequest pendingStartDisable =
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(false, false, false, true, true, null);
        assertEquals(YcbtDeviceSupport.RealtimeHeartRateRequestAction.DEFER,
                pendingStartDisable.getAction());
        assertEquals(Boolean.FALSE, pendingStartDisable.getDeferredTarget());

        final YcbtDeviceSupport.RealtimeHeartRateRequest quarantinedEnable =
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(true, false, true, false, false,
                        pendingStartDisable.getDeferredTarget());
        assertEquals(Boolean.TRUE, quarantinedEnable.getDeferredTarget());

        final YcbtDeviceSupport.RealtimeHeartRateRequest latestQuarantinedDisable =
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(false, false, true, false, false,
                        quarantinedEnable.getDeferredTarget());
        assertEquals(Boolean.FALSE, latestQuarantinedDisable.getDeferredTarget());

        final YcbtDeviceSupport.RealtimeHeartRateRequest reconciledDisable =
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(false, false, false, false, false,
                        latestQuarantinedDisable.getDeferredTarget());
        assertEquals(YcbtDeviceSupport.RealtimeHeartRateRequestAction.IGNORE, reconciledDisable.getAction());
        assertNull(reconciledDisable.getDeferredTarget());
    }

    @Test
    public void realtimeHeartRateRequestStartsAndStopsWhenUnblocked() {
        assertEquals(
                YcbtDeviceSupport.RealtimeHeartRateRequestAction.START,
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(true, false, false, false, false, null)
                        .getAction()
        );
        assertEquals(
                YcbtDeviceSupport.RealtimeHeartRateRequestAction.STOP,
                YcbtDeviceSupport.decideRealtimeHeartRateRequest(false, true, false, false, false, null)
                        .getAction()
        );
    }

    @Test
    public void persistsCompletedHistoryBeforeAcknowledgingAndAdvancing() {
        final List<String> events = new ArrayList<>();

        assertTrue(YcbtDeviceSupport.processHistoryResult(
                completedHistoryResult(),
                (type, block) -> {
                    events.add("persist");
                    return true;
                },
                action -> events.add(action.getType().name())
        ));

        assertEquals(Arrays.asList("persist", "ACK", "REQUEST"), events);
    }

    @Test
    public void doesNotAcknowledgeHistoryWhenPersistenceFails() {
        final List<String> events = new ArrayList<>();

        assertFalse(YcbtDeviceSupport.processHistoryResult(
                completedHistoryResult(),
                (type, block) -> {
                    events.add("persist");
                    return false;
                },
                action -> events.add(action.getType().name())
        ));

        assertEquals(Arrays.asList("persist"), events);
    }

    @Test
    public void filtersCombinedVitalsFieldsByAdvertisedCapabilities() {
        final byte[] capabilityPayload = new byte[24];
        capabilityPayload[1] = 1 << 3;
        final YcbtProtocol.Capabilities capabilities = YcbtProtocol.parseCapabilities(
                YcbtFrameCodec.decode(YcbtFrameCodec.encode(0x02, 0x01, capabilityPayload))
        );
        final List<YcbtHealthRecordParser.Record> records = YcbtHealthRecordParser.parse(
                YcbtHealthRecordParser.HISTORY_COMBINED_VITALS,
                bytes("1cf0de31721046764f610f3a0324061504370000")
        );
        final List<YcbtHealthRecordParser.MeasurementKind> acceptedKinds = new ArrayList<>();
        for (final YcbtHealthRecordParser.Record record : records) {
            if (YcbtDeviceSupport.historyRecordSupported(record, capabilities)
                    && record instanceof YcbtHealthRecordParser.MeasurementRecord) {
                acceptedKinds.add(((YcbtHealthRecordParser.MeasurementRecord) record).getKind());
            }
        }

        assertEquals(Arrays.asList(YcbtHealthRecordParser.MeasurementKind.SPO2), acceptedKinds);
    }

    @Test
    public void requestsBaselineHistoryWithoutBitmapBits() {
        final YcbtProtocol.Capabilities capabilities = YcbtProtocol.parseCapabilities(
                YcbtFrameCodec.decode(YcbtFrameCodec.encode(0x02, 0x01, new byte[24]))
        );

        assertEquals(Arrays.asList(YcbtHistoryTransfer.HistoryType.SPORT),
                YcbtDeviceSupport.historyTypesFor(RecordedDataTypes.TYPE_ACTIVITY, capabilities));
        assertEquals(Arrays.asList(YcbtHistoryTransfer.HistoryType.SLEEP),
                YcbtDeviceSupport.historyTypesFor(RecordedDataTypes.TYPE_SLEEP, capabilities));
        assertEquals(Arrays.asList(YcbtHistoryTransfer.HistoryType.HEART_RATE),
                YcbtDeviceSupport.historyTypesFor(RecordedDataTypes.TYPE_HEART_RATE, capabilities));
        assertEquals(Arrays.asList(YcbtHistoryTransfer.HistoryType.VITALS),
                YcbtDeviceSupport.historyTypesFor(RecordedDataTypes.TYPE_SPO2, capabilities));
    }

    @Test
    public void routesCombinedLiveFrameToOnlyOneConsumer() {
        assertEquals(
                YcbtDeviceSupport.LiveVitalsFrameRoute.VITALS,
                YcbtDeviceSupport.routeLiveVitalsFrame(YcbtBloodPressureOperation.State.IDLE)
        );
        for (final YcbtBloodPressureOperation.State state : YcbtBloodPressureOperation.State.values()) {
            if (state != YcbtBloodPressureOperation.State.IDLE) {
                assertEquals(
                        YcbtDeviceSupport.LiveVitalsFrameRoute.BLOOD_PRESSURE,
                        YcbtDeviceSupport.routeLiveVitalsFrame(state)
                );
            }
        }
    }

    @Test
    public void acceptsOldHistoryButRejectsFarFutureTimestamps() {
        final long now = 1_800_000_000_000L;
        assertTrue(YcbtDeviceSupport.historyTimestampSupported(now - 30L * 24L * 60L * 60L * 1_000L, now));
        assertTrue(YcbtDeviceSupport.historyTimestampSupported(now + 60L * 60L * 1_000L, now));
        assertFalse(YcbtDeviceSupport.historyTimestampSupported(now + 60L * 60L * 1_000L + 1, now));
    }

    private static YcbtHistoryTransfer.Result completedHistoryResult() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 0);
        transfer.handle(0x06, new byte[]{
                0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00
        }, 10);
        transfer.handle(0x15, new byte[]{
                0x1c, (byte) 0xf0, (byte) 0xde, 0x31, 0x00, 0x47,
                0x1a, (byte) 0xfe, (byte) 0xde, 0x31, 0x00, 0x42
        }, 20);
        return transfer.handle(0x80, new byte[]{
                0x01, 0x00, 0x0c, 0x00, 0x1a, (byte) 0x8b
        }, 30);
    }

    private static byte[] bytes(final String hex) {
        final byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) Integer.parseInt(hex.substring(index * 2, index * 2 + 2), 16);
        }
        return bytes;
    }
}
