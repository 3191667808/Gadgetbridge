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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraEventParser;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraOpcode;
import nodomain.freeyourgadget.gadgetbridge.service.devices.oura.protocol.OuraTimeSync;

public class OuraEventParserTest {

    @Test
    public void isEventFrame_eventTagDetected() {
        // Verification example C: bundled events start with tag 0x46 (Temp event).
        final byte[] frame = new byte[]{0x46, 0x0A, (byte) 0x97, (byte) 0xB7, (byte) 0x82, 0x05,
                (byte) 0x98, 0x09, (byte) 0xF2, 0x09, (byte) 0xBF, 0x07};
        assertTrue(OuraEventParser.isEventFrame(frame));
    }

    @Test
    public void isEventFrame_controlOpcodeNotEvent() {
        // 0x11 is the drain-summary opcode, NOT an event tag.
        assertFalse(OuraEventParser.isEventFrame(new byte[]{0x11, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x00}));
    }

    @Test
    public void parseFrame_singleTempEventDecoded() {
        // Verification example C: 46 0A 97 B7 82 05 98 09 F2 09 BF 07
        final byte[] frame = new byte[]{0x46, 0x0A, (byte) 0x97, (byte) 0xB7, (byte) 0x82, 0x05,
                (byte) 0x98, 0x09, (byte) 0xF2, 0x09, (byte) 0xBF, 0x07};

        final List<RecordedEvent> recorded = new ArrayList<>();
        final OuraEventParser.EventSink sink = new OuraEventParser.EventSink() {
            @Override
            public void onUnknown(final int tag, final long wallClockMs, final byte[] payload) {
                recorded.add(new RecordedEvent(tag, wallClockMs, payload));
            }

            @Override
            public void onTemp(final long wallClockMs, final byte[] payload) {
                recorded.add(new RecordedEvent(OuraOpcode.EVT_TEMP, wallClockMs, payload));
            }
        };

        // Synthetic clock — anchor at unix 2_000_000_000 with ring boot-counter equal to the
        // captured sync-resp value, so the captured event maps to a deterministic wall-clock.
        final long ringBootAtSync = 0x0582BCC2L;
        final long eventBootTs = 0x0582B797L;
        final long unixAtSync = 2_000_000_000L;
        final long expectedWallSeconds = unixAtSync + (eventBootTs - ringBootAtSync);
        final OuraTimeSync.BootClock clock = new OuraTimeSync.BootClock(unixAtSync, ringBootAtSync);
        final int parsed = OuraEventParser.parseFrame(frame, sink, clock);

        assertEquals(1, parsed);
        assertEquals(1, recorded.size());
        final RecordedEvent ev = recorded.get(0);
        assertEquals(OuraOpcode.EVT_TEMP, ev.tag);
        assertEquals(expectedWallSeconds * 1000L, ev.wallClockMs);
        assertEquals(6, ev.payload.length);
    }

    @Test
    public void parseFrame_twoBundledEvents() {
        // Two minimal records back-to-back: Temp + IBI (length=4 → 0 payload bytes).
        final byte[] frame = new byte[]{
                0x46, 0x04, 0x00, 0x00, 0x00, 0x00,
                0x44, 0x04, 0x01, 0x00, 0x00, 0x00,
        };
        final List<Integer> tags = new ArrayList<>();
        final OuraEventParser.EventSink sink = new OuraEventParser.EventSink() {
            @Override
            public void onUnknown(final int tag, final long wallClockMs, final byte[] payload) {
                tags.add(tag);
            }

            @Override
            public void onTemp(final long wallClockMs, final byte[] payload) {
                tags.add(OuraOpcode.EVT_TEMP);
            }

            @Override
            public void onIbi(final long wallClockMs, final byte[] payload) {
                tags.add(OuraOpcode.EVT_IBI);
            }
        };
        final int parsed = OuraEventParser.parseFrame(frame, sink, null);
        assertEquals(2, parsed);
        assertEquals(OuraOpcode.EVT_TEMP, tags.get(0).intValue());
        assertEquals(OuraOpcode.EVT_IBI, tags.get(1).intValue());
    }

