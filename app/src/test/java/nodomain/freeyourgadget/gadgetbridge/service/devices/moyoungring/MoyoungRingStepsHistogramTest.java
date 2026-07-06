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
package nodomain.freeyourgadget.gadgetbridge.service.devices.moyoungring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.devices.moyoungring.MoyoungRingConstants;

/**
 * Pure-JVM tests for the MoYoung / CRRepa ring per-slot step histogram parser
 * (cmd 2/18), covering little-endian slot decoding, slot timestamping, zero-slot
 * skipping and the today-future-slot filter. Derived from the decompiled companion
 * app {@code i1/k.c(byte[])} + {@code z1/c}.
 */
public class MoyoungRingStepsHistogramTest {

    /** Build a [day][u16 LE per slot...] histogram payload. */
    private static byte[] payload(final int day, final int... slots) {
        final byte[] out = new byte[1 + slots.length * 2];
        out[0] = (byte) day;
        for (int i = 0; i < slots.length; i++) {
            out[1 + i * 2] = (byte) (slots[i] & 0xFF);          // low byte
            out[1 + i * 2 + 1] = (byte) ((slots[i] >> 8) & 0xFF); // high byte
        }
        return out;
    }

    @Test
    public void parsesDayMarkerAndPerSlotCounts() {
        final MoyoungRingPacket.StepsHistogram h =
                MoyoungRingPacket.parseStepsHistogram(payload(1, 16, 1000, 0, 300));
        assertNotNull(h);
        assertEquals(1, h.day);
        assertEquals(4, h.slotSteps.length);
        assertEquals(16, h.slotSteps[0]);
        assertEquals(1000, h.slotSteps[1]);
        assertEquals(0, h.slotSteps[2]);
        assertEquals(300, h.slotSteps[3]);
    }

    @Test
    public void decodesSlotsLittleEndian() {
        // Raw bytes: day=0, slot0 = {0x2C, 0x01} => 0x012C = 300, slot1 = {0xFF, 0xFF} => 65535
        final byte[] raw = new byte[]{0x00, 0x2C, 0x01, (byte) 0xFF, (byte) 0xFF};
        final MoyoungRingPacket.StepsHistogram h = MoyoungRingPacket.parseStepsHistogram(raw);
        assertNotNull(h);
        assertEquals(0, h.day);
        assertEquals(300, h.slotSteps[0]);
        assertEquals(65535, h.slotSteps[1]);
    }

    @Test
    public void emptyPayloadReturnsNull() {
        assertNull(MoyoungRingPacket.parseStepsHistogram(null));
        assertNull(MoyoungRingPacket.parseStepsHistogram(new byte[0]));
    }

    @Test
    public void dayOnlyPayloadHasNoSlots() {
        final MoyoungRingPacket.StepsHistogram h =
                MoyoungRingPacket.parseStepsHistogram(new byte[]{0x05});
        assertNotNull(h);
        assertEquals(5, h.day);
        assertEquals(0, h.slotSteps.length);
    }

    @Test
    public void trailingOddByteIsIgnored() {
        // day + one full slot (2 bytes) + one dangling byte
        final byte[] raw = new byte[]{0x02, 0x0A, 0x00, 0x7F};
        final MoyoungRingPacket.StepsHistogram h = MoyoungRingPacket.parseStepsHistogram(raw);
        assertNotNull(h);
        assertEquals(1, h.slotSteps.length);
        assertEquals(10, h.slotSteps[0]);
    }

    @Test
    public void nonZeroSlotsYieldStepsAtCorrectTimestamps() {
        // Emulate the DeviceSupport persistence loop: skip zero slots, timestamp each
        // kept slot at dayStart + slotIndex * 30 minutes.
        final MoyoungRingPacket.StepsHistogram h =
                MoyoungRingPacket.parseStepsHistogram(payload(1, 0, 120, 0, 0, 55));
        assertNotNull(h);

        final TimeZone utc = TimeZone.getTimeZone("UTC");
        final long now = 1_700_000_000_000L; // 2023-11-14T22:13:20Z
        final long dayStart = MoyoungRingPacket.startOfDayMillis(now, h.day, utc);

        final List<long[]> kept = new ArrayList<>(); // {timestampMs, steps}
        for (int i = 0; i < h.slotSteps.length; i++) {
            if (h.slotSteps[i] <= 0) continue;
            kept.add(new long[]{MoyoungRingPacket.slotTimestampMillis(dayStart, i), h.slotSteps[i]});
        }

        // Only slots 1 (120) and 4 (55) are non-zero.
        assertEquals(2, kept.size());
        final long slotMs = (long) MoyoungRingConstants.STEPS_SLOT_MINUTES * 60_000L;
        assertEquals(dayStart + 1 * slotMs, kept.get(0)[0]);
        assertEquals(120, kept.get(0)[1]);
        assertEquals(dayStart + 4 * slotMs, kept.get(1)[0]);
        assertEquals(55, kept.get(1)[1]);
    }

    @Test
    public void slotTimestampSchemeIsThirtyMinuteAligned() {
        final TimeZone utc = TimeZone.getTimeZone("UTC");
        final long now = 1_700_000_000_000L;

        // In UTC there is no offset/DST, so start-of-day is an exact multiple of 24h.
        final long start = MoyoungRingPacket.startOfDayMillis(now, 0, utc);
        assertEquals((now / 86_400_000L) * 86_400_000L, start);

        // daysAgo shifts back by whole days.
        assertEquals(start - 86_400_000L, MoyoungRingPacket.startOfDayMillis(now, 1, utc));

        // Slot spacing is exactly 30 minutes.
        assertEquals(start, MoyoungRingPacket.slotTimestampMillis(start, 0));
        assertEquals(start + 3 * 30 * 60_000L, MoyoungRingPacket.slotTimestampMillis(start, 3));
        assertEquals(start + 47 * 30 * 60_000L, MoyoungRingPacket.slotTimestampMillis(start, 47));
    }

    @Test
    public void todayFilterZeroesFutureSlots() {
        // 48 slots all = 1; at 10:00 (600 min) cutoff = 600/30 + 1 = 21.
        final int[] slots = new int[MoyoungRingConstants.STEPS_SLOTS_PER_DAY];
        for (int i = 0; i < slots.length; i++) slots[i] = 1;

        final int[] filtered = MoyoungRingPacket.filterTodayFutureSlots(slots, 10 * 60);
        for (int i = 0; i < filtered.length; i++) {
            if (i <= 20) {
                assertEquals("slot " + i + " should be kept", 1, filtered[i]);
            } else {
                assertEquals("slot " + i + " should be zeroed", 0, filtered[i]);
            }
        }
        // Input array is not mutated.
        assertEquals(1, slots[47]);
    }

    @Test
    public void todayFilterAtMidnightKeepsOnlyFirstSlot() {
        final int[] slots = new int[4];
        for (int i = 0; i < slots.length; i++) slots[i] = 5;
        final int[] filtered = MoyoungRingPacket.filterTodayFutureSlots(slots, 0);
        // cutoff = 0/30 + 1 = 1 -> keep slot 0, zero the rest.
        assertEquals(5, filtered[0]);
        assertEquals(0, filtered[1]);
        assertEquals(0, filtered[2]);
        assertEquals(0, filtered[3]);
    }
}
