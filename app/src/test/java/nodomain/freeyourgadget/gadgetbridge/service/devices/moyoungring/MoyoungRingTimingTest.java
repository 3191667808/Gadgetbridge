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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.moyoungring.MoyoungRingConstants;

/**
 * Unit tests for the MoYoung / CRRepa ring's paged daily-timeline ("timing")
 * parsing and reassembly, derived from the decompiled companion app state
 * machines (i1.f/d/g/l). Each per-day timeline arrives PAGED: multiple response
 * frames, each carrying {@code [day][pageIndex][per-slot values...]}. Pages are
 * concatenated in page order; concatenated slot index i maps to
 * {@code startOfDay + i * 5 minutes}.
 *
 * <p>These tests operate on the raw PAYLOAD (bytes AFTER the 6-byte header),
 * matching {@code bArr2} in the decompile.
 */
public class MoyoungRingTimingTest {

    private static final int SLOT = MoyoungRingConstants.TIMING_SLOT_MINUTES; // 5
    private static final long SLOT_MS = SLOT * 60_000L;
    // Fixed, deterministic "start of day" so timestamps are exact.
    private static final long DAY_START = 1_780_000_000_000L;

    /** Accepts any non-zero slot (used to isolate timestamp/value assertions). */
    private static final MoyoungRingPacket.SlotFilter NONZERO = v -> v != 0;

    private static byte[] page(int day, int pageIndex, int... u8slots) {
        byte[] out = new byte[2 + u8slots.length];
        out[0] = (byte) day;
        out[1] = (byte) pageIndex;
        for (int i = 0; i < u8slots.length; i++) out[2 + i] = (byte) u8slots[i];
        return out;
    }

    // ---- request builder ----

    @Test
    public void timingRequest_wireFormat_dayThenPage() {
        byte[] f = MoyoungRingPacket.timingRequest(
                MoyoungRingConstants.CMD_TIMING_HR, MoyoungRingConstants.SUB_TIMING_HR, 1, 2);
        // FD DA 10 len cmd sub day idx  -> len = 8
        assertEquals(8, f.length);
        assertEquals((byte) 0xFD, f[0]);
        assertEquals((byte) 0xDA, f[1]);
        assertEquals((byte) 0x10, f[2]);
        assertEquals((byte) 8, f[3]);
        assertEquals((byte) 2, f[4]);   // cmd
        assertEquals((byte) 15, f[5]);  // sub
        assertEquals((byte) 1, f[6]);   // day
        assertEquals((byte) 2, f[7]);   // pageIndex
    }

    // ---- page parsing ----

    @Test
    public void parsePage_u8_extractsDayIndexAndSlots() {
        MoyoungRingPacket.TimingPage p = MoyoungRingPacket.parseTimingPage(
                page(3, 0, 60, 0, 200), MoyoungRingPacket.SlotFormat.U8);
        assertNotNull(p);
        assertEquals(3, p.day);
        assertEquals(0, p.pageIndex);
        assertEquals(3, p.slots.length);
        assertEquals(60, p.slots[0]);
        assertEquals(0, p.slots[1]);
        assertEquals(200, p.slots[2]);
    }

    @Test
    public void parsePage_u16le_decodesLittleEndianAndDropsDanglingByte() {
        // day=0 idx=0, slot0 = 0x0064 = 100, slot1 = 0x00C8 = 200, trailing 0x07 ignored
        byte[] payload = new byte[]{0, 0, 0x64, 0x00, (byte) 0xC8, 0x00, 0x07};
        MoyoungRingPacket.TimingPage p = MoyoungRingPacket.parseTimingPage(
                payload, MoyoungRingPacket.SlotFormat.U16LE);
        assertNotNull(p);
        assertEquals(2, p.slots.length);
        assertEquals(100, p.slots[0]);
        assertEquals(200, p.slots[1]);
    }

    @Test
    public void parsePage_tooShort_returnsNull() {
        assertNull(MoyoungRingPacket.parseTimingPage(new byte[]{5}, MoyoungRingPacket.SlotFormat.U8));
        assertNull(MoyoungRingPacket.parseTimingPage(new byte[0], MoyoungRingPacket.SlotFormat.U8));
        assertNull(MoyoungRingPacket.parseTimingPage(null, MoyoungRingPacket.SlotFormat.U8));
    }

