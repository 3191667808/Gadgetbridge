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
import java.util.TimeZone;

public final class OuraTimeSync {

    public static byte[] buildSyncTimeFrame(final long unixTsSeconds, final int tzHalfHours) {
        final ByteBuffer payload = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(unixTsSeconds);
        payload.put((byte) (tzHalfHours & 0xff));
        return OuraPacket.frame(OuraOpcode.OP_SYNC_TIME, payload.array());
    }

    public static byte[] buildSyncTimeFrameNow() {
        final long unix = System.currentTimeMillis() / 1000L;
        final int tzMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        return buildSyncTimeFrame(unix, tzMinutes / 30);
    }

    public static SyncTimeResponse parseSyncTimeResp(final byte[] payload) {
        if (payload == null || payload.length < 5) {
            throw new IllegalArgumentException("SyncTime response too short");
        }
        final long bootTicks = OuraPacket.readU32LE(payload, 0);
        final int status = payload[4] & 0xff;
        return new SyncTimeResponse(bootTicks, status);
    }

    public static final class SyncTimeResponse {
        public final long ringBootTicks;
        public final int status;

        public SyncTimeResponse(final long ringBootTicks, final int status) {
            this.ringBootTicks = ringBootTicks;
            this.status = status;
        }
    }

    // Ring boot counter ticks at 10 Hz (one tick = 0.1 s). Empirically verified across multiple
    // SyncTime samples 16+ hrs apart: tick delta / wall delta = 10.0 exactly. The wire field at
    // offset 0 of the 0x13 response and the cursor passed to 0x10 GetEvent and every event-record
    // bootTs are all the same u32LE tick count, so a single divisor handles all three.
    public static final long TICKS_PER_SECOND = 10L;

    public static final class BootClock {
        private final long unixAtSync;
        private final long ringBootTicksAtSync;

        public BootClock(final long unixAtSync, final long ringBootTicksAtSync) {
            this.unixAtSync = unixAtSync;
            this.ringBootTicksAtSync = ringBootTicksAtSync;
        }

        public long wallClockOf(final long eventBootTicks) {
            return unixAtSync + (eventBootTicks - ringBootTicksAtSync) / TICKS_PER_SECOND;
        }

        public long bootTicksOf(final long wallSeconds) {
            return ringBootTicksAtSync + (wallSeconds - unixAtSync) * TICKS_PER_SECOND;
        }
    }

    private OuraTimeSync() {
    }
}
