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
    private static final int GATTLINK_FRAME_HEADER_LENGTH = 1;
    private static final int IPV4_MIN_HEADER_LENGTH = 20;
    private static final int IPV4_HEADER_OFFSET = GATTLINK_FRAME_HEADER_LENGTH;
    private static final int IPV4_TOTAL_LENGTH_OFFSET = IPV4_HEADER_OFFSET + 2;
    private static final byte DEFAULT_FRAME_PREFIX = 0x00;

    private FitbitGattlink() {
    }

    static boolean looksLikeIpv4Start(final byte[] frame) {
        return expectedIpv4Length(frame) > 0;
    }

    static int expectedIpv4Length(final byte[] frame) {
        if (frame.length < GATTLINK_FRAME_HEADER_LENGTH + IPV4_MIN_HEADER_LENGTH) {
            return -1;
        }

        final int versionAndHeaderLength = frame[IPV4_HEADER_OFFSET] & 0xff;
        final int version = versionAndHeaderLength >> 4;
        final int headerLength = (versionAndHeaderLength & 0x0f) * 4;
        if (version != 4 || headerLength < IPV4_MIN_HEADER_LENGTH) {
            return -1;
        }

        final int totalLength = ((frame[IPV4_TOTAL_LENGTH_OFFSET] & 0xff) << 8)
                | (frame[IPV4_TOTAL_LENGTH_OFFSET + 1] & 0xff);
        if (totalLength < headerLength) {
            return -1;
        }

        return totalLength;
    }

    static byte[][] encodeIpv4Packet(final byte[] packet, final int maxFrameLength) {
        return encodeIpv4Packet(packet, maxFrameLength, DEFAULT_FRAME_PREFIX);
    }

    static byte[][] encodeIpv4Packet(final byte[] packet, final int maxFrameLength, final byte framePrefix) {
        if (maxFrameLength <= GATTLINK_FRAME_HEADER_LENGTH) {
            throw new IllegalArgumentException("maxFrameLength must leave room for payload");
        }

        final int payloadLength = maxFrameLength - GATTLINK_FRAME_HEADER_LENGTH;
        final List<byte[]> frames = new ArrayList<>();
        for (int offset = 0; offset < packet.length; offset += payloadLength) {
            final int end = Math.min(packet.length, offset + payloadLength);
            final byte[] frame = new byte[GATTLINK_FRAME_HEADER_LENGTH + end - offset];
            frame[0] = framePrefix;
            System.arraycopy(packet, offset, frame, GATTLINK_FRAME_HEADER_LENGTH, end - offset);
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

    static final class IpReassembler {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int expectedLength = -1;

        byte[] addFrame(final byte[] frame) {
            final int newExpectedLength = expectedIpv4Length(frame);
            if (newExpectedLength > 0) {
                buffer.reset();
                expectedLength = newExpectedLength;
                appendFramePayload(frame);
            } else if (expectedLength > 0 && frame.length > GATTLINK_FRAME_HEADER_LENGTH) {
                appendFramePayload(frame);
            } else {
                return null;
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

        private void appendFramePayload(final byte[] frame) {
            buffer.write(frame, GATTLINK_FRAME_HEADER_LENGTH, frame.length - GATTLINK_FRAME_HEADER_LENGTH);
        }
    }
}
