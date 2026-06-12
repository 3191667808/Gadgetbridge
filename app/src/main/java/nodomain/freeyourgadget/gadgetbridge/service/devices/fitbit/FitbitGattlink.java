/*  Copyright (C) 2026 Marc

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitbit;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class FitbitGattlink {
    private static final int GATTLINK_CONTROL_PACKET_MASK = 0x80;
    private static final int GATTLINK_CONTROL_PACKET_TYPE_MASK = 0x7f;
    private static final int GATTLINK_CONTROL_RESET_REQUEST = 0x00;
    private static final int GATTLINK_CONTROL_RESET_COMPLETE = 0x01;
    private static final int GATTLINK_CONTROL_HEADER = 0x80;

    private static final int GATTLINK_DATA_ACK_MASK = 0x40;
    private static final int GATTLINK_DATA_PSN_MASK = 0x1f;
    private static final int GATTLINK_SN_WINDOW_SIZE = 1 << 5;
    private static final int GATTLINK_MIN_VERSION = 0x00;
    private static final int GATTLINK_MAX_VERSION = 0x00;
    private static final int DEFAULT_RX_WINDOW_SIZE = 8;
    private static final int DEFAULT_TX_WINDOW_SIZE = 8;

    private static final int IPV4_MIN_HEADER_LENGTH = 20;
    private static final int IPV4_TOTAL_LENGTH_OFFSET = 2;

    private FitbitGattlink() {
    }

    static byte[] resetRequestPacket() {
        return new byte[]{(byte) buildControlPacket(GATTLINK_CONTROL_RESET_REQUEST)};
    }

    static byte[] resetCompletePacket() {
        return new byte[]{
                (byte) buildControlPacket(GATTLINK_CONTROL_RESET_COMPLETE),
                GATTLINK_MIN_VERSION,
                GATTLINK_MAX_VERSION,
                DEFAULT_RX_WINDOW_SIZE,
                DEFAULT_TX_WINDOW_SIZE
        };
    }

    static byte[] ackPacket(final int psn) {
        return new byte[]{(byte) (GATTLINK_DATA_ACK_MASK | (psn & GATTLINK_DATA_PSN_MASK))};
    }

    private static int buildControlPacket(final int type) {
        return GATTLINK_CONTROL_HEADER | (type & GATTLINK_CONTROL_PACKET_TYPE_MASK);
    }

    private static int nextSn(final int sn) {
        return (sn + 1) & GATTLINK_DATA_PSN_MASK;
    }

    private static int snDistance(final int snBeginInclusive, final int snEndExclusive) {
        return (GATTLINK_SN_WINDOW_SIZE + snEndExclusive - snBeginInclusive) % GATTLINK_SN_WINDOW_SIZE;
    }

    private static int expectedIpv4Length(final byte[] value, final int offset, final int length) {
        if (length < IPV4_MIN_HEADER_LENGTH) {
            return -1;
        }

        final int versionAndHeaderLength = value[offset] & 0xff;
        final int version = versionAndHeaderLength >> 4;
        final int headerLength = (versionAndHeaderLength & 0x0f) * 4;
        if (version != 4 || headerLength < IPV4_MIN_HEADER_LENGTH) {
            return -1;
        }

        final int totalLength = ((value[offset + IPV4_TOTAL_LENGTH_OFFSET] & 0xff) << 8)
                | (value[offset + IPV4_TOTAL_LENGTH_OFFSET + 1] & 0xff);
        if (totalLength < headerLength) {
            return -1;
        }

        return totalLength;
    }

    static byte[][] encodeIpv4Packet(final byte[] packet, final int maxFrameLength, final PsnAllocator psnAllocator) {
        if (maxFrameLength <= 1) {
            throw new IllegalArgumentException("maxFrameLength must leave room for payload");
        }

        final int payloadLength = maxFrameLength - 1;
        final List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < packet.length; offset += payloadLength) {
            final int end = Math.min(packet.length, offset + payloadLength);
            final byte[] frame = new byte[1 + end - offset];
            frame[0] = (byte) psnAllocator.nextPsn();
            System.arraycopy(packet, offset, frame, 1, end - offset);
            frames.add(frame);
        }

        return frames.toArray(new byte[0][]);
    }

    static String describeIpv4Packet(final byte[] packet) {
        if (packet.length < IPV4_MIN_HEADER_LENGTH || ((packet[0] & 0xff) >> 4) != 4) {
            return "non-ipv4 len=" + packet.length;
        }

        final int totalLength = ((packet[2] & 0xff) << 8) | (packet[3] & 0xff);
        final int protocol = packet[9] & 0xff;
        return ipv4Address(packet, 12)
                + " -> "
                + ipv4Address(packet, 16)
                + " proto="
                + protocol
                + " len="
                + totalLength;
    }

    private static String ipv4Address(final byte[] packet, final int offset) {
        return (packet[offset] & 0xff)
                + "."
                + (packet[offset + 1] & 0xff)
                + "."
                + (packet[offset + 2] & 0xff)
                + "."
                + (packet[offset + 3] & 0xff);
    }

    interface PsnAllocator {
        int nextPsn();
    }

    static final class Session implements PsnAllocator {
        private enum State {
            INITIALIZED,
            AWAITING_RESET_COMPLETE_SELF_INITIATED,
            AWAITING_RESET_COMPLETE_REMOTE_INITIATED,
            READY
        }

        private final IpReassembler ipReassembler = new IpReassembler();
        private final boolean[] outgoingAwaitingAck = new boolean[GATTLINK_SN_WINDOW_SIZE];
        private State state = State.INITIALIZED;
        private int nextOutgoingPsn;
        private int nextExpectedAckPsn;
        private int nextExpectedIncomingPsn;
        private int lastIncomingPsn;
        private int actualRxWindowSize = DEFAULT_RX_WINDOW_SIZE;
        private int actualTxWindowSize = DEFAULT_TX_WINDOW_SIZE;

        void reset() {
            state = State.INITIALIZED;
            actualRxWindowSize = DEFAULT_RX_WINDOW_SIZE;
            actualTxWindowSize = DEFAULT_TX_WINDOW_SIZE;
            resetDataState();
        }

        IncomingResult start() {
            if (state != State.INITIALIZED) {
                return IncomingResult.empty();
            }

            state = State.AWAITING_RESET_COMPLETE_SELF_INITIATED;
            return IncomingResult.withResponse(resetRequestPacket());
        }

        IncomingResult handleIncoming(final byte[] rawData) {
            if (rawData.length == 0) {
                return IncomingResult.empty();
            }

            final int first = rawData[0] & 0xff;
            if ((first & GATTLINK_CONTROL_PACKET_MASK) == GATTLINK_CONTROL_PACKET_MASK) {
                return handleControlPacket(rawData, first & GATTLINK_CONTROL_PACKET_TYPE_MASK);
            }

            return handleDataPacket(rawData);
        }

        byte[][] encodeIpv4Packet(final byte[] packet, final int maxFrameLength) {
            return FitbitGattlink.encodeIpv4Packet(packet, maxFrameLength, this);
        }

        boolean hasPartialIpv4Packet() {
            return ipReassembler.hasPartialPacket();
        }

        boolean isReady() {
            return state == State.READY;
        }

        @Override
        public int nextPsn() {
            final int psn = nextOutgoingPsn;
            outgoingAwaitingAck[psn] = true;
            nextOutgoingPsn = nextSn(nextOutgoingPsn);
            return psn;
        }

        private IncomingResult handleControlPacket(final byte[] rawData, final int type) {
            if (type == GATTLINK_CONTROL_RESET_REQUEST) {
                final boolean wasReady = state == State.READY;
                state = State.AWAITING_RESET_COMPLETE_REMOTE_INITIATED;
                ipReassembler.reset();
                return IncomingResult.sessionReset(resetCompletePacket(), wasReady);
            }

            if (type == GATTLINK_CONTROL_RESET_COMPLETE) {
                if (rawData.length < 5) {
                    return IncomingResult.empty();
                }

                final boolean wasAwaitingSelfInitiated =
                        state == State.AWAITING_RESET_COMPLETE_SELF_INITIATED;
                if (wasAwaitingSelfInitiated
                        || state == State.AWAITING_RESET_COMPLETE_REMOTE_INITIATED) {
                    actualTxWindowSize = Math.min(DEFAULT_TX_WINDOW_SIZE, rawData[3] & 0xff);
                    actualRxWindowSize = Math.min(DEFAULT_RX_WINDOW_SIZE, rawData[4] & 0xff);
                    resetDataState();
                    state = State.READY;
                    if (wasAwaitingSelfInitiated) {
                        return IncomingResult.sessionReady(resetCompletePacket());
                    }
                    return IncomingResult.sessionReady();
                }

                return IncomingResult.empty();
            }

            return IncomingResult.empty();
        }

        private IncomingResult handleDataPacket(final byte[] rawData) {
            int dataOffset = 0;
            if ((rawData[0] & GATTLINK_DATA_ACK_MASK) == GATTLINK_DATA_ACK_MASK) {
                handleAck(rawData[0] & GATTLINK_DATA_PSN_MASK);
                dataOffset = 1;
            }

            if (dataOffset >= rawData.length) {
                return IncomingResult.empty();
            }

            if (state != State.READY) {
                return IncomingResult.closedData(rawData);
            }

            final int incomingPsn = rawData[dataOffset] & GATTLINK_DATA_PSN_MASK;
            if (incomingPsn != nextExpectedIncomingPsn) {
                final int distance = snDistance(incomingPsn, nextExpectedIncomingPsn);
                if (distance < actualRxWindowSize) {
                    return IncomingResult.unexpectedPsn(
                            incomingPsn,
                            nextExpectedIncomingPsn,
                            ackPacket(lastIncomingPsn)
                    );
                }
                return IncomingResult.unexpectedPsn(incomingPsn, nextExpectedIncomingPsn, null);
            }

            lastIncomingPsn = incomingPsn;
            final byte[] ack = ackPacket(incomingPsn);
            nextExpectedIncomingPsn = nextSn(nextExpectedIncomingPsn);
            final byte[] ipv4Packet = ipReassembler.addPayload(rawData, dataOffset + 1, rawData.length - dataOffset - 1);
            if (ipv4Packet != null) {
                return IncomingResult.ackAndPacket(ack, ipv4Packet);
            }

            return IncomingResult.withResponse(ack);
        }

        private void handleAck(final int ackedPsn) {
            if (!isOutgoingAwaitingAck(ackedPsn)) {
                return;
            }

            int psn = nextExpectedAckPsn;
            while (true) {
                outgoingAwaitingAck[psn] = false;
                if (psn == ackedPsn) {
                    nextExpectedAckPsn = nextSn(psn);
                    return;
                }
                psn = nextSn(psn);
            }
        }

        private boolean isOutgoingAwaitingAck(final int psn) {
            int current = nextExpectedAckPsn;
            while (current != nextOutgoingPsn) {
                if (current == psn) {
                    return outgoingAwaitingAck[current];
                }
                current = nextSn(current);
            }
            return false;
        }

        private void resetDataState() {
            nextOutgoingPsn = 0;
            nextExpectedAckPsn = 0;
            nextExpectedIncomingPsn = 0;
            lastIncomingPsn = 0;
            Arrays.fill(outgoingAwaitingAck, false);
            ipReassembler.reset();
        }
    }

    static final class IncomingResult {
        private static final IncomingResult EMPTY = new IncomingResult(
                new ArrayList<>(),
                null,
                false,
                false,
                false,
                -1,
                -1,
                null
        );

        private final List<byte[]> responses;
        private final byte[] ipv4Packet;
        private final boolean sessionReset;
        private final boolean sessionReady;
        private final boolean dataOnClosedSession;
        private final int unexpectedPsn;
        private final int expectedPsn;
        private final byte[] rawDataOnClosedSession;

        private IncomingResult(final List<byte[]> responses,
                               final byte[] ipv4Packet,
                               final boolean sessionReset,
                               final boolean sessionReady,
                               final boolean dataOnClosedSession,
                               final int unexpectedPsn,
                               final int expectedPsn,
                               final byte[] rawDataOnClosedSession) {
            this.responses = responses;
            this.ipv4Packet = ipv4Packet;
            this.sessionReset = sessionReset;
            this.sessionReady = sessionReady;
            this.dataOnClosedSession = dataOnClosedSession;
            this.unexpectedPsn = unexpectedPsn;
            this.expectedPsn = expectedPsn;
            this.rawDataOnClosedSession = rawDataOnClosedSession;
        }

        static IncomingResult empty() {
            return EMPTY;
        }

        static IncomingResult withResponse(final byte[] response) {
            final List<byte[]> responses = new ArrayList<>();
            responses.add(response);
            return new IncomingResult(responses, null, false, false, false, -1, -1, null);
        }

        static IncomingResult sessionReset(final byte[] response, final boolean sessionReset) {
            final List<byte[]> responses = new ArrayList<>();
            responses.add(response);
            return new IncomingResult(responses, null, sessionReset, false, false, -1, -1, null);
        }

        static IncomingResult sessionReady() {
            return new IncomingResult(new ArrayList<>(), null, false, true, false, -1, -1, null);
        }

        static IncomingResult sessionReady(final byte[] response) {
            final List<byte[]> responses = new ArrayList<>();
            responses.add(response);
            return new IncomingResult(responses, null, false, true, false, -1, -1, null);
        }

        static IncomingResult ackAndPacket(final byte[] ack, final byte[] ipv4Packet) {
            final List<byte[]> responses = new ArrayList<>();
            responses.add(ack);
            return new IncomingResult(responses, ipv4Packet, false, false, false, -1, -1, null);
        }

        static IncomingResult unexpectedPsn(final int unexpectedPsn, final int expectedPsn, final byte[] ack) {
            final List<byte[]> responses = new ArrayList<>();
            if (ack != null) {
                responses.add(ack);
            }
            return new IncomingResult(responses, null, false, false, false, unexpectedPsn, expectedPsn, null);
        }

        static IncomingResult closedData(final byte[] rawData) {
            return new IncomingResult(new ArrayList<>(), null, false, false, true, -1, -1, rawData);
        }

        List<byte[]> getResponses() {
            return responses;
        }

        byte[] getIpv4Packet() {
            return ipv4Packet;
        }

        boolean isSessionReset() {
            return sessionReset;
        }

        boolean isSessionReady() {
            return sessionReady;
        }

        boolean isDataOnClosedSession() {
            return dataOnClosedSession;
        }

        int getUnexpectedPsn() {
            return unexpectedPsn;
        }

        int getExpectedPsn() {
            return expectedPsn;
        }

        byte[] getRawDataOnClosedSession() {
            return rawDataOnClosedSession;
        }
    }

    private static final class IpReassembler {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int expectedLength = -1;

        byte[] addPayload(final byte[] payload, final int offset, final int length) {
            if (length <= 0) {
                return null;
            }

            if (expectedLength > 0) {
                buffer.write(payload, offset, length);
            } else {
                final int newExpectedLength = expectedIpv4Length(payload, offset, length);
                if (newExpectedLength <= 0) {
                    return null;
                }

                buffer.reset();
                expectedLength = newExpectedLength;
                buffer.write(payload, offset, length);
            }

            if (buffer.size() < expectedLength) {
                return null;
            }

            final byte[] packet = Arrays.copyOf(buffer.toByteArray(), expectedLength);
            buffer.reset();
            expectedLength = -1;
            return packet;
        }

        boolean hasPartialPacket() {
            return expectedLength > 0;
        }

        void reset() {
            buffer.reset();
            expectedLength = -1;
        }
    }
}
