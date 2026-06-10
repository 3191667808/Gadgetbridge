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

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.r20.R20Packet;

public class R20PacketSleepSessionTest {

    private static final long EPOCH_2000_UNIX_SEC = 946684800L;

    private static void putTs(ByteBuffer bb, long unixSec) {
        bb.putInt((int) (unixSec - EPOCH_2000_UNIX_SEC));
    }

    private static void putStage(ByteBuffer bb, int type, long unixSec, int durationSec) {
        bb.put((byte) type);
        putTs(bb, unixSec);
        bb.put((byte) durationSec);
        bb.put((byte) (durationSec >> 8));
        bb.put((byte) (durationSec >> 16));
    }

    private static long expectedMs(long unixSec) {
        long asMs = unixSec * 1000L;
        return asMs - java.util.TimeZone.getDefault().getOffset(asMs);
    }

    @Test
    public void parsesNormalHeaderAndStages() {
        long start = 1_780_000_000L;
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) 0);
        bb.putShort((short) 44);
        putTs(bb, start);
        putTs(bb, start + 8 * 3600);
        bb.putShort((short) 2);
        bb.putShort((short) 3);
        bb.putShort((short) 90);
        bb.putShort((short) 300);
        putStage(bb, R20Packet.SleepStage.TYPE_DEEP, start, 5400);
        putStage(bb, R20Packet.SleepStage.TYPE_REM, start + 6 * 3600, 1800);
        putStage(bb, R20Packet.SleepStage.TYPE_AWAKE, start + 7 * 3600, 300);

        List<R20Packet.SleepSession> sessions = R20Packet.parseSleepSessions(bb.array());

        assertEquals(1, sessions.size());
        R20Packet.SleepSession s = sessions.get(0);
        assertEquals(expectedMs(start), s.startTimeMs);
        assertEquals(2, s.deepSleepCount);
        assertEquals(3, s.lightSleepCount);
        assertEquals(90 * 60, s.deepSleepSec);
        assertEquals(300 * 60, s.lightSleepSec);
        assertEquals(1800, s.remSleepSec);
        assertEquals(1, s.wakeCount);
        assertEquals(300, s.wakeDurationSec);
        assertEquals(3, s.stages.size());
    }

    @Test
    public void parsesSentinelHeaderDurationsAsMinutes() {
        long start = 1_780_100_000L;
        ByteBuffer bb = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) 0);
        bb.putShort((short) 28);
        putTs(bb, start);
        putTs(bb, start + 7 * 3600);
        bb.putShort((short) 0xFFFF);
        bb.putShort((short) 4);
        bb.putShort((short) 80);
        bb.putShort((short) 260);
        putStage(bb, R20Packet.SleepStage.TYPE_LIGHT, start + 80 * 60, 3600);

        R20Packet.SleepSession s = R20Packet.parseSleepSessions(bb.array()).get(0);

        assertEquals(4, s.deepSleepCount);
        assertEquals(0, s.lightSleepCount);
        assertEquals(80 * 60, s.deepSleepSec);
        assertEquals(260 * 60, s.lightSleepSec);
        assertEquals(1, s.stages.size());
    }

    @Test
    public void ignoresEmptyAndTruncatedPayloads() {
        assertEquals(0, R20Packet.parseSleepSessions(null).size());
        assertEquals(0, R20Packet.parseSleepSessions(new byte[0]).size());
        assertEquals(0, R20Packet.parseSleepSessions(new byte[19]).size());
    }

    @Test
    public void malformedShortSessionLengthDoesNotLoopForever() {
        byte[] payload = new byte[40];
        payload[2] = 12;
        assertEquals(1, R20Packet.parseSleepSessions(payload).size());
    }
}
