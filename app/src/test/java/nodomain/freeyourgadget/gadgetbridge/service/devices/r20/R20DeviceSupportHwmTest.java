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
 * Read-only ring policy: Gadgetbridge never deletes on-ring history. To avoid
 * re-processing the same records on every reconnect we apply a per-metric
 * "high-water mark" filter: each incoming record's timestamp is compared
 * against the newest timestamp we've ever persisted, and only strictly newer
 * records are written to the DAO. This keeps DB traffic linear in NEW data
 * instead of total on-ring history, while leaving the ring's buffer intact
 * for the OEM SmartHealth app and any other companion app sharing the device.
 */
public class R20DeviceSupportHwmTest {

    /** Record with timestamp 0 (unset) must never pass — defensive. */
    @Test
    public void zeroTimestampRejected() {
        assertFalse(R20DeviceSupport.isNewRecord(0L, 1_780_000_000_000L));
        assertFalse(R20DeviceSupport.isNewRecord(0L, 0L));
    }

    /** Record older than HWM must be filtered out. */
    @Test
    public void olderThanHwmRejected() {
        long hwm = 1_780_000_000_000L;
        assertFalse(R20DeviceSupport.isNewRecord(hwm - 60_000L, hwm));
    }

    /** Record at exactly HWM is treated as already-seen (strict greater-than). */
    @Test
    public void atHwmRejected() {
        long hwm = 1_780_000_000_000L;
        assertFalse(R20DeviceSupport.isNewRecord(hwm, hwm));
    }

    /** Record strictly newer than HWM is accepted. */
    @Test
    public void newerThanHwmAccepted() {
        long hwm = 1_780_000_000_000L;
        assertTrue(R20DeviceSupport.isNewRecord(hwm + 1L, hwm));
        assertTrue(R20DeviceSupport.isNewRecord(hwm + 86_400_000L, hwm));
    }

    /** First-ever batch (HWM = 0) must accept any positive timestamp. */
    @Test
    public void firstBatchAcceptsEverything() {
        assertTrue(R20DeviceSupport.isNewRecord(1_780_000_000_000L, 0L));
        assertTrue(R20DeviceSupport.isNewRecord(1L, 0L));
    }

    /** HWM keys are namespaced per-metric so multiple metrics don't collide. */
    @Test
    public void hwmKeysAreDistinct() {
        assertFalse(R20DeviceSupport.HWM_HR.equals(R20DeviceSupport.HWM_BP));
        assertFalse(R20DeviceSupport.HWM_HR.equals(R20DeviceSupport.HWM_SLEEP));
        assertFalse(R20DeviceSupport.HWM_HR.equals(R20DeviceSupport.HWM_SPORT));
        assertFalse(R20DeviceSupport.HWM_HR.equals(R20DeviceSupport.HWM_ALL));
        assertFalse(R20DeviceSupport.HWM_BP.equals(R20DeviceSupport.HWM_SLEEP));
        assertTrue("HWM keys should be prefixed",
                R20DeviceSupport.HWM_HR.startsWith("r20_history_hwm_"));
    }
}
