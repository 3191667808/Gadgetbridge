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

import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet;

/**
 * Validates the 14-byte sport-history record parser against the YCBT SDK
 * reference layout from {@code DataUnpack.unpackHealthData} case 2:
 * <pre>
 *   0..3  startTime   (uint32 LE + EPOCH_2000)
 *   4..7  endTime     (uint32 LE + EPOCH_2000)
 *   8..9  steps       (uint16 LE)
 *  10..11 distance m  (uint16 LE)
 *  12..13 calorie kcal(uint16 LE)
 * </pre>
 *
 * <p>The on-device log of 2026-06-08 showed 126 bytes of sport-history payload
 * for this exact ring (9 records × 14 bytes), which previously hit the
 * "(parser TBD)" branch and was discarded — this test locks in the fix.
 */
public class R20PacketSportRecordTest {

    private static final long EPOCH_2000_UNIX_SEC = 946684800L;

    /** Build a synthetic 14-byte sport record with the SDK's exact layout. */
    private static byte[] buildRecord(long startUnixSec, long endUnixSec,
                                      int steps, int distance, int kcal) {
        ByteBuffer bb = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt((int) (startUnixSec - EPOCH_2000_UNIX_SEC));
        bb.putInt((int) (endUnixSec   - EPOCH_2000_UNIX_SEC));
        bb.putShort((short) steps);
        bb.putShort((short) distance);
        bb.putShort((short) kcal);
        return bb.array();
    }

    @Test
    public void parsesSingleRecord() {
        long start = 1_745_536_807L; // 2025-04-24T22:00:07Z
        long end   = start + 1800;   // 30 min walk
        byte[] payload = buildRecord(start, end, 3210, 2480, 142);

        List<R20Packet.SportRecord> records = R20Packet.parseSportRecords(payload);
        assertEquals(1, records.size());
        R20Packet.SportRecord r = records.get(0);
        // Parser subtracts TZ offset (ring stores wall-clock local time as UTC),
        // so reconstruct the expected value the same way for TZ-portable tests.
        long startMs = start * 1000L;
        long endMs   = end   * 1000L;
        long tz = java.util.TimeZone.getDefault().getOffset(startMs);
        assertEquals(startMs - tz, r.startTimeMs);
        assertEquals(endMs   - tz, r.endTimeMs);
        assertEquals(3210, r.steps);
        assertEquals(2480, r.distanceMeters);
        assertEquals(142,  r.calorieKcal);
        assertEquals(1800, r.durationSec());
    }

    /** A 126-byte payload (9 records) is exactly what the ring streamed in the
     *  2026-06-08 capture; verify parser handles a full block without dropping bytes. */
    @Test
    public void parsesFullBlockMatchingObservedSize() {
        long base = 1_780_900_000L;
        byte[] payload = new byte[126];
        for (int i = 0; i < 9; i++) {
            byte[] rec = buildRecord(base + i * 3600, base + i * 3600 + 1800,
                                     100 * (i + 1), 80 * (i + 1), 5 * (i + 1));
            System.arraycopy(rec, 0, payload, i * 14, 14);
        }

        List<R20Packet.SportRecord> records = R20Packet.parseSportRecords(payload);
        assertEquals("9 records in 126 bytes", 9, records.size());
        // Strictly increasing start times
        for (int i = 1; i < records.size(); i++) {
            assertTrue(records.get(i).startTimeMs > records.get(i - 1).startTimeMs);
        }
        // First and last have the expected step counts
        assertEquals(100, records.get(0).steps);
        assertEquals(900, records.get(8).steps);
    }

    @Test
    public void emptyAndNullPayload() {
        assertEquals(0, R20Packet.parseSportRecords(new byte[0]).size());
        assertEquals(0, R20Packet.parseSportRecords(null).size());
    }

    @Test
    public void truncatedTrailingBytes() {
        long s = 1_745_536_807L;
        byte[] rec = buildRecord(s, s + 60, 50, 30, 2);
        byte[] payload = new byte[rec.length + 7]; // 7 trailing bytes < 14
        System.arraycopy(rec, 0, payload, 0, rec.length);
        assertEquals(1, R20Packet.parseSportRecords(payload).size());
    }
}
