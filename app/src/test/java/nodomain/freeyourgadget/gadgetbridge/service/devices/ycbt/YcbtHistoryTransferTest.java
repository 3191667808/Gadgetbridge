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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class YcbtHistoryTransferTest {
    private static final byte[] HEART_QUERY = new byte[]{0x05, 0x06};
    private static final byte[] VITALS_QUERY = new byte[]{0x05, 0x09};
    private static final byte[] ACK_ACCEPTED = new byte[]{0x05, (byte) 0x80, 0x00};
    private static final byte[] ACK_CRC_FAILURE = new byte[]{0x05, (byte) 0x80, 0x04};
    private static final byte[] HEART_HEADER_ONE_PACKET = new byte[]{
            0x02, 0x00, 0x01, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00
    };
    private static final byte[] HEART_HEADER_TWO_PACKETS = new byte[]{
            0x02, 0x00, 0x02, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00
    };
    private static final byte[] HEART_BLOCK = new byte[]{
            0x1c, (byte) 0xf0, (byte) 0xde, 0x31, 0x00, 0x47,
            0x1a, (byte) 0xfe, (byte) 0xde, 0x31, 0x00, 0x42
    };
    private static final byte[] HEART_TERMINAL_ONE_PACKET = new byte[]{
            0x01, 0x00, 0x0c, 0x00, 0x1a, (byte) 0x8b
    };
    private static final byte[] HEART_TERMINAL_TWO_PACKETS = new byte[]{
            0x02, 0x00, 0x0c, 0x00, 0x1a, (byte) 0x8b
    };
    private static final byte[] HEART_TERMINAL_BAD_CRC = new byte[]{
            0x01, 0x00, 0x0c, 0x00, (byte) 0xad, (byte) 0xde
    };

    @Test
    public void acceptsCompleteBlockAndAdvancesToNextType() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();

        final YcbtHistoryTransfer.Result started = transfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 0);
        assertAction(started, 0, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);

        assertEquals(YcbtHistoryTransfer.Status.HEADER_ACCEPTED,
                transfer.handle(0x06, HEART_HEADER_ONE_PACKET, 10).getStatus());
        assertEquals(YcbtHistoryTransfer.Status.DATA_ACCEPTED,
                transfer.handle(0x15, HEART_BLOCK, 20).getStatus());

        final YcbtHistoryTransfer.Result completed = transfer.handle(0x80, HEART_TERMINAL_ONE_PACKET, 30);

        assertEquals(YcbtHistoryTransfer.Status.BLOCK_ACCEPTED, completed.getStatus());
        assertEquals(YcbtHistoryTransfer.HistoryType.HEART_RATE, completed.getCompletedType());
        assertArrayEquals(HEART_BLOCK, completed.getCompletedBlock());
        assertAction(completed, 0, YcbtHistoryTransfer.ActionType.ACK, ACK_ACCEPTED);
        assertAction(completed, 1, YcbtHistoryTransfer.ActionType.REQUEST, VITALS_QUERY);
        assertFalse(completed.isFinished());
    }

    @Test
    public void acceptsRecordDataSplitAcrossTwoPackets() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);
        transfer.handle(0x06, HEART_HEADER_TWO_PACKETS, 10);

        transfer.handle(0x15, Arrays.copyOfRange(HEART_BLOCK, 0, 9), 20);
        transfer.handle(0x15, Arrays.copyOfRange(HEART_BLOCK, 9, HEART_BLOCK.length), 30);
        final YcbtHistoryTransfer.Result completed = transfer.handle(0x80, HEART_TERMINAL_TWO_PACKETS, 40);

        assertEquals(YcbtHistoryTransfer.Status.BLOCK_ACCEPTED, completed.getStatus());
        assertArrayEquals(HEART_BLOCK, completed.getCompletedBlock());
        assertTrue(completed.isFinished());
    }

    @Test
    public void acceptsFirmwareHeaderPacketEstimateWhenTerminalMatchesReceivedBlock() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);
        final byte[] header = new byte[]{0x01, 0x00, 0x19, 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00};
        transfer.handle(0x06, header, 10);
        transfer.handle(0x15, Arrays.copyOfRange(HEART_BLOCK, 0, 6), 20);
        transfer.handle(0x15, Arrays.copyOfRange(HEART_BLOCK, 6, HEART_BLOCK.length), 30);

        final YcbtHistoryTransfer.Result completed =
                transfer.handle(0x80, HEART_TERMINAL_TWO_PACKETS, 40);

        assertEquals(YcbtHistoryTransfer.Status.BLOCK_ACCEPTED, completed.getStatus());
        assertArrayEquals(HEART_BLOCK, completed.getCompletedBlock());
        assertAction(completed, 0, YcbtHistoryTransfer.ActionType.ACK, ACK_ACCEPTED);
    }

    @Test
    public void nacksPacketAndByteCountMismatchesAndRetriesOnce() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);
        transfer.handle(0x06, HEART_HEADER_TWO_PACKETS, 10);
        transfer.handle(0x15, HEART_BLOCK, 20);

        final YcbtHistoryTransfer.Result packetMismatch =
                transfer.handle(0x80, HEART_TERMINAL_TWO_PACKETS, 30);

        assertEquals(YcbtHistoryTransfer.Status.COUNT_MISMATCH, packetMismatch.getStatus());
        assertAction(packetMismatch, 0, YcbtHistoryTransfer.ActionType.NACK, ACK_CRC_FAILURE);
        assertAction(packetMismatch, 1, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);

        transfer.handle(0x06, HEART_HEADER_TWO_PACKETS, 35);
        transfer.handle(0x15, Arrays.copyOf(HEART_BLOCK, 11), 36);
        final byte[] wrongByteCount = new byte[]{0x02, 0x00, 0x0b, 0x00, 0x1a, (byte) 0x8b};
        final YcbtHistoryTransfer.Result byteMismatch = transfer.handle(0x80, wrongByteCount, 40);
        assertEquals(YcbtHistoryTransfer.Status.COUNT_MISMATCH, byteMismatch.getStatus());
        assertAction(byteMismatch, 0, YcbtHistoryTransfer.ActionType.NACK, ACK_CRC_FAILURE);
        assertTrue(byteMismatch.isFinished());
    }

    @Test
    public void nacksCrcMismatchAndRetriesCurrentTypeOnlyOnce() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 0);
        receiveOnePacketHeartBlock(transfer, 10);

        final YcbtHistoryTransfer.Result retry = transfer.handle(0x80, HEART_TERMINAL_BAD_CRC, 30);

        assertEquals(YcbtHistoryTransfer.Status.CRC_RETRY, retry.getStatus());
        assertAction(retry, 0, YcbtHistoryTransfer.ActionType.NACK, ACK_CRC_FAILURE);
        assertAction(retry, 1, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);

        receiveOnePacketHeartBlock(transfer, 40);
        final YcbtHistoryTransfer.Result skipped = transfer.handle(0x80, HEART_TERMINAL_BAD_CRC, 60);

        assertEquals(YcbtHistoryTransfer.Status.CRC_FAILED, skipped.getStatus());
        assertAction(skipped, 0, YcbtHistoryTransfer.ActionType.NACK, ACK_CRC_FAILURE);
        assertAction(skipped, 1, YcbtHistoryTransfer.ActionType.REQUEST, VITALS_QUERY);
    }

    @Test
    public void remembersUnsupportedHistoryKeysAcrossRuns() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 0);

        final YcbtHistoryTransfer.Result heartUnsupported =
                transfer.handle(0x06, new byte[]{(byte) 0xfc}, 10);
        assertEquals(YcbtHistoryTransfer.Status.UNSUPPORTED, heartUnsupported.getStatus());
        assertAction(heartUnsupported, 0, YcbtHistoryTransfer.ActionType.REQUEST, VITALS_QUERY);
        assertTrue(transfer.isUnsupported(YcbtHistoryTransfer.HistoryType.HEART_RATE));

        final YcbtHistoryTransfer.Result vitalsUnsupported =
                transfer.handle(0x09, new byte[]{(byte) 0xfb}, 20);
        assertTrue(vitalsUnsupported.isFinished());

        final YcbtHistoryTransfer.Result restarted = transfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 30);
        assertEquals(YcbtHistoryTransfer.Status.FINISHED, restarted.getStatus());
        assertTrue(restarted.getActions().isEmpty());
    }

    @Test
    public void transientProtocolErrorsDoNotMarkTypeUnsupported() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);

        final YcbtHistoryTransfer.Result failed = transfer.handle(0x06, new byte[]{(byte) 0xff}, 10);

        assertEquals(YcbtHistoryTransfer.Status.PROTOCOL_ERROR, failed.getStatus());
        assertFalse(transfer.isUnsupported(YcbtHistoryTransfer.HistoryType.HEART_RATE));
        final YcbtHistoryTransfer.Result restarted = transfer.start(
                Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 20);
        assertAction(restarted, 0, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);
    }

    @Test
    public void distinguishesInactivityFromAbsoluteTypeTimeout() {
        final YcbtHistoryTransfer inactivityTransfer = new YcbtHistoryTransfer(100, 300, 1024);
        inactivityTransfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 1_000);

        assertEquals(YcbtHistoryTransfer.Status.IGNORED, inactivityTransfer.onTimeout(1_099).getStatus());
        final YcbtHistoryTransfer.Result inactivity = inactivityTransfer.onTimeout(1_100);
        assertEquals(YcbtHistoryTransfer.Status.INACTIVITY_TIMEOUT, inactivity.getStatus());
        assertAction(inactivity, 0, YcbtHistoryTransfer.ActionType.REQUEST, VITALS_QUERY);

        final YcbtHistoryTransfer typeTransfer = new YcbtHistoryTransfer(1_000, 300, 1024);
        typeTransfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 2_000);
        typeTransfer.handle(0x06, HEART_HEADER_ONE_PACKET, 2_100);
        typeTransfer.handle(0x15, new byte[]{0x1c}, 2_200);

        final YcbtHistoryTransfer.Result typeTimeout = typeTransfer.onTimeout(2_300);
        assertEquals(YcbtHistoryTransfer.Status.TYPE_TIMEOUT, typeTimeout.getStatus());
        assertTrue(typeTimeout.isFinished());
        assertTrue(typeTimeout.getActions().isEmpty());
    }

    @Test
    public void drainsOversizedBlockWithoutAllocatingThenNacksAndRetries() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer(100, 300, 8);
        transfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 0);

        final YcbtHistoryTransfer.Result oversized = transfer.handle(0x06, HEART_HEADER_ONE_PACKET, 10);

        assertEquals(YcbtHistoryTransfer.Status.BUFFER_LIMIT_EXCEEDED, oversized.getStatus());
        assertEquals(0, transfer.getBufferedByteCount());
        assertTrue(oversized.getActions().isEmpty());

        assertEquals(YcbtHistoryTransfer.Status.BUFFER_LIMIT_EXCEEDED,
                transfer.handle(0x15, HEART_BLOCK, 20).getStatus());
        final YcbtHistoryTransfer.Result rejected =
                transfer.handle(0x80, HEART_TERMINAL_ONE_PACKET, 30);
        assertEquals(YcbtHistoryTransfer.Status.BUFFER_LIMIT_EXCEEDED, rejected.getStatus());
        assertAction(rejected, 0, YcbtHistoryTransfer.ActionType.NACK, ACK_CRC_FAILURE);
        assertAction(rejected, 1, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);
    }

    @Test
    public void ignoresReservedHeaderBytesAfterUnsigned16BitPacketCount() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);
        final byte[] header = Arrays.copyOf(HEART_HEADER_ONE_PACKET, HEART_HEADER_ONE_PACKET.length);
        header[4] = 0x01;

        transfer.handle(0x06, header, 10);
        transfer.handle(0x15, HEART_BLOCK, 20);
        final YcbtHistoryTransfer.Result completed =
                transfer.handle(0x80, HEART_TERMINAL_ONE_PACKET, 30);

        assertEquals(YcbtHistoryTransfer.Status.BLOCK_ACCEPTED, completed.getStatus());
        assertAction(completed, 0, YcbtHistoryTransfer.ActionType.ACK, ACK_ACCEPTED);
    }

    @Test
    public void cancellationClearsSessionUnsupportedKeys() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);
        transfer.handle(0x06, new byte[]{(byte) 0xfc}, 10);
        assertTrue(transfer.isUnsupported(YcbtHistoryTransfer.HistoryType.HEART_RATE));

        transfer.cancel();

        assertFalse(transfer.isUnsupported(YcbtHistoryTransfer.HistoryType.HEART_RATE));
        assertAction(transfer.start(Collections.singletonList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE), 20),
                0, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);
    }

    @Test
    public void noDataHeaderSkipsTypeWithoutAcknowledging() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 0);

        final YcbtHistoryTransfer.Result noData = transfer.handle(0x06, new byte[]{0x00}, 10);

        assertEquals(YcbtHistoryTransfer.Status.TYPE_SKIPPED, noData.getStatus());
        assertEquals(1, noData.getActions().size());
        assertAction(noData, 0, YcbtHistoryTransfer.ActionType.REQUEST, VITALS_QUERY);
    }

    @Test
    public void malformedMultiByteHeaderIsNackedAndRetried() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);

        final YcbtHistoryTransfer.Result malformed = transfer.handle(0x06, new byte[]{0x01, 0x02}, 10);

        assertEquals(YcbtHistoryTransfer.Status.PROTOCOL_ERROR, malformed.getStatus());
        assertAction(malformed, 0, YcbtHistoryTransfer.ActionType.NACK, ACK_CRC_FAILURE);
        assertAction(malformed, 1, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);
    }

    @Test
    public void unknownOneByteHeaderIsNackedAndRetried() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);

        final YcbtHistoryTransfer.Result malformed = transfer.handle(0x06, new byte[]{0x01}, 10);

        assertEquals(YcbtHistoryTransfer.Status.PROTOCOL_ERROR, malformed.getStatus());
        assertAction(malformed, 0, YcbtHistoryTransfer.ActionType.NACK, ACK_CRC_FAILURE);
        assertAction(malformed, 1, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);
    }

    @Test
    public void malformedTerminalIsNackedAndRetried() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);
        receiveOnePacketHeartBlock(transfer, 10);

        final YcbtHistoryTransfer.Result malformed = transfer.handle(0x80, new byte[]{0x01}, 30);

        assertEquals(YcbtHistoryTransfer.Status.PROTOCOL_ERROR, malformed.getStatus());
        assertAction(malformed, 0, YcbtHistoryTransfer.ActionType.NACK, ACK_CRC_FAILURE);
        assertAction(malformed, 1, YcbtHistoryTransfer.ActionType.REQUEST, HEART_QUERY);
    }

    @Test
    public void ignoresDelayedTerminalThatMatchesNeitherCurrentHeaderNorBuffer() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Arrays.asList(
                YcbtHistoryTransfer.HistoryType.HEART_RATE,
                YcbtHistoryTransfer.HistoryType.VITALS
        ), 0);
        transfer.handle(0x06, new byte[]{(byte) 0xfc}, 10);
        transfer.handle(0x09, new byte[]{0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00}, 20);
        transfer.handle(0x18, new byte[]{0x01, 0x02, 0x03, 0x04}, 30);

        final YcbtHistoryTransfer.Result delayed =
                transfer.handle(0x80, HEART_TERMINAL_ONE_PACKET, 40);

        assertEquals(YcbtHistoryTransfer.Status.IGNORED, delayed.getStatus());
        assertTrue(delayed.getActions().isEmpty());
        assertEquals(YcbtHistoryTransfer.HistoryType.VITALS, transfer.getCurrentType());
    }

    @Test
    public void acceptsOneByteDataFragmentThatLooksLikeAnErrorCode() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);
        transfer.handle(0x06, HEART_HEADER_TWO_PACKETS, 10);

        final YcbtHistoryTransfer.Result data = transfer.handle(0x15, new byte[]{(byte) 0xfc}, 20);

        assertEquals(YcbtHistoryTransfer.Status.DATA_ACCEPTED, data.getStatus());
        assertFalse(transfer.isUnsupported(YcbtHistoryTransfer.HistoryType.HEART_RATE));
    }

    @Test
    public void ignoresLateTerminalAfterCompletionOrCancellation() {
        final YcbtHistoryTransfer transfer = new YcbtHistoryTransfer();
        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 0);
        receiveOnePacketHeartBlock(transfer, 10);
        transfer.handle(0x80, HEART_TERMINAL_ONE_PACKET, 30);

        final YcbtHistoryTransfer.Result duplicate = transfer.handle(0x80, HEART_TERMINAL_ONE_PACKET, 40);
        assertEquals(YcbtHistoryTransfer.Status.IGNORED, duplicate.getStatus());
        assertTrue(duplicate.getActions().isEmpty());

        transfer.start(Collections.singletonList(YcbtHistoryTransfer.HistoryType.HEART_RATE), 50);
        transfer.cancel();
        final YcbtHistoryTransfer.Result cancelled = transfer.handle(0x80, HEART_TERMINAL_ONE_PACKET, 60);
        assertEquals(YcbtHistoryTransfer.Status.IGNORED, cancelled.getStatus());
        assertFalse(transfer.isActive());
        assertNull(cancelled.getCompletedBlock());
    }

    private static void receiveOnePacketHeartBlock(final YcbtHistoryTransfer transfer, final long nowMillis) {
        transfer.handle(0x06, HEART_HEADER_ONE_PACKET, nowMillis);
        transfer.handle(0x15, HEART_BLOCK, nowMillis + 10);
    }

    private static void assertAction(final YcbtHistoryTransfer.Result result,
                                     final int index,
                                     final YcbtHistoryTransfer.ActionType type,
                                     final byte[] command) {
        assertEquals(type, result.getActions().get(index).getType());
        assertArrayEquals(command, result.getActions().get(index).getCommand());
    }
}
