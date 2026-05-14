/*  Copyright (C) 2026 Dany Mestas

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
package nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class OuraPacket {
    public final int tag;
    public final byte[] payload;

    public OuraPacket(final int tag, final byte[] payload) {
        this.tag = tag & 0xff;
        this.payload = payload;
    }

    public static byte[] frame(final int tag, final byte[] payload) {
        if (payload.length > 0xff) {
            throw new IllegalArgumentException("Payload too large: " + payload.length);
        }
        final byte[] out = new byte[2 + payload.length];
        out[0] = (byte) (tag & 0xff);
        out[1] = (byte) (payload.length & 0xff);
        System.arraycopy(payload, 0, out, 2, payload.length);
        return out;
    }

    public static byte[] frameExt(final int subTag, final byte[] payload) {
        final byte[] inner = new byte[1 + payload.length];
        inner[0] = (byte) (subTag & 0xff);
        System.arraycopy(payload, 0, inner, 1, payload.length);
        return frame(OuraOpcode.OP_EXT, inner);
    }

    public static OuraPacket parse(final byte[] frame) {
        if (frame == null || frame.length < 2) {
            throw new IllegalArgumentException("Frame too short");
        }
        final int tag = frame[0] & 0xff;
        final int len = frame[1] & 0xff;
        if (frame.length < 2 + len) {
            throw new IllegalArgumentException("Frame truncated: declared " + len + " got " + (frame.length - 2));
        }
        final byte[] payload = new byte[len];
        System.arraycopy(frame, 2, payload, 0, len);
        return new OuraPacket(tag, payload);
    }

    public static int readU16LE(final byte[] buf, final int off) {
        return (buf[off] & 0xff) | ((buf[off + 1] & 0xff) << 8);
    }

    public static long readU32LE(final byte[] buf, final int off) {
        return (buf[off] & 0xffL)
                | ((buf[off + 1] & 0xffL) << 8)
                | ((buf[off + 2] & 0xffL) << 16)
                | ((buf[off + 3] & 0xffL) << 24);
    }

    public static long readU64LE(final byte[] buf, final int off) {
        return (buf[off] & 0xffL)
                | ((buf[off + 1] & 0xffL) << 8)
                | ((buf[off + 2] & 0xffL) << 16)
                | ((buf[off + 3] & 0xffL) << 24)
                | ((buf[off + 4] & 0xffL) << 32)
                | ((buf[off + 5] & 0xffL) << 40)
                | ((buf[off + 6] & 0xffL) << 48)
                | ((buf[off + 7] & 0xffL) << 56);
    }

    public static byte[] u32LE(final long value) {
        final ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt((int) (value & 0xffffffffL));
        return buf.array();
    }

    public static byte[] u64LE(final long value) {
        final ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(value);
        return buf.array();
    }

    public static byte[] u16LE(final int value) {
        final ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) (value & 0xffff));
        return buf.array();
    }

    private OuraPacket() {
        this.tag = 0;
        this.payload = new byte[0];
    }
}
