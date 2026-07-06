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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.moyoungring.MoyoungRingConstants;

/**
 * Unit tests for the MoYoung / CRRepa ring's "rolling accumulation" model:
 *
 * <ul>
 *   <li>{@link MoyoungRingDeviceSupport#isNewRecord} — the timestamp gate used by
 *       the single-frame LIST handlers. It must reject garbage-old samples (a
 *       mis-decoded HR-LIST record can decode to 1971), reject absurd-future
 *       samples, and otherwise accept records strictly newer than the HWM.</li>
 *   <li>{@link MoyoungRingPacket#toSamples} — the paged-timeline expander used by
 *       the timing handlers, which persist via insert-or-replace (dedup by
 *       timestamp+device). This relies on timestamps being STABLE across
 *       re-parses so the upsert deduplicates instead of duplicating.</li>
 * </ul>
 */
public class MoyoungRingAccumulationTest {

    private static final int SLOT = MoyoungRingConstants.TIMING_SLOT_MINUTES; // 5
    private static final long SLOT_MS = SLOT * 60_000L;
    private static final long DAY_START = 1_780_000_000_000L;

    /** Accepts any non-zero slot. */
    private static final MoyoungRingPacket.SlotFilter NONZERO = v -> v != 0;

    // ---- isNewRecord: floor / future / hwm gating ----

    @Test
    public void isNewRecord_rejectsTimestampsAtOrBelowThe2020Floor() {
        final long floor = MoyoungRingConstants.TS_FLOOR_MS; // 2020-01-01
        // Exactly the floor and below are rejected regardless of a zeroed HWM.
        assertFalse(MoyoungRingDeviceSupport.isNewRecord(floor, 0L));
        assertFalse(MoyoungRingDeviceSupport.isNewRecord(floor - 1L, 0L));
        assertFalse(MoyoungRingDeviceSupport.isNewRecord(0L, 0L));
        assertFalse(MoyoungRingDeviceSupport.isNewRecord(-1L, 0L));
        // The concrete regression: a wrong HR-LIST layout decodes to ~1971.
        final long ts1971 = 40_000_000_000L; // ~1971-04, well below the 2020 floor
        assertTrue("guard: sample really is below the floor", ts1971 < floor);
        assertFalse(MoyoungRingDeviceSupport.isNewRecord(ts1971, 0L));
    }

    @Test
    public void isNewRecord_rejectsTimestampsBeyondTheFutureTolerance() {
        final long now = System.currentTimeMillis();
        final long tolerance = MoyoungRingConstants.HWM_FUTURE_TOLERANCE_MS;
        // Just past the tolerance window is rejected.
        assertFalse(MoyoungRingDeviceSupport.isNewRecord(now + tolerance + 60_000L, 0L));
        // At/under the tolerance boundary (and above the floor) is accepted.
        assertTrue(MoyoungRingDeviceSupport.isNewRecord(now + tolerance, 0L));
    }

    @Test
    public void isNewRecord_acceptsRecentTimestampStrictlyAboveHwm() {
        final long now = System.currentTimeMillis();
        final long recent = now - 60_000L; // 1 min ago: above floor, not future
        final long hwm = now - 120_000L;   // 2 min ago
        assertTrue(MoyoungRingDeviceSupport.isNewRecord(recent, hwm));
    }

    @Test
    public void isNewRecord_rejectsTimestampAtOrBelowHwm() {
        final long now = System.currentTimeMillis();
        final long recent = now - 60_000L;
        assertFalse("equal to HWM is not new", MoyoungRingDeviceSupport.isNewRecord(recent, recent));
        assertFalse("older than HWM is not new", MoyoungRingDeviceSupport.isNewRecord(recent, recent + 1L));
    }

    // ---- toSamples: only plausible non-zero slots, at stable timestamps ----

    @Test
    public void toSamples_keepsOnlyPlausibleNonZeroSlotsAtSlotTimestamps() {
        // slot0=0 (empty), slot1=60 (ok), slot2=255 (0xFF sentinel, implausible),
        // slot3=0 (empty), slot4=72 (ok)
        final MoyoungRingPacket.TimingTimeline timeline =
                new MoyoungRingPacket.TimingTimeline(0, new int[]{0, 60, 0xFF, 0, 72});

        final List<MoyoungRingPacket.TimedValue> samples = MoyoungRingPacket.toSamples(
                timeline, DAY_START, SLOT, MoyoungRingConstants::plausibleHr);

        assertEquals(2, samples.size());
        // Surviving samples keep their ORIGINAL slot-index timestamps.
        assertEquals(DAY_START + 1 * SLOT_MS, samples.get(0).timestampMs);
        assertEquals(60, samples.get(0).value);
        assertEquals(DAY_START + 4 * SLOT_MS, samples.get(1).timestampMs);
        assertEquals(72, samples.get(1).value);
    }

    @Test
    public void toSamples_repeatedSlotValueYieldsSameTimestamp_upsertIsIdempotent() {
        // The same value re-appearing in the same slot index across re-syncs must map
        // to the SAME timestamp so the generic providers' insert-or-replace dedups it.
        final int[] slots = {0, 65, 0, 65};
        final MoyoungRingPacket.TimedValue first = MoyoungRingPacket.toSamples(
                new MoyoungRingPacket.TimingTimeline(0, slots.clone()), DAY_START, SLOT, NONZERO).get(1);
        final MoyoungRingPacket.TimedValue second = MoyoungRingPacket.toSamples(
                new MoyoungRingPacket.TimingTimeline(0, slots.clone()), DAY_START, SLOT, NONZERO).get(1);
        assertEquals(65, first.value);
        assertEquals(65, second.value);
        assertEquals(DAY_START + 3 * SLOT_MS, first.timestampMs);
        assertEquals(first.timestampMs, second.timestampMs);
    }

    @Test
    public void toSamples_reParsingSameTimelineTwiceYieldsIdenticalPairs() {
        // Build the timeline through the public paged-reassembly API (as the driver does),
        // then assert two independent expansions produce identical (timestamp,value) pairs.
        final MoyoungRingPacket.TimingTimeline timeline = assembleHrTimeline();
        assertNotNull(timeline);

        final List<MoyoungRingPacket.TimedValue> a = MoyoungRingPacket.toSamples(
                timeline, DAY_START, SLOT, MoyoungRingConstants::plausibleHr);
        final List<MoyoungRingPacket.TimedValue> b = MoyoungRingPacket.toSamples(
                timeline, DAY_START, SLOT, MoyoungRingConstants::plausibleHr);

        assertEquals(a.size(), b.size());
        assertTrue(a.size() > 0);
        for (int i = 0; i < a.size(); i++) {
            assertEquals("timestamp pair " + i, a.get(i).timestampMs, b.get(i).timestampMs);
            assertEquals("value pair " + i, a.get(i).value, b.get(i).value);
        }
    }

    /** Assemble a two-page HR timeline [60,0,62,63] via the public reassembler. */
    private static MoyoungRingPacket.TimingTimeline assembleHrTimeline() {
        final MoyoungRingPacket.TimingReassembler ra = new MoyoungRingPacket.TimingReassembler(
                MoyoungRingConstants.TIMING_LAST_PAGE_HR, MoyoungRingPacket.SlotFormat.U8);
        ra.add(new byte[]{0, 0, (byte) 60, 0});          // page 0: slot0=60, slot1=0
        final MoyoungRingPacket.TimingReassembler.Result r =
                ra.add(new byte[]{0, 1, (byte) 62, (byte) 63}); // page 1: slot2=62, slot3=63
        return r.timeline;
    }
}
