/*  Copyright (C) 2026 Ariel Saghiv

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro.jl_rcsp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class RcspFrameTest {

    private static byte[] hex(String s) {
        s = s.replace(" ", "");
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    public void sessionInitMatchesRcspAuthHardcoded() {
        assertArrayEquals(hex("fedcba c0 06 00 02 00 01 ef"), RcspFrame.SESSION_INIT);
    }

    @Test
    public void authOkIsLiteralPass() {
        assertArrayEquals(hex("02 70 61 73 73"), RcspFrame.AUTH_OK);
    }

    @Test
    public void encodeRequestWrapsBodyWithMagicAndTail() {
        final byte[] frame = RcspFrame.request((byte) 0x03, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x00);
        assertArrayEquals(hex("fedcba c0 03 00 05 ff ff ff ff 00 ef"), frame);
    }

    @Test
    public void decodeAllRoundTripsSessionInit() {
        final List<RcspFrame> frames = RcspFrame.decodeAll(RcspFrame.SESSION_INIT);
        assertEquals(1, frames.size());
        final RcspFrame f = frames.get(0);
        assertEquals(RcspFrame.FLAG_REQUEST_ACK, f.flags);
        assertEquals(RcspFrame.OP_DISCONNECT_CLASSIC_BT, f.opCode);
        assertArrayEquals(hex("00 01"), f.body);
    }

    @Test
    public void decodeAllSkipsGarbageBeforeMagic() {
        final byte[] buf = hex("aa bb fedcba 00 1b 00 04 00 4a 01 ea ef cc dd");
        final List<RcspFrame> frames = RcspFrame.decodeAll(buf);
        assertEquals(1, frames.size());
        assertEquals(0x00, frames.get(0).flags);
        assertEquals(0x1b, frames.get(0).opCode);
        assertArrayEquals(hex("00 4a 01 ea"), frames.get(0).body);
    }

    @Test
    public void decodeAllRejectsFrameMissingTail() {
        final byte[] buf = hex("fedcba c0 03 00 02 00 01");
        assertTrue(RcspFrame.decodeAll(buf).isEmpty());
    }
}
