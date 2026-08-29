/*  Copyright (C) 2026 Dominic Monroe

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.bose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Encodes and decodes BMAP protocol frames. */
public final class BoseProtocol {
    public static final int MAX_FRAME_LENGTH = 255;

    // Function blocks
    public static final int BLOCK_PRODUCT_INFO = 0x00;
    public static final int BLOCK_SETTINGS = 0x01;
    public static final int BLOCK_STATUS = 0x02;

    // Operators
    public static final int OP_GET = 0x01;
    public static final int OP_SETGET = 0x02;
    public static final int OP_STATUS = 0x03;
    public static final int OP_ERROR = 0x04;

    // Settings functions
    public static final int FUNCTION_ANR = 0x06;

    // Status functions
    public static final int FUNCTION_BATTERY = 0x02;

    // Product info functions
    public static final int FUNCTION_INIT_HANDSHAKE = 0x01;

    public static final class Command {
        public final int block;
        public final int function;
        public final int operator;

        public Command(final int block, final int function, final int operator) {
            this.block = block;
            this.function = function;
            this.operator = operator;
        }

        public byte[] frame(final byte... payload) {
            return BoseProtocol.frame(block, function, operator, payload);
        }
    }

    private BoseProtocol() {
    }

    public static String errorName(final int error) {
        switch (error) {
            case 1: return "Length";
            case 3: return "Function block not supported";
            case 4: return "Function not supported";
            case 5: return "Operator not supported";
            case 6: return "Invalid data";
            default: return "Unknown";
        }
    }

    public static byte[] frame(final int block, final int function, final int operator,
                               final byte... payload) {
        if (payload.length > MAX_FRAME_LENGTH) {
            throw new IllegalArgumentException("Payload exceeds one-byte frame length");
        }
        final byte[] frame = new byte[4 + payload.length];
        frame[0] = (byte) block;
        frame[1] = (byte) function;
        frame[2] = (byte) operator;
        frame[3] = (byte) payload.length;
        System.arraycopy(payload, 0, frame, 4, payload.length);
        return frame;
    }

    public static byte[] connectHandshake() {
        return frame(BLOCK_PRODUCT_INFO, FUNCTION_INIT_HANDSHAKE, OP_GET);
    }

    public static byte[] getBattery() {
        return frame(BLOCK_STATUS, FUNCTION_BATTERY, OP_GET);
    }

    public static byte[] setAnr(final int uiLevel) {
        int level = uiLevel;
        if (level == 2) {
            level = 1;
        } else if (level == 1) {
            level = 3;
        }
        return frame(BLOCK_SETTINGS, FUNCTION_ANR, OP_SETGET, (byte) level);
    }

    /** Returns battery percentage, or -1 when absent. */
    public static int decodeBatteryLevel(final byte[] payload) {
        if (payload.length < 1) {
            return -1;
        }
        return payload[0] & 0xFF;
    }

    /** Buffers incomplete data and returns complete frames. */
    public static final class BoseFrameParser {
        private byte[] pending = new byte[0];

        public synchronized List<byte[]> feed(final byte[] data) {
            final List<byte[]> frames = new ArrayList<>();
            pending = concat(pending, data);
            int offset = 0;
            while (pending.length - offset >= 4) {
                final int payloadLength = pending[offset + 3] & 0xFF;
                if (pending.length - offset < 4 + payloadLength) {
                    break;
                }
                frames.add(Arrays.copyOfRange(pending, offset, offset + 4 + payloadLength));
                offset += 4 + payloadLength;
            }
            pending = offset == 0 ? pending : Arrays.copyOfRange(pending, offset, pending.length);
            return frames;
        }

        private static byte[] concat(final byte[] a, final byte[] b) {
            final byte[] result = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, result, a.length, b.length);
            return result;
        }
    }
}
