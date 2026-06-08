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
package nodomain.freeyourgadget.gadgetbridge.service.devices.r20;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Multi-app friendliness: the R20 driver only sends {@code HEALTH_DELETE_*}
 * after a history-stream batch when every record in that batch is older than
 * 7 days. This keeps fresh records on the ring so the OEM SmartHealth app
 * (and any other companion app sharing this device) can still read them.
 *
 * <p>Records are deduplicated by timestamp in the receiving DAOs, so
 * re-fetching the same fresh samples on every connect is harmless.
 */
public class R20DeviceSupportDeleteGateTest {

    private static final long DAY  = 24L * 3600_000L;
    private static final long WEEK = 7L * DAY;

    /** Empty batch (no records, newestTs=0) must never trigger a delete. */
    @Test
    public void emptyBatchNeverDeletes() {
        assertFalse(R20DeviceSupport.shouldDeleteHistory(0L, 1_780_000_000_000L, WEEK));
    }

    /** Fresh record (today) must preserve on-ring history. */
    @Test
    public void freshBatchPreservesOnRingHistory() {
        long now = 1_780_000_000_000L;
        long freshTs = now - (2L * 3600_000L); // 2 hours ago
        assertFalse(R20DeviceSupport.shouldDeleteHistory(freshTs, now, WEEK));
    }

    /** Batch from exactly 6 days ago — still within the protected window. */
    @Test
    public void sixDayOldBatchPreserved() {
        long now = 1_780_000_000_000L;
        assertFalse(R20DeviceSupport.shouldDeleteHistory(now - 6 * DAY, now, WEEK));
    }

    /** Exactly 7 days old → boundary case → safe to delete. */
    @Test
    public void sevenDayBoundaryDeletes() {
        long now = 1_780_000_000_000L;
        assertTrue(R20DeviceSupport.shouldDeleteHistory(now - WEEK, now, WEEK));
    }

    /** Stale batch (a month old) → delete to keep the on-ring buffer bounded. */
    @Test
    public void staleBatchDeletes() {
        long now = 1_780_000_000_000L;
        assertTrue(R20DeviceSupport.shouldDeleteHistory(now - 30 * DAY, now, WEEK));
    }

    /** Future timestamp (clock skew) → treated as fresh → preserved. */
    @Test
    public void futureTimestampPreserved() {
        long now = 1_780_000_000_000L;
        assertFalse(R20DeviceSupport.shouldDeleteHistory(now + 60_000L, now, WEEK));
    }
}
