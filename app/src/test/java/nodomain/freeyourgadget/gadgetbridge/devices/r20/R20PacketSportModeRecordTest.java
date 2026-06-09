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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet;

/**
 * Validates the 25-byte manual-workout / sport-mode session record parser
 * against the YCBT SDK byte arithmetic from
 * {@code DataUnpack.unpackHealthData} case 45.
 *
 * <p>No live capture of this opcode exists yet for the user's R20 fw 2.32 —
 * this test pins the parser to the SDK reference so when a real capture
 * lands (after the user runs a workout from the ring), we can drop the
 * captured bytes in as an additional fixture without risk of regression
 * on the format mapping.
 */
public class R20PacketSportModeRecordTest {

    private static final long EPOCH_2000_UNIX_SEC = 946684800L;

    /** Build a 25-byte SDK-shaped session record. */
    private static byte[] buildRecord(long startUnixSec, long endUnixSec,
                                      long sportSteps, int distance, int kcal,
                                      int sportMode, int startMethod, int avgHr,
                                      long activeDurationSec, int minHr, int maxHr) {
        ByteBuffer bb = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) (startUnixSec - EPOCH_2000_UNIX_SEC));
        bb.putInt((int) (endUnixSec   - EPOCH_2000_UNIX_SEC));
        bb.putInt((int) sportSteps);
        bb.putShort((short) distance);
        bb.putShort((short) kcal);
        bb.put((byte) sportMode);
        bb.put((byte) startMethod);
        bb.put((byte) avgHr);
        bb.putInt((int) activeDurationSec);
        bb.put((byte) minHr);
        bb.put((byte) maxHr);
        return bb.array();
    }

    @Test
    public void parsesSingleRunningSession() {
        long start = 1_780_000_000L;
        long end   = start + 1800;
        byte[] payload = buildRecord(start, end,
                /*steps*/    4321,
                /*dist*/     3500,
                /*kcal*/     245,
                /*mode*/     2,            // Running
                /*method*/   0,            // manual start
                /*avgHr*/    142,
                /*active*/   1740,         // 60 s paused
                /*minHr*/    98,
                /*maxHr*/    168);

        List<R20Packet.SportModeRecord> r = R20Packet.parseSportModeRecords(payload);
        assertEquals(1, r.size());
        R20Packet.SportModeRecord s = r.get(0);

        long tz = TimeZone.getDefault().getOffset(start * 1000L);
        assertEquals("TZ-corrected start", start * 1000L - tz, s.startTimeMs);
        assertEquals("TZ-corrected end",   end   * 1000L - tz, s.endTimeMs);
        assertEquals(4321L, s.sportSteps);
        assertEquals(3500,  s.distanceMeters);
        assertEquals(245,   s.calorieKcal);
        assertEquals(2,     s.sportMode);
        assertEquals(0,     s.startMethod);
        assertTrue(s.isManualStart());
        assertFalse(s.isAutoDetected());
        assertEquals(142,   s.avgHr);
        assertEquals(1740L, s.activeDurationSec);
        assertEquals(98,    s.minHr);
        assertEquals(168,   s.maxHr);
    }

    @Test
    public void distinguishesManualVsAutoDetect() {
        long start = 1_780_000_000L;
        byte[] manual = buildRecord(start, start + 600, 800, 600, 30, 1, 0, 110, 600, 90, 130);
        byte[] auto   = buildRecord(start, start + 600, 800, 600, 30, 1, 1, 110, 600, 90, 130);
        assertTrue(R20Packet.parseSportModeRecords(manual).get(0).isManualStart());
        assertTrue(R20Packet.parseSportModeRecords(auto).get(0).isAutoDetected());
    }

    @Test
    public void parsesFullBlockMultipleSessions() {
        long base = 1_780_900_000L;
        byte[] payload = new byte[75];     // 3 sessions × 25 bytes
        for (int i = 0; i < 3; i++) {
            byte[] rec = buildRecord(base + i * 7200L, base + i * 7200L + 1800L,
                    1000L * (i + 1), 800 * (i + 1), 50 * (i + 1),
                    2 + i, /*method*/ i % 2, 130 + i, 1750L, 95, 160 + i);
            System.arraycopy(rec, 0, payload, i * 25, 25);
        }
        List<R20Packet.SportModeRecord> sessions = R20Packet.parseSportModeRecords(payload);
        assertEquals(3, sessions.size());
        // Strictly increasing start timestamps.
        for (int i = 1; i < sessions.size(); i++) {
            assertTrue(sessions.get(i).startTimeMs > sessions.get(i - 1).startTimeMs);
        }
        assertEquals(1000L, sessions.get(0).sportSteps);
        assertEquals(3000L, sessions.get(2).sportSteps);
    }

    @Test
    public void emptyAndNullPayload() {
        assertEquals(0, R20Packet.parseSportModeRecords(new byte[0]).size());
        assertEquals(0, R20Packet.parseSportModeRecords(null).size());
    }

    @Test
    public void truncatedTrailingBytesDropped() {
        long s = 1_780_000_000L;
        byte[] rec = buildRecord(s, s + 600, 500, 400, 20, 1, 0, 110, 600, 90, 130);
        byte[] payload = new byte[rec.length + 10];          // 10 trailing bytes < 25
        System.arraycopy(rec, 0, payload, 0, rec.length);
        assertEquals(1, R20Packet.parseSportModeRecords(payload).size());
    }

    @Test
    public void sportModeNameKnownAndUnknown() {
        assertEquals("Running",  R20Packet.sportModeName(2));
        assertEquals("Cycling",  R20Packet.sportModeName(3));
        assertEquals("Swimming", R20Packet.sportModeName(6));
        // Unknown mode falls back to "Sport #N".
        assertEquals("Sport #99", R20Packet.sportModeName(99));
        assertEquals("Sport #0",  R20Packet.sportModeName(0));
    }

    /** uint32 steps field must round-trip values that exceed int16 range. */
    @Test
    public void supportsLargeStepCount() {
        long start = 1_780_000_000L;
        long steps = 80_000L;             // marathon-ish, fits in u32 but not u16
        byte[] payload = buildRecord(start, start + 14400L,
                steps, 42_195, 2400, 2, 0, 145, 14400, 90, 175);
        R20Packet.SportModeRecord s = R20Packet.parseSportModeRecords(payload).get(0);
        assertEquals(80_000L, s.sportSteps);
        assertEquals(42_195, s.distanceMeters);
    }
}
