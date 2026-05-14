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
package nodomain.freeyourgadget.gadgetbridge.test.oura;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraOpcode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraTimeSync;

public class OuraTimeSyncTest {

    @Test
    public void parseSyncTimeResp_evidence() {
        // Evidence transcript: 0x0582BCC2 boot-ticks + status 0x00
        final byte[] payload = new byte[]{(byte) 0xC2, (byte) 0xBC, (byte) 0x82, 0x05, 0x00};
        final OuraTimeSync.SyncTimeResponse resp = OuraTimeSync.parseSyncTimeResp(payload);
        assertEquals(0x0582BCC2L, resp.ringBootTicks);
        assertEquals(0, resp.status);
    }

    @Test
    public void buildSyncTimeFrame_layout() {
        final byte[] frame = OuraTimeSync.buildSyncTimeFrame(0x0000_0000_6A00_7488L, 4);
        assertEquals(OuraOpcode.OP_SYNC_TIME, frame[0] & 0xff);
        assertEquals(9, frame[1] & 0xff);
        assertEquals(11, frame.length);
        assertEquals(0x88, frame[2] & 0xff);
        assertEquals(0x74, frame[3] & 0xff);
        assertEquals(0x00, frame[4] & 0xff);
        assertEquals(0x6A, frame[5] & 0xff);
        assertEquals(0x04, frame[10] & 0xff);
    }

    @Test
    public void bootClock_wallClockMath() {
        // Ring boot counter ticks at 10 Hz. Anchor: unix=2_000_000_000 ↔ ring boot ticks=1_000_000
        // (= 100 000 s × 10). Event at 999_500 ticks = anchor − 500 ticks = anchor − 50 s.
        final OuraTimeSync.BootClock clock = new OuraTimeSync.BootClock(2_000_000_000L, 1_000_000L);
        assertEquals(1_999_999_950L, clock.wallClockOf(999_500L));
        assertEquals(2_000_000_000L, clock.wallClockOf(1_000_000L));
        assertEquals(2_000_000_001L, clock.wallClockOf(1_000_010L));
        // Reverse: wall=anchor-50s → ticks=anchor-500
        assertEquals(999_500L, clock.bootTicksOf(1_999_999_950L));
        assertEquals(1_000_010L, clock.bootTicksOf(2_000_000_001L));
    }
}
