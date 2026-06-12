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

import java.util.Arrays;

final class FitbitIpv4Udp {
    private static final int IPV4_MIN_HEADER_LENGTH = 20;
    private static final int UDP_HEADER_LENGTH = 8;
    private static final int UDP_PROTOCOL = 17;
    private static final int COAPS_PORT = 5684;

    private FitbitIpv4Udp() {
    }

    static Packet parse(final byte[] packet) {
        if (packet.length < IPV4_MIN_HEADER_LENGTH || (packet[0] & 0xf0) != 0x40) {
            return null;
        }

        final int headerLength = (packet[0] & 0x0f) * 4;
        final int totalLength = readU16(packet, 2);
        if (headerLength < IPV4_MIN_HEADER_LENGTH
                || totalLength < headerLength + UDP_HEADER_LENGTH
                || totalLength > packet.length
                || (packet[9] & 0xff) != UDP_PROTOCOL) {
            return null;
        }

        final int udpOffset = headerLength;
        final int sourcePort = readU16(packet, udpOffset);
        final int destinationPort = readU16(packet, udpOffset + 2);
        final int udpLength = readU16(packet, udpOffset + 4);
        if (udpLength < UDP_HEADER_LENGTH
                || udpOffset + udpLength > totalLength
                || (sourcePort != COAPS_PORT && destinationPort != COAPS_PORT)) {
            return null;
        }

        return new Packet(
                new Endpoint(
                        Arrays.copyOfRange(packet, 12, 16),
                        Arrays.copyOfRange(packet, 16, 20),
                        sourcePort,
                        destinationPort
                ),
                udpOffset + UDP_HEADER_LENGTH,
                udpOffset + udpLength
        );
    }

    static byte[] buildResponse(final Endpoint endpoint, final byte[] udpPayload, final int ipIdentification) {
        final int udpLength = UDP_HEADER_LENGTH + udpPayload.length;
        final int totalLength = IPV4_MIN_HEADER_LENGTH + udpLength;
        final byte[] packet = new byte[totalLength];

        packet[0] = 0x45;
        writeU16(packet, 2, totalLength);
        writeU16(packet, 4, ipIdentification & 0xffff);
        packet[8] = (byte) 0xff;
        packet[9] = UDP_PROTOCOL;
        System.arraycopy(endpoint.destinationAddress, 0, packet, 12, 4);
        System.arraycopy(endpoint.sourceAddress, 0, packet, 16, 4);
        writeU16(packet, 10, checksum(packet, 0, IPV4_MIN_HEADER_LENGTH));

        final int udpOffset = IPV4_MIN_HEADER_LENGTH;
        writeU16(packet, udpOffset, endpoint.destinationPort);
        writeU16(packet, udpOffset + 2, endpoint.sourcePort);
        writeU16(packet, udpOffset + 4, udpLength);
        System.arraycopy(udpPayload, 0, packet, udpOffset + UDP_HEADER_LENGTH, udpPayload.length);
        writeU16(packet, udpOffset + 6, udpChecksum(packet, udpOffset, udpLength));

        return packet;
    }

    private static int checksum(final byte[] packet, final int offset, final int length) {
        long sum = 0;
        for (int i = 0; i < length; i += 2) {
            final int hi = packet[offset + i] & 0xff;
            final int lo = i + 1 < length ? packet[offset + i + 1] & 0xff : 0;
            sum += (hi << 8) | lo;
        }
        return finishChecksum(sum);
    }

    private static int udpChecksum(final byte[] packet, final int udpOffset, final int udpLength) {
        long sum = 0;
        sum = addAddress(sum, packet, 12);
        sum = addAddress(sum, packet, 16);
        sum += UDP_PROTOCOL;
        sum += udpLength;

        for (int i = 0; i < udpLength; i += 2) {
            final int hi = packet[udpOffset + i] & 0xff;
            final int lo = i + 1 < udpLength ? packet[udpOffset + i + 1] & 0xff : 0;
            sum += (hi << 8) | lo;
        }

        final int checksum = finishChecksum(sum);
        return checksum != 0 ? checksum : 0xffff;
    }

    private static long addAddress(long sum, final byte[] packet, final int offset) {
        sum += ((packet[offset] & 0xff) << 8) | (packet[offset + 1] & 0xff);
        sum += ((packet[offset + 2] & 0xff) << 8) | (packet[offset + 3] & 0xff);
        return sum;
    }

    private static int finishChecksum(long sum) {
        while ((sum >> 16) != 0) {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        return (int) (~sum) & 0xffff;
    }

    private static int readU16(final byte[] value, final int offset) {
        return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff);
    }

    private static void writeU16(final byte[] value, final int offset, final int number) {
        value[offset] = (byte) ((number >> 8) & 0xff);
        value[offset + 1] = (byte) (number & 0xff);
    }

    static final class Packet {
        final Endpoint endpoint;
        final int dtlsOffset;
        final int dtlsEnd;

        private Packet(final Endpoint endpoint, final int dtlsOffset, final int dtlsEnd) {
            this.endpoint = endpoint;
            this.dtlsOffset = dtlsOffset;
            this.dtlsEnd = dtlsEnd;
        }
    }

    static final class Endpoint {
        final byte[] sourceAddress;
        final byte[] destinationAddress;
        final int sourcePort;
        final int destinationPort;

        private Endpoint(final byte[] sourceAddress,
                         final byte[] destinationAddress,
                         final int sourcePort,
                         final int destinationPort) {
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
        }
    }
}
