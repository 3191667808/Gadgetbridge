/*  Copyright (C) 2026 Ariel Saghiv

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro.jl_rcsp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * JieLi RCSP envelope codec.
 *
 * <pre>
 *  +----------+-------+--------+--------------+---------+----+
 *  | FE DC BA | flags | opCode | paramLen(2B) | body…   | EF |
 *  +----------+-------+--------+--------------+---------+----+
 *    magic(3B)  (1B)    (1B)   big-endian 16b  (N bytes) (1B)
 * </pre>
 *
 * <ul>
 *   <li>flags bit 7 = direction (1=request, 0=response)
 *   <li>flags bit 6 = hasResponse (1=phone expects ack)
 *   <li>common flag values: 0xC0 (request+ack), 0x80 (request, no ack), 0x00 (response)
 * </ul>
 *
 * Protocol notes derived from `jumpingmushroom/e87_badge` documentation and
 * verified against captured JieLi RCSP exchanges.
 */
public final class RcspFrame {

    private static final byte[] MAGIC = {(byte) 0xFE, (byte) 0xDC, (byte) 0xBA};
    private static final byte TAIL = (byte) 0xEF;

    public static final byte FLAG_REQUEST_ACK = (byte) 0xC0;
    public static final byte FLAG_REQUEST_NO_ACK = (byte) 0x80;
    public static final byte FLAG_RESPONSE = (byte) 0x00;

    // Auth-flow opcodes
    public static final byte OP_DISCONNECT_CLASSIC_BT = 0x06;
    public static final byte OP_GET_TARGET_INFO = 0x03;
    public static final byte OP_GET_SYS_INFO = 0x07;

    /** Hard-coded session-init frame matching {@code RcspAuth.getResetAuthFlagCmdData()}. */
    public static final byte[] SESSION_INIT = {
            (byte) 0xFE, (byte) 0xDC, (byte) 0xBA,
            FLAG_REQUEST_ACK, OP_DISCONNECT_CLASSIC_BT,
            0x00, 0x02, 0x00, 0x01, TAIL
    };

    /** "\x02pass" — emitted by both sides once their challenge has been validated. */
    public static final byte[] AUTH_OK = {0x02, 0x70, 0x61, 0x73, 0x73};

    public final byte flags;
    public final byte opCode;
    public final byte[] body;

    public RcspFrame(final byte flags, final byte opCode, final byte[] body) {
        this.flags = flags;
        this.opCode = opCode;
        this.body = body == null ? new byte[0] : body;
    }

    public byte[] encode() {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(8 + body.length);
        out.write(MAGIC, 0, 3);
        out.write(flags & 0xff);
        out.write(opCode & 0xff);
        out.write((body.length >> 8) & 0xff);
        out.write(body.length & 0xff);
        out.write(body, 0, body.length);
        out.write(TAIL & 0xff);
        return out.toByteArray();
    }

    /** Encode a request frame expecting an ack. */
    public static byte[] request(final byte opCode, final byte... body) {
        return new RcspFrame(FLAG_REQUEST_ACK, opCode, body).encode();
    }

    /** Upper bound on a body length we will accept — guards against DoS / OOM on malformed input. */
    public static final int MAX_BODY_LEN = 16 * 1024;

    /** Scan a buffer for valid RCSP frames. Returns all decoded frames found. */
    public static List<RcspFrame> decodeAll(final byte[] buffer) {
        final List<RcspFrame> frames = new ArrayList<>();
        if (buffer == null) return frames;
        int i = 0;
        while (i + 8 <= buffer.length) {
            if (buffer[i] != MAGIC[0] || buffer[i + 1] != MAGIC[1] || buffer[i + 2] != MAGIC[2]) {
                i++;
                continue;
            }
            final byte flags = buffer[i + 3];
            final byte opCode = buffer[i + 4];
            final int len = ((buffer[i + 5] & 0xff) << 8) | (buffer[i + 6] & 0xff);
            if (len < 0 || len > MAX_BODY_LEN) {
                i++;
                continue;
            }
            final int tailIdx = i + 7 + len;
            if (tailIdx >= buffer.length || buffer[tailIdx] != TAIL) {
                i++;
                continue;
            }
            final byte[] body = new byte[len];
            System.arraycopy(buffer, i + 7, body, 0, len);
            frames.add(new RcspFrame(flags, opCode, body));
            i = tailIdx + 1;
        }
        return frames;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("RcspFrame{flags=0x");
        sb.append(String.format("%02x", flags & 0xff));
        sb.append(", op=0x").append(String.format("%02x", opCode & 0xff));
        sb.append(", body=");
        for (byte b : body) sb.append(String.format("%02x", b & 0xff));
        return sb.append('}').toString();
    }
}