    @Test
    public void parseFrame_largeBundleExceedsClassicMtu() {
        // R2 mitigation: Android may silently upgrade requested MTU=247 to 517.
        // Synthesize a bundled HVN payload larger than 247B (close to the 517B worst case)
        // and confirm every record is dispatched — parser must NOT cap on classic MTU.
        final int recordCount = 50; // 50 × 12B wire = 600B, > MTU=247 and > MTU=517
        final int recordLen = 0x0A; // body length field: ts u32LE + 4-byte payload
        final byte[] frame = new byte[recordCount * (recordLen + 2)];
        for (int i = 0; i < recordCount; i++) {
            final int off = i * (recordLen + 2);
            frame[off] = (byte) OuraOpcode.EVT_TEMP;
            frame[off + 1] = (byte) recordLen;
            frame[off + 2] = (byte) (i & 0xff);
            frame[off + 3] = 0;
            frame[off + 4] = 0;
            frame[off + 5] = 0;
        }
        org.junit.Assert.assertTrue("test must exceed classic MTU=247 to be meaningful", frame.length > 247);

        final int[] tempCount = new int[1];
        final OuraEventParser.EventSink sink = new OuraEventParser.EventSink() {
            @Override
            public void onUnknown(final int tag, final long wallClockMs, final byte[] payload) {
            }

            @Override
            public void onTemp(final long wallClockMs, final byte[] payload) {
                tempCount[0]++;
            }
        };

        final int parsed = OuraEventParser.parseFrame(frame, sink, null);
        assertEquals(recordCount, parsed);
        assertEquals(recordCount, tempCount[0]);
    }

    @Test
    public void parseFrame_newTagsDispatchToTypedCallbacks() {
        // Records mirror captured shapes from protocol notes (length = 2 + len-byte).
        // All bodies use bootTs=0 + N body bytes; values are not interpreted by the dispatcher.
        final byte[] frame = new byte[]{
                (byte) 0x60, 0x12, 0, 0, 0, 0,
                0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D,
                (byte) 0x61, 0x10, 0, 0, 0, 0,
                0x09, 0x0d, (byte) 0xbf, 0x3c, 0, 0x5c, 0x25, 0, 0, 0, (byte) 0xc5, 0x55,
                (byte) 0x6e, 0x11, 0, 0, 0, 0,
                (byte) 0x96, (byte) 0x83, 0x78, (byte) 0x81, (byte) 0x88, 0x75, 0x79,
                (byte) 0xe2, (byte) 0xf5, (byte) 0xe8, (byte) 0xe5, (byte) 0xf2, (byte) 0xf8,
                (byte) 0x72, 0x10, 0, 0, 0, 0,
                0x11, 0, 0x28, 0, 0x17, 0, 0x18, 0, 0x1f, 0, 0x03, 0,
                (byte) 0x76, 0x0C, 0, 0, 0, 0,
                (byte) 0x96, (byte) 0xd9, 0x3a, 0, 0x27, (byte) 0x9c, 0x3f, 0,
                (byte) 0x6f, 0x12, 0, 0, 0, 0,
                0x06, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60, 0x60,
        };
        final List<Integer> tags = new ArrayList<>();
        final OuraEventParser.EventSink sink = new OuraEventParser.EventSink() {
            @Override
            public void onUnknown(final int tag, final long wallClockMs, final byte[] payload) {
                tags.add(tag);
            }

            @Override
            public void onIbiAmp(final long wallClockMs, final byte[] payload) {
                tags.add(OuraOpcode.EVT_IBI_AMP);
            }

            @Override
            public void onMetric(final long wallClockMs, final byte[] payload) {
                tags.add(OuraOpcode.EVT_METRIC);
            }

            @Override
            public void onSensor6E(final long wallClockMs, final byte[] payload) {
                tags.add(OuraOpcode.EVT_SENSOR_6E);
            }

            @Override
            public void onMetric72(final long wallClockMs, final byte[] payload) {
                tags.add(OuraOpcode.EVT_METRIC_72);
            }

            @Override
            public void onBedtime(final long wallClockMs, final byte[] payload) {
                tags.add(OuraOpcode.EVT_BEDTIME);
            }

            @Override
            public void onSpo2(final long wallClockMs, final byte[] payload) {
                tags.add(OuraOpcode.EVT_SPO2);
            }
        };

        final int parsed = OuraEventParser.parseFrame(frame, sink, null);
        assertEquals(6, parsed);
        assertEquals(OuraOpcode.EVT_IBI_AMP, tags.get(0).intValue());
        assertEquals(OuraOpcode.EVT_METRIC, tags.get(1).intValue());
        assertEquals(OuraOpcode.EVT_SENSOR_6E, tags.get(2).intValue());
        assertEquals(OuraOpcode.EVT_METRIC_72, tags.get(3).intValue());
        assertEquals(OuraOpcode.EVT_BEDTIME, tags.get(4).intValue());
        assertEquals(OuraOpcode.EVT_SPO2, tags.get(5).intValue());
    }

