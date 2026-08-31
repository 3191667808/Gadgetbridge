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
    public static final int BLOCK_DEVICE_MANAGEMENT = 0x04;
    public static final int BLOCK_AUDIO_MANAGEMENT = 0x05;
    public static final int BLOCK_NOTIFICATION = 0x09;

    // Operators
    public static final int OP_GET = 0x01;
    public static final int OP_SETGET = 0x02;
    public static final int OP_STATUS = 0x03;
    public static final int OP_ERROR = 0x04;
    public static final int OP_START = 0x05;

    // Notification functions
    public static final int FUNCTION_NOTIFICATION_BY_FUNCTION_BLOCK = 0x02;

    // Settings functions
    public static final int FUNCTION_NOISE_CANCELLING = 0x05;
    public static final int FUNCTION_ANR = 0x06;

    // Status functions
    public static final int FUNCTION_BATTERY = 0x02;

    // Product info functions
    public static final int FUNCTION_INIT_HANDSHAKE = 0x01;
    public static final int FUNCTION_FIRMWARE_VERSION = 0x05;

    // Device management functions
    public static final int FUNCTION_PAIRING_MODE = 0x08;

    // Audio management functions
    public static final int FUNCTION_MEDIA_CONTROL = 0x03;

    // Active source types
    public static final int SOURCE_NONE = 0x00;
    public static final int SOURCE_BLUETOOTH = 0x01;
    public static final int SOURCE_AUXILIARY = 0x02;

    // Media transport control actions
    public static final int MEDIA_PLAY = 0x01;
    public static final int MEDIA_PAUSE = 0x02;
    public static final int MEDIA_NEXT = 0x03;
    public static final int MEDIA_PREVIOUS = 0x04;

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

    public static byte[] enableNotificationsForFunctionBlocks(final int... blocks) {
        int highestBlock = 0;
        for (final int block : blocks) {
            if (block < 0 || block > 0xFF) {
                throw new IllegalArgumentException("Function block must fit in an unsigned byte");
            }
            highestBlock = Math.max(highestBlock, block);
        }

        final byte[] bitset = new byte[Math.max(1, (highestBlock / 8) + 1)];
        for (final int block : blocks) {
            bitset[bitset.length - 1 - (block / 8)] |= 1 << (block % 8);
        }
        final byte[] payload = new byte[1 + bitset.length];
        payload[0] = 0x01;
        System.arraycopy(bitset, 0, payload, 1, bitset.length);
        return frame(BLOCK_NOTIFICATION, FUNCTION_NOTIFICATION_BY_FUNCTION_BLOCK, OP_SETGET, payload);
    }

    public static byte[] getFirmwareVersion() {
        return frame(BLOCK_PRODUCT_INFO, FUNCTION_FIRMWARE_VERSION, OP_GET);
    }

    public static byte[] getCnc() {
        return frame(BLOCK_SETTINGS, FUNCTION_NOISE_CANCELLING, OP_GET);
    }

    public static byte[] getAnr() {
        return frame(BLOCK_SETTINGS, FUNCTION_ANR, OP_GET);
    }

    // Wire values: 0=Off, 1=High, 2=Wind, 3=Low.
    public static byte[] setAnr(final int level) {
        return frame(BLOCK_SETTINGS, FUNCTION_ANR, OP_SETGET, (byte) level);
    }

    // Wire value is inverted (10 - level) and sent three times because enabling ANC resets the level
    public static byte[] setCnc(final int level) {
        final int clamped = Math.max(0, Math.min(10, level));
        final byte[] packet = frame(BLOCK_SETTINGS, FUNCTION_NOISE_CANCELLING, OP_SETGET,
                (byte) (10 - clamped), (byte) 0x01);
        final byte[] repeated = new byte[packet.length * 3];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(packet, 0, repeated, i * packet.length, packet.length);
        }
        return repeated;
    }

    public static byte[] setPairingMode(final boolean enabled) {
        return frame(BLOCK_DEVICE_MANAGEMENT, FUNCTION_PAIRING_MODE, OP_START,
                (byte) (enabled ? 0x01 : 0x00));
    }

    /** Returns battery percentage, or -1 when absent. */
    public static int decodeBatteryLevel(final byte[] payload) {
        if (payload.length < 1) {
            return -1;
        }
        return payload[0] & 0xFF;
    }

    // CNC status payload: [numSteps, invertedLevel, enabled]
    public static int decodeCncLevel(final byte[] payload) {
        if (payload.length < 2) {
            return -1;
        }
        return 10 - (payload[1] & 0xFF);
    }

    // ANR status payload: [wireLevel, 0x0b]; wire 0=off, 1=high, 2=wind, 3=low
    public static int decodeAnrLevel(final byte[] payload) {
        if (payload.length < 1) {
            return -1;
        }
        final int level = payload[0] & 0xFF;
        return level <= 3 ? level : -1;
    }

    public static byte[] getMediaControlCapabilities() {
        return frame(BLOCK_AUDIO_MANAGEMENT, FUNCTION_MEDIA_CONTROL, OP_GET);
    }

    public static byte[] mediaControl(final int action) {
        return frame(BLOCK_AUDIO_MANAGEMENT, FUNCTION_MEDIA_CONTROL, OP_START, (byte) action);
    }

    public static int decodeMediaControlCapabilities(final byte[] payload) {
        if (payload.length < 1) {
            return 0;
        }
        int capabilities = payload[0] & 0xFF;
        if (payload.length > 1) {
            capabilities |= (payload[1] & 0xFF) << 8;
        }
        return capabilities;
    }

    public static boolean isMediaControlSupported(final int capabilities, final int action) {
        return action >= 0 && action < Integer.SIZE && (capabilities & (1 << action)) != 0;
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