    // ---- multi-page reassembly (HR: last page index = 1) ----

    @Test
    public void hr_twoPages_assembleWithCorrectTimestampsAndValues() {
        MoyoungRingPacket.TimingReassembler ra = new MoyoungRingPacket.TimingReassembler(
                MoyoungRingConstants.TIMING_LAST_PAGE_HR, MoyoungRingPacket.SlotFormat.U8);

        // Page 0 (non-terminal) -> must ask for page 1, no timeline yet.
        MoyoungRingPacket.TimingReassembler.Result r0 = ra.add(page(0, 0, 60, 61));
        assertNotNull(r0);
        assertTrue(r0.needNextPage);
        assertEquals(1, r0.nextPageIndex);
        assertNull(r0.timeline);

        // Page 1 (terminal) -> assembled, no further request.
        MoyoungRingPacket.TimingReassembler.Result r1 = ra.add(page(0, 1, 62, 63));
        assertNotNull(r1);
        assertFalse(r1.needNextPage);
        assertNotNull(r1.timeline);
        assertEquals(0, r1.timeline.day);
        // Concatenated: [60,61,62,63]
        assertEquals(4, r1.timeline.slots.length);

        List<MoyoungRingPacket.TimedValue> samples = MoyoungRingPacket.toSamples(
                r1.timeline, DAY_START, SLOT, NONZERO);
        assertEquals(4, samples.size());
        for (int i = 0; i < 4; i++) {
            assertEquals("slot " + i + " timestamp", DAY_START + i * SLOT_MS, samples.get(i).timestampMs);
            assertEquals("slot " + i + " value", 60 + i, samples.get(i).value);
        }
    }

    @Test
    public void hr_emptyAndImplausibleSlotsDropped_indexAlignmentPreserved() {
        MoyoungRingPacket.TimingReassembler ra = new MoyoungRingPacket.TimingReassembler(
                MoyoungRingConstants.TIMING_LAST_PAGE_HR, MoyoungRingPacket.SlotFormat.U8);
        // slot0=0 (empty), slot1=60 (ok), slot2=255 (0xFF sentinel), slot3=70 (ok)
        ra.add(page(0, 0, 0, 60));
        MoyoungRingPacket.TimingReassembler.Result r = ra.add(page(0, 1, 0xFF, 70));
        assertNotNull(r.timeline);

        // Plausibility gate drops slot0 (0) and slot2 (0xFF), keeps slot1 & slot3
        // — but the surviving samples keep their ORIGINAL slot timestamps.
        List<MoyoungRingPacket.TimedValue> samples = MoyoungRingPacket.toSamples(
                r.timeline, DAY_START, SLOT, MoyoungRingConstants::plausibleHr);
        assertEquals(2, samples.size());
        assertEquals(DAY_START + 1 * SLOT_MS, samples.get(0).timestampMs);
        assertEquals(60, samples.get(0).value);
        assertEquals(DAY_START + 3 * SLOT_MS, samples.get(1).timestampMs);
        assertEquals(70, samples.get(1).value);
    }

    // ---- HRV: 2-byte slots, last page index = 3 ----

    @Test
    public void hrv_fourPages_u16le_assemble() {
        MoyoungRingPacket.TimingReassembler ra = new MoyoungRingPacket.TimingReassembler(
                MoyoungRingConstants.TIMING_LAST_PAGE_HRV, MoyoungRingPacket.SlotFormat.U16LE);
        // pages 0,1,2 non-terminal; page 3 terminal. One u16 slot per page.
        assertTrue(ra.add(new byte[]{0, 0, 0x28, 0x00}).needNextPage);  // 40
        assertTrue(ra.add(new byte[]{0, 1, 0x32, 0x00}).needNextPage);  // 50
        assertTrue(ra.add(new byte[]{0, 2, 0x3C, 0x00}).needNextPage);  // 60
        MoyoungRingPacket.TimingReassembler.Result r =
                ra.add(new byte[]{0, 3, 0x46, 0x00});                    // 70
        assertFalse(r.needNextPage);
        assertNotNull(r.timeline);
        List<MoyoungRingPacket.TimedValue> samples = MoyoungRingPacket.toSamples(
                r.timeline, DAY_START, SLOT, MoyoungRingConstants::plausibleHrv);
        assertEquals(4, samples.size());
        assertEquals(40, samples.get(0).value);
        assertEquals(70, samples.get(3).value);
        assertEquals(DAY_START + 3 * SLOT_MS, samples.get(3).timestampMs);
    }

