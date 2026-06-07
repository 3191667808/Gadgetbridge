/*  Copyright (C) 2026 Ariel Saghiv

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.a10pro.jl_rcsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Drives the JieLi RCSP authentication handshake:
 *
 * <ol>
 *   <li>Phone → device: {@link RcspFrame#SESSION_INIT} (FE DC BA C0 06 00 02 00 01 EF)</li>
 *   <li>Phone → device: {@code 00 || rand16} via {@link JieliRcspAuth#getRandomAuthData()}</li>
 *   <li>Device → phone: {@code 01 || encrypted16} — we verify with our own cipher</li>
 *   <li>Phone → device: {@code 02 "pass"} on success</li>
 *   <li>Device → phone: {@code 00 || challenge16}</li>
 *   <li>Phone → device: {@code 01 || encrypted16} via {@link JieliRcspAuth#getEncryptedAuthData(byte[])}</li>
 *   <li>Device → phone: {@code 02 "pass"} — handshake complete</li>
 * </ol>
 *
 * The state machine is intentionally side-effect free: callers feed inbound
 * bytes via {@link #onInbound(byte[])} and emit outbound bytes returned by the
 * state-transition methods. This keeps it trivially unit-testable.
 */
public class JieliRcspAuthSession {
    private static final Logger LOG = LoggerFactory.getLogger(JieliRcspAuthSession.class);

    public enum State { IDLE, SENT_INIT, SENT_CHALLENGE, DEVICE_VERIFIED, COMPLETE, FAILED }

    private State state = State.IDLE;
    private byte[] phoneChallenge;

    public State getState() { return state; }
    public boolean isComplete() { return state == State.COMPLETE; }
    public boolean isFailed() { return state == State.FAILED; }

    /** First write: hard-coded session-init RCSP frame. */
    /** First write: hard-coded session-init RCSP frame. */
    public synchronized byte[] start() {
        if (state != State.IDLE) throw new IllegalStateException("session already started: " + state);
        state = State.SENT_INIT;
        LOG.info("JieLi RCSP: SESSION_INIT sent, state -> SENT_INIT");
        return RcspFrame.SESSION_INIT.clone();
    }

    /** Second write: phone challenge (raw, no RCSP envelope). */
    public synchronized byte[] sendChallenge() {
        if (state != State.SENT_INIT) throw new IllegalStateException("bad state for challenge: " + state);
        phoneChallenge = JieliRcspAuth.getRandomAuthData();
        state = State.SENT_CHALLENGE;
        LOG.info("JieLi RCSP: phone challenge sent ({}B), state -> SENT_CHALLENGE", phoneChallenge.length);
        return phoneChallenge.clone();
    }

    /**
     * Handle an inbound auth packet (raw, no envelope). Returns the next outbound
     * payload to write, or {@code null} when nothing to send. Updates state.
     */
    public synchronized byte[] onInbound(final byte[] data) {
        if (data == null || data.length == 0 || data.length > 64) return null;
        final byte head = data[0];
        switch (state) {
            case SENT_CHALLENGE: {
                if (head == 0x01 && data.length == 17) {
                    final byte[] expected = JieliRcspAuth.getEncryptedAuthData(phoneChallenge);
                    final boolean ok = MessageDigest.isEqual(expected, data);
                    // Wipe the cached challenge regardless of outcome — it must not be reusable.
                    if (phoneChallenge != null) Arrays.fill(phoneChallenge, (byte) 0);
                    phoneChallenge = null;
                    if (ok) {
                        state = State.DEVICE_VERIFIED;
                        LOG.info("JieLi RCSP: device response validated");
                        return RcspFrame.AUTH_OK.clone();
                    }
                    LOG.warn("JieLi RCSP: device response mismatch (crypto bytes redacted)");
                    state = State.FAILED;
                    return null;
                }
                return null;
            }
            case DEVICE_VERIFIED: {
                if (head == 0x00 && data.length == 17) {
                    LOG.info("JieLi RCSP: replying to device challenge");
                    return JieliRcspAuth.getEncryptedAuthData(data);
                }
                if (head == 0x02 && startsWith(data, RcspFrame.AUTH_OK)) {
                    state = State.COMPLETE;
                    LOG.info("JieLi RCSP: handshake complete");
                    return null;
                }
                return null;
            }
            case COMPLETE:
            case FAILED:
            default:
                return null;
        }
    }

    /** Reset the session so it can be re-driven on a fresh connect. */
    public synchronized void reset() {
        if (phoneChallenge != null) Arrays.fill(phoneChallenge, (byte) 0);
        phoneChallenge = null;
        state = State.IDLE;
    }

    private static boolean startsWith(final byte[] data, final byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