    @Test
    public void parseFrame_tapAndStressRouteToTypedCallbacks() {
        // Two minimal records — tap (0x7A) and stress (0x59) — back-to-back. Length=4 → 0 body bytes.
        final byte[] frame = new byte[]{
                (byte) 0x7A, 0x04, 0x00, 0x00, 0x00, 0x00,
                (byte) 0x59, 0x04, 0x01, 0x00, 0x00, 0x00,
        };
        final boolean[] gotTap = new boolean[]{false};
        final boolean[] gotStress = new boolean[]{false};
        final OuraEventParser.EventSink sink = new OuraEventParser.EventSink() {
            @Override
            public void onUnknown(final int tag, final long wallClockMs, final byte[] payload) {
            }

            @Override
            public void onTap(final long wallClockMs, final byte[] payload) {
                gotTap[0] = true;
            }

            @Override
            public void onStress(final long wallClockMs, final byte[] payload) {
                gotStress[0] = true;
            }
        };
        assertEquals(2, OuraEventParser.parseFrame(frame, sink, null));
        assertTrue("tap dispatched", gotTap[0]);
        assertTrue("stress dispatched", gotStress[0]);
    }

    @Test
    public void parseFrame_unknownTagFallsThroughToOnUnknown() {
        // Tag 0xA0 is not in the dispatch table; should land on onUnknown.
        final byte[] frame = new byte[]{(byte) 0xA0, 0x05, 0, 0, 0, 0, 0x42};
        final int[] unknownTag = new int[]{-1};
        final OuraEventParser.EventSink sink = new OuraEventParser.EventSink() {
            @Override
            public void onUnknown(final int tag, final long wallClockMs, final byte[] payload) {
                unknownTag[0] = tag;
            }
        };
        assertEquals(1, OuraEventParser.parseFrame(frame, sink, null));
        assertEquals(0xA0, unknownTag[0]);
    }

    @Test
    public void parseFrame_truncatedFrameStopsCleanly() {
        // Tag claims length 0x10 but only 6 bytes follow. Parser must not throw.
        final byte[] frame = new byte[]{0x46, 0x10, 0x00, 0x00, 0x00, 0x00};
        final int parsed = OuraEventParser.parseFrame(frame, new OuraEventParser.EventSink() {
            @Override
            public void onUnknown(final int tag, final long wallClockMs, final byte[] payload) {
            }
        }, null);
        assertEquals(0, parsed);
    }

    private static final class RecordedEvent {
        final int tag;
        final long wallClockMs;
        final byte[] payload;

        RecordedEvent(final int tag, final long wallClockMs, final byte[] payload) {
            this.tag = tag;
            this.wallClockMs = wallClockMs;
            this.payload = payload;
        }
    }
}
