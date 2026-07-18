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

import java.util.Arrays;

import nodomain.freeyourgadget.gadgetbridge.util.CheckSums;

public final class YcbtFrameCodec {
    public static final int HEADER_LENGTH = 4;
    public static final int CRC_LENGTH = 2;
    public static final int MINIMUM_FRAME_LENGTH = HEADER_LENGTH + CRC_LENGTH;
    private static final int MAXIMUM_FRAME_LENGTH = 0xffff;

    private YcbtFrameCodec() {
    }

    public static byte[] encode(final int group, final int command, final byte[] payload) {
        if (group < 0 || group > 0xff || command < 0 || command > 0xff) {
            throw new IllegalArgumentException("Group and command must fit in one byte");
        }
        if (payload == null) {
            throw new IllegalArgumentException("Payload must not be null");
        }

        final int totalLength = MINIMUM_FRAME_LENGTH + payload.length;
        if (totalLength > MAXIMUM_FRAME_LENGTH) {
            throw new IllegalArgumentException("Frame is too long");
        }

        final byte[] frame = new byte[totalLength];
        frame[0] = (byte) group;
        frame[1] = (byte) command;
        frame[2] = (byte) totalLength;
        frame[3] = (byte) (totalLength >> 8);
        System.arraycopy(payload, 0, frame, HEADER_LENGTH, payload.length);

        final int crcOffset = totalLength - CRC_LENGTH;
        final int crc = CheckSums.getCRC16(Arrays.copyOf(frame, crcOffset));
        frame[crcOffset] = (byte) crc;
        frame[crcOffset + 1] = (byte) (crc >> 8);
        return frame;
    }

    public static Frame decode(final byte[] encoded) {
        if (encoded == null || encoded.length < MINIMUM_FRAME_LENGTH) {
            throw new IllegalArgumentException("Frame is shorter than the minimum length");
        }

        final int declaredLength = readLittleEndianUnsignedShort(encoded, 2);
        if (declaredLength != encoded.length) {
            throw new IllegalArgumentException("Declared frame length does not match input length");
        }

        final int crcOffset = encoded.length - CRC_LENGTH;
        final int expectedCrc = readLittleEndianUnsignedShort(encoded, crcOffset);
        final int actualCrc = CheckSums.getCRC16(Arrays.copyOf(encoded, crcOffset));
        if (expectedCrc != actualCrc) {
            throw new IllegalArgumentException("Frame CRC does not match");
        }

        return new Frame(
                encoded[0] & 0xff,
                encoded[1] & 0xff,
                Arrays.copyOfRange(encoded, HEADER_LENGTH, crcOffset)
        );
    }

    static int readLittleEndianUnsignedShort(final byte[] data, final int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    public static final class Frame {
        private final int group;
        private final int command;
        private final byte[] payload;

        private Frame(final int group, final int command, final byte[] payload) {
            this.group = group;
            this.command = command;
            this.payload = payload;
        }

        public int getGroup() {
            return group;
        }

        public int getCommand() {
            return command;
        }

        public byte[] getPayload() {
            return Arrays.copyOf(payload, payload.length);
        }
    }
}
