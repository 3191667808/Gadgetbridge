/*  Copyright (C) 2026 The Gadgetbridge Project

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
package nodomain.freeyourgadget.gadgetbridge.devices.r20;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet;

/**
 * Coverage for the smaller per-metric history-record parsers in {@link R20Packet}
 * (HR / BP / Sleep) and the shared TZ-correcting timestamp decoder.
 *
 * <p>The 20-byte composite and 14-byte sport-record layouts already have golden
 * tests in {@link R20PacketAllRecordTest} and {@link R20PacketSportRecordTest};
 * this file fills the remaining gaps so a refactor of any one record type can't
 * silently break the others.
 */
public class R20PacketParserCoverageTest {

    private static final long EPOCH_2000_UNIX_SEC = 946684800L;

    private static byte[] hrRecord(long unixSec, int bpm) {
        ByteBuffer bb = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) (unixSec - EPOCH_2000_UNIX_SEC));
        bb.put((byte) 0);              // reserved
        bb.put((byte) bpm);
        return bb.array();
    }

    private static byte[] bpRecord(long unixSec, int sys, int dia, int hr) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) (unixSec - EPOCH_2000_UNIX_SEC));
        bb.put((byte) 1);              // valid
        bb.put((byte) sys);
        bb.put((byte) dia);
        bb.put((byte) hr);
        return bb.array();
    }

    // -------- HR --------

    @Test
    public void hrSingleRecord() {
        long ts = 1_780_000_000L; // 2026-05-29 ~10:13 UTC
        byte[] payload = hrRecord(ts, 72);

        List<R20Packet.HrRecord> recs = R20Packet.parseHrRecords(payload);
        assertEquals(1, recs.size());
        assertEquals(72, recs.get(0).bpm);
        long expected = ts * 1000L - TimeZone.getDefault().getOffset(ts * 1000L);
        assertEquals("TZ-corrected", expected, recs.get(0).timestampMs);
    }

    @Test
    public void hrFiltersImplausibleBpm() {
        long ts = 1_780_000_000L;
        byte[] p = new byte[18];
        System.arraycopy(hrRecord(ts,       0),   0, p, 0,  6); // 0    -> drop
        System.arraycopy(hrRecord(ts + 60,  72),  0, p, 6,  6); // 72   -> keep
        System.arraycopy(hrRecord(ts + 120, 250), 0, p, 12, 6); // 250  -> drop
        List<R20Packet.HrRecord> recs = R20Packet.parseHrRecords(p);
        assertEquals(1, recs.size());
        assertEquals(72, recs.get(0).bpm);
    }

    @Test
    public void hrEmptyAndNull() {
        assertEquals(0, R20Packet.parseHrRecords(new byte[0]).size());
        assertEquals(0, R20Packet.parseHrRecords(null).size());
    }

    @Test
    public void hrTruncatedTailDropped() {
        long ts = 1_780_000_000L;
        byte[] p = new byte[10];                       // 1 record + 4 bytes
        System.arraycopy(hrRecord(ts, 72), 0, p, 0, 6);
        assertEquals(1, R20Packet.parseHrRecords(p).size());
    }

    // -------- BP --------

    @Test
    public void bpSingleRecord() {
        long ts = 1_780_000_000L;
        byte[] payload = bpRecord(ts, 118, 76, 70);
        List<R20Packet.BpRecord> recs = R20Packet.parseBpRecords(payload);
        assertEquals(1, recs.size());
        R20Packet.BpRecord r = recs.get(0);
        assertEquals(118, r.systolic);
        assertEquals(76,  r.diastolic);
        assertEquals(70,  r.hr);
        long expected = ts * 1000L - TimeZone.getDefault().getOffset(ts * 1000L);
        assertEquals("TZ-corrected", expected, r.timestampMs);
    }

    @Test
    public void bpSkipsZeroSystolicMarker() {
        long ts = 1_780_000_000L;
        byte[] p = new byte[24];
        System.arraycopy(bpRecord(ts,       0,   0,  0),   0, p, 0,  8); // empty marker
        System.arraycopy(bpRecord(ts + 60,  120, 80, 72),  0, p, 8,  8); // real
        System.arraycopy(bpRecord(ts + 120, 130, 85, 75),  0, p, 16, 8); // real
        List<R20Packet.BpRecord> recs = R20Packet.parseBpRecords(p);
        assertEquals(2, recs.size());
        assertEquals(120, recs.get(0).systolic);
        assertEquals(130, recs.get(1).systolic);
    }

    @Test
    public void bpEmptyAndNull() {
        assertEquals(0, R20Packet.parseBpRecords(new byte[0]).size());
        assertEquals(0, R20Packet.parseBpRecords(null).size());
    }

    @Test
    public void bpTruncatedTailDropped() {
        long ts = 1_780_000_000L;
        byte[] p = new byte[13];
        System.arraycopy(bpRecord(ts, 118, 76, 70), 0, p, 0, 8);
        assertEquals(1, R20Packet.parseBpRecords(p).size());
    }

    // -------- TZ correction --------

    /**
     * The ring stores wall-clock-local-time-as-UTC. After the parser correction
     * a record timestamped "2026-06-08 14:00 local" should land on
     * "2026-06-08 14:00 UTC" minus the JVM's TZ offset for that moment.
     *
     * <p>This pins the formula independently of the per-record tests above.
     */
    @Test
    public void tzCorrectionDirection() {
        // Build a deterministic record at a known instant.
        long localAsUtcSec = 1_780_900_000L; // arbitrary 2026 timestamp
        byte[] payload = hrRecord(localAsUtcSec, 72);

        R20Packet.HrRecord parsed = R20Packet.parseHrRecords(payload).get(0);

        // The parser must subtract tz_offset, so the parsed value is always
        // <= the naive (no-correction) value. In TZ=UTC they're equal; in any
        // positive-offset TZ (Israel, Berlin) parsed < naive.
        long naive = localAsUtcSec * 1000L;
        long tzOffsetMs = TimeZone.getDefault().getOffset(naive);
        assertEquals(naive - tzOffsetMs, parsed.timestampMs);
        assertTrue("parsed never exceeds naive ts", parsed.timestampMs <= naive);
    }

    /** CRC-16 (Yucheng variant) self-check via roundtrip: encode() computes a
     *  CRC trailer and decode() must parse the resulting dtype + payload.
     *  (decode() trusts the transport-level BLE GATT integrity for the bytes;
     *  the explicit application-level CRC is used by the firmware-side parser.) */
    @Test
    public void packetEncodeDecodeRoundTrip() {
        R20Packet pkt = R20Packet.healthHistory(0x0506); // HEALTH_HISTORY_HEART
        byte[] bytes = pkt.encode();
        R20Packet decoded = R20Packet.decode(bytes);
        assertTrue("decode succeeds on well-formed frame", decoded != null);
        assertEquals("dtype preserved", 0x0506, decoded.getDataType());
        assertEquals("payload length preserved", 0, decoded.getPayload().length);
    }

    @Test
    public void packetDecodeRejectsTooShort() {
        // Anything < 6 bytes can't even hold the header+CRC; must not crash.
        assertEquals(null, R20Packet.decode(new byte[]{0x05, 0x06}));
        assertEquals(null, R20Packet.decode(new byte[0]));
        assertEquals(null, R20Packet.decode(null));
    }

    /** Frame with a declared length larger than the actual buffer must be
     *  rejected without throwing — the parser previously trusted the length
     *  field unconditionally and would have IndexOutOfBoundsException'd. */
    @Test
    public void packetDecodeRejectsOverflowedLengthField() {
        // dtype + length=0xFFFF (way larger than the 6-byte buffer)
        byte[] bad = new byte[]{0x05, 0x06, (byte) 0xFF, (byte) 0xFF, 0x00, 0x00};
        assertEquals(null, R20Packet.decode(bad));
    }
}
