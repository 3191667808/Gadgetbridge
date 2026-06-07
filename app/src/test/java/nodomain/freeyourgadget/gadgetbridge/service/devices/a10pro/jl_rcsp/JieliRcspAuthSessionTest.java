/*  Copyright (C) 2026 Ariel Saghiv

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro.jl_rcsp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class JieliRcspAuthSessionTest {

    @Test
    public void happyPathDrivesFullHandshake() {
        final JieliRcspAuthSession s = new JieliRcspAuthSession();
        assertEquals(JieliRcspAuthSession.State.IDLE, s.getState());

        // 1. session init
        assertArrayEquals(RcspFrame.SESSION_INIT, s.start());
        assertEquals(JieliRcspAuthSession.State.SENT_INIT, s.getState());

        // 2. phone challenge (random)
        final byte[] challenge = s.sendChallenge();
        assertEquals(17, challenge.length);
        assertEquals(0x00, challenge[0]);
        assertEquals(JieliRcspAuthSession.State.SENT_CHALLENGE, s.getState());

        // 3. device echoes back the encrypted form (simulated)
        final byte[] simulatedDeviceResponse = JieliRcspAuth.getEncryptedAuthData(challenge);
        final byte[] passToken = s.onInbound(simulatedDeviceResponse);
        assertArrayEquals(RcspFrame.AUTH_OK, passToken);
        assertEquals(JieliRcspAuthSession.State.DEVICE_VERIFIED, s.getState());

        // 4. device sends its challenge, we respond
        final byte[] deviceChallenge = new byte[17];
        deviceChallenge[0] = 0x00;
        for (int i = 0; i < 16; i++) deviceChallenge[i + 1] = (byte) (0xAA ^ i);
        final byte[] ourReply = s.onInbound(deviceChallenge);
        assertEquals(17, ourReply.length);
        assertEquals(0x01, ourReply[0]);
        assertArrayEquals(JieliRcspAuth.getEncryptedAuthData(deviceChallenge), ourReply);
        assertEquals(JieliRcspAuthSession.State.DEVICE_VERIFIED, s.getState());

        // 5. device pass token completes handshake
        final byte[] none = s.onInbound(RcspFrame.AUTH_OK);
        assertNull(none);
        assertTrue(s.isComplete());
    }

    @Test
    public void deviceResponseMismatchMarksFailed() {
        final JieliRcspAuthSession s = new JieliRcspAuthSession();
        s.start();
        s.sendChallenge();
        final byte[] bogus = new byte[17];
        bogus[0] = 0x01;
        final byte[] reply = s.onInbound(bogus);
        assertNull(reply);
        assertTrue(s.isFailed());
    }

    @Test
    public void inboundBeforeStartIsIgnored() {
        final JieliRcspAuthSession s = new JieliRcspAuthSession();
        assertNull(s.onInbound(new byte[]{0x02, 0x70}));
        assertEquals(JieliRcspAuthSession.State.IDLE, s.getState());
    }

    @Test
    public void inboundRejectsOversizedFrames() {
        final JieliRcspAuthSession s = new JieliRcspAuthSession();
        s.start();
        s.sendChallenge();
        assertNull(s.onInbound(new byte[1024]));
        // State must not advance — oversized frame is silently ignored, no crash
        assertEquals(JieliRcspAuthSession.State.SENT_CHALLENGE, s.getState());
    }

    @Test
    public void resetClearsStateForReconnect() {
        final JieliRcspAuthSession s = new JieliRcspAuthSession();
        s.start();
        s.sendChallenge();
        s.reset();
        assertEquals(JieliRcspAuthSession.State.IDLE, s.getState());
        // Can be restarted
        assertNotNull(s.start());
    }

    @Test
    public void startReturnsIndependentCopy() {
        // Mutating the returned frame must not corrupt the constant
        final JieliRcspAuthSession s = new JieliRcspAuthSession();
        final byte[] init = s.start();
        init[0] = 0x55;
        assertEquals((byte) 0xFE, RcspFrame.SESSION_INIT[0]);
    }
}