    // ---- robustness: missing / out-of-order pages ----

    @Test
    public void terminalPageWithMissingEarlierPage_dropsRunWithoutCrash() {
        MoyoungRingPacket.TimingReassembler ra = new MoyoungRingPacket.TimingReassembler(
                MoyoungRingConstants.TIMING_LAST_PAGE_HR, MoyoungRingPacket.SlotFormat.U8);
        // Terminal page arrives but page 0 was never received -> no timeline, no crash.
        MoyoungRingPacket.TimingReassembler.Result r = ra.add(page(0, 1, 62, 63));
        assertNotNull(r);
        assertFalse(r.needNextPage);
        assertNull(r.timeline);
    }

    @Test
    public void outOfOrderPages_stillAssemble() {
        MoyoungRingPacket.TimingReassembler ra = new MoyoungRingPacket.TimingReassembler(
                MoyoungRingConstants.TIMING_LAST_PAGE_HR, MoyoungRingPacket.SlotFormat.U8);
        // Page 1 (terminal) arrives first but page 0 missing -> dropped.
        assertNull(ra.add(page(0, 1, 62, 63)).timeline);
        // Now page 0 arrives (asks for next); then page 1 again completes it.
        assertTrue(ra.add(page(0, 0, 60, 61)).needNextPage);
        MoyoungRingPacket.TimingReassembler.Result r = ra.add(page(0, 1, 62, 63));
        assertNotNull(r.timeline);
        assertEquals(4, r.timeline.slots.length);
    }

    @Test
    public void twoDaysConcurrently_doNotCollide() {
        MoyoungRingPacket.TimingReassembler ra = new MoyoungRingPacket.TimingReassembler(
                MoyoungRingConstants.TIMING_LAST_PAGE_HR, MoyoungRingPacket.SlotFormat.U8);
        // Interleave today (0) and yesterday (1).
        ra.add(page(0, 0, 60, 61));
        ra.add(page(1, 0, 80, 81));
        MoyoungRingPacket.TimingReassembler.Result rToday = ra.add(page(0, 1, 62, 63));
        MoyoungRingPacket.TimingReassembler.Result rYest = ra.add(page(1, 1, 82, 83));

        assertNotNull(rToday.timeline);
        assertEquals(0, rToday.timeline.day);
        assertEquals(60, rToday.timeline.slots[0]);
        assertEquals(63, rToday.timeline.slots[3]);

        assertNotNull(rYest.timeline);
        assertEquals(1, rYest.timeline.day);
        assertEquals(80, rYest.timeline.slots[0]);
        assertEquals(83, rYest.timeline.slots[3]);
    }

    @Test
    public void malformedPayload_returnsNullNoCrash() {
        MoyoungRingPacket.TimingReassembler ra = new MoyoungRingPacket.TimingReassembler(
                MoyoungRingConstants.TIMING_LAST_PAGE_HR, MoyoungRingPacket.SlotFormat.U8);
        assertNull(ra.add(new byte[]{0}));   // too short
        assertNull(ra.add(null));            // null
        // A valid page still works afterwards.
        assertTrue(ra.add(page(0, 0, 60)).needNextPage);
    }

    // ---- per-page persistence: global-index mapping (incremental sync) ----

    @Test
    public void pageToSamples_page0_mapsSlotsFromDayStart() {
        // HR uses 144 slots/page. Page 0, slot i -> globalIndex i -> DAY_START + i*5min.
        MoyoungRingPacket.TimingPage p = MoyoungRingPacket.parseTimingPage(
                page(0, 0, 60, 0, 70), MoyoungRingPacket.SlotFormat.U8);
        List<MoyoungRingPacket.TimedValue> samples = MoyoungRingPacket.pageToSamples(
                p, DAY_START, 144, SLOT, NONZERO);
        // slot1 is 0 -> dropped; slot0 & slot2 kept with their ORIGINAL slot timestamps.
        assertEquals(2, samples.size());
        assertEquals(DAY_START + 0 * SLOT_MS, samples.get(0).timestampMs);
        assertEquals(60, samples.get(0).value);
        assertEquals(DAY_START + 2 * SLOT_MS, samples.get(1).timestampMs);
        assertEquals(70, samples.get(1).value);
    }

    @Test
    public void pageToSamples_page1_offsetsByPageIndexTimesSlotsPerPage() {
        // Page 1 with slotsPerPage=144: page-local slot i -> globalIndex 144 + i.
        MoyoungRingPacket.TimingPage p = MoyoungRingPacket.parseTimingPage(
                page(0, 1, 80, 81), MoyoungRingPacket.SlotFormat.U8);
        List<MoyoungRingPacket.TimedValue> samples = MoyoungRingPacket.pageToSamples(
                p, DAY_START, 144, SLOT, NONZERO);
        assertEquals(2, samples.size());
        assertEquals(DAY_START + 144 * SLOT_MS, samples.get(0).timestampMs);
        assertEquals(80, samples.get(0).value);
        assertEquals(DAY_START + 145 * SLOT_MS, samples.get(1).timestampMs);
        assertEquals(81, samples.get(1).value);
    }

    @Test
    public void pageToSamples_hrvPage2_u16le_usesSeventyTwoSlotsPerPage() {
        // HRV: 72 slots/page, u16 values. Page 2, slot0 -> globalIndex 144.
        byte[] payload = new byte[]{0, 2, 0x28, 0x00, 0x32, 0x00}; // 40, 50
        MoyoungRingPacket.TimingPage p = MoyoungRingPacket.parseTimingPage(
                payload, MoyoungRingPacket.SlotFormat.U16LE);
        List<MoyoungRingPacket.TimedValue> samples = MoyoungRingPacket.pageToSamples(
                p, DAY_START, 72, SLOT, NONZERO);
        assertEquals(2, samples.size());
        assertEquals(DAY_START + 144 * SLOT_MS, samples.get(0).timestampMs);
        assertEquals(40, samples.get(0).value);
        assertEquals(DAY_START + 145 * SLOT_MS, samples.get(1).timestampMs);
        assertEquals(50, samples.get(1).value);
    }

    @Test
    public void pageToSamples_nullPage_returnsEmpty() {
        assertTrue(MoyoungRingPacket.pageToSamples(null, DAY_START, 144, SLOT, NONZERO).isEmpty());
    }

    // ---- temperature timeline (2/22): u16LE/10 °C, 72 slots/page, 4 pages ----

    @Test
    public void tempTimeline_slotToCelsiusAndGlobalIndexTimestamp() {
        // Temp shares HRV geometry: u16LE, 72 slots/page. Page 1, so page-local slot i
        // -> globalIndex 72 + i. Values are deci-degrees: 0x015E=350 -> 35.0 °C,
        // 0x0000 -> no reading (dropped), 0x0136=310 -> 31.0 °C.
        byte[] payload = new byte[]{0, 1, 0x5E, 0x01, 0x00, 0x00, 0x36, 0x01};
        MoyoungRingPacket.TimingPage p = MoyoungRingPacket.parseTimingPage(
                payload, MoyoungRingPacket.SlotFormat.U16LE);
        assertNotNull(p);
        assertEquals(1, p.pageIndex);
        assertEquals(3, p.slots.length);
        assertEquals(350, p.slots[0]);
        assertEquals(0, p.slots[1]);
        assertEquals(310, p.slots[2]);

        // Mirror handleTimingTemp's mapping: drop slot value 0, celsius = deci/10.0,
        // globalIndex = pageIndex*72 + i.
        List<MoyoungRingPacket.TimedValue> kept = MoyoungRingPacket.pageToSamples(
                p, DAY_START, 72, SLOT, v -> v > 0);
        assertEquals(2, kept.size());
        assertEquals(DAY_START + (72 + 0) * SLOT_MS, kept.get(0).timestampMs);
        assertEquals(35.0, kept.get(0).value / 10.0, 0.0001);
        assertEquals(DAY_START + (72 + 2) * SLOT_MS, kept.get(1).timestampMs);
        assertEquals(31.0, kept.get(1).value / 10.0, 0.0001);
    }
}
